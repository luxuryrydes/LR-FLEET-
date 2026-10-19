"""Background job processing against the background_jobs table (claimed with FOR UPDATE SKIP LOCKED)."""
from __future__ import annotations

import hashlib
import json
import logging
import tempfile
import traceback
import uuid
from datetime import date, datetime
from pathlib import Path

import pandas as pd
import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from .config import Config
from .excel import FileFormatError, describe_file, read_rows
from .parsers import ParseResult, VehicleIndex, parse_fuel, parse_gps, parse_master
from .reconcile import reconcile, write_workbook
from .storage import Storage

log = logging.getLogger("fleet_worker")
BATCH = 1000


class PermanentError(Exception):
    """A failure that retrying cannot fix (bad file, missing batch)."""


def connect(cfg: Config) -> psycopg.Connection:
    conn = psycopg.connect(cfg.database_url, row_factory=dict_row, autocommit=False, connect_timeout=10)
    with conn.cursor() as cur:
        cur.execute("SET search_path TO fleet, public")
    conn.commit()
    return conn


# ---------------------------------------------------------------------------------------------- queue

def recover_stale(conn: psycopg.Connection, minutes: int) -> int:
    with conn.cursor() as cur:
        cur.execute("""
            UPDATE background_jobs SET status = 'QUEUED', locked_by = NULL, locked_at = NULL,
                   last_error = coalesce(last_error, '') || ' [recovered after worker timeout]'
            WHERE status = 'RUNNING' AND locked_at < now() - make_interval(mins => %s)""", (minutes,))
        n = cur.rowcount
    conn.commit()
    return n


def claim(conn: psycopg.Connection, worker_id: str) -> dict | None:
    with conn.cursor() as cur:
        cur.execute("""
            UPDATE background_jobs SET status = 'RUNNING', locked_by = %s, locked_at = now(), started_at = now(),
                   attempts = attempts + 1
            WHERE id = (SELECT id FROM background_jobs WHERE status = 'QUEUED' AND run_after <= now()
                        ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id, job_type, payload, attempts, max_attempts""", (worker_id,))
        job = cur.fetchone()
    conn.commit()
    return job


def finish(conn: psycopg.Connection, job_id: int, result: dict) -> None:
    with conn.cursor() as cur:
        cur.execute("UPDATE background_jobs SET status = 'SUCCEEDED', result = %s, finished_at = now(), last_error = NULL WHERE id = %s",
                    (Jsonb(result), job_id))
    conn.commit()


def fail(conn: psycopg.Connection, job: dict, err: BaseException) -> None:
    conn.rollback()
    permanent = isinstance(err, (PermanentError, FileFormatError))
    retry = not permanent and job["attempts"] < job["max_attempts"]
    message = f"{type(err).__name__}: {err}"[:3900]
    with conn.cursor() as cur:
        if retry:
            cur.execute("""UPDATE background_jobs SET status = 'QUEUED', locked_by = NULL, last_error = %s,
                           run_after = now() + make_interval(mins => %s) WHERE id = %s""", (message, 2 ** job["attempts"], job["id"]))
        else:
            cur.execute("UPDATE background_jobs SET status = 'FAILED', last_error = %s, finished_at = now() WHERE id = %s",
                        (message, job["id"]))
            batch_id = (job.get("payload") or {}).get("batchId")
            if batch_id:
                cur.execute("""UPDATE import_batches SET status = 'FAILED', error_message = %s, updated_at = now()
                               WHERE id = %s AND status IN ('HEADERS_PENDING', 'PARSE_PENDING')""",
                            (_friendly(err), batch_id))
    conn.commit()


def _friendly(err: BaseException) -> str:
    if isinstance(err, (PermanentError, FileFormatError)):
        return str(err)[:1900]
    return f"Processing failed after retries ({type(err).__name__}: {err}). Use Retry, or contact support."[:1900]


def run_one(conn: psycopg.Connection, storage: Storage, worker_id: str) -> bool:
    job = claim(conn, worker_id)
    if job is None:
        return False
    log.info("job=%s type=%s attempt=%s", job["id"], job["job_type"], job["attempts"])
    try:
        payload = job["payload"] or {}
        if job["job_type"] == "DETECT_HEADERS":
            result = detect_headers(conn, storage, payload["batchId"])
        elif job["job_type"] == "PARSE_IMPORT":
            result = parse_import(conn, storage, payload["batchId"])
        elif job["job_type"] == "RECONCILIATION_REPORT":
            result = reconciliation_report(conn, storage, payload, job["id"])
        else:
            raise PermanentError(f"Unknown job type {job['job_type']}")
        finish(conn, job["id"], result)
        log.info("job=%s done result=%s", job["id"], json.dumps(result)[:300])
    except Exception as e:  # noqa: BLE001 - every failure is recorded on the job
        log.error("job=%s failed: %s\n%s", job["id"], e, traceback.format_exc())
        fail(conn, job, e)
    return True


# ---------------------------------------------------------------------------------------------- import jobs

def _batch(conn: psycopg.Connection, batch_id: int, expected_status: str) -> dict:
    with conn.cursor() as cur:
        cur.execute("""
            SELECT b.*, f.storage_key FROM import_batches b JOIN stored_files f ON f.id = b.file_id
            WHERE b.id = %s FOR UPDATE OF b""", (batch_id,))
        b = cur.fetchone()
    if b is None:
        raise PermanentError(f"Import batch {batch_id} not found")
    if b["status"] != expected_status:
        raise PermanentError(f"Batch {batch_id} is {b['status']}, expected {expected_status} (it may have been cancelled)")
    return b


def _with_file(storage: Storage, b: dict, fn):
    suffix = Path(b["original_filename"]).suffix.lower()
    path = storage.download(b["storage_key"], suffix)
    try:
        return fn(path)
    finally:
        path.unlink(missing_ok=True)


def detect_headers(conn: psycopg.Connection, storage: Storage, batch_id: int) -> dict:
    b = _batch(conn, batch_id, "HEADERS_PENDING")
    info = _with_file(storage, b, describe_file)
    if not info["sheets"] or all(not s["headers"] for s in info["sheets"]):
        raise PermanentError("The file has no readable rows")
    with conn.cursor() as cur:
        cur.execute("UPDATE import_batches SET sheet_info = %s, status = 'HEADERS_READY', updated_at = now() WHERE id = %s",
                    (Jsonb(info), batch_id))
    conn.commit()
    return {"batchId": batch_id, "sheets": len(info["sheets"])}


def load_vehicle_index(conn: psycopg.Connection) -> VehicleIndex:
    with conn.cursor() as cur:
        cur.execute("""SELECT id, registration_normalized, fuel_type, gps_km_tracked FROM vehicles
                       WHERE status NOT IN ('SOLD', 'SCRAPPED')""")
        by_reg: dict[str, int] = {}
        ambiguous: set[str] = set()
        fuel: dict[int, str] = {}
        tracked: set[int] = set()
        for r in cur.fetchall():
            fuel[r["id"]] = r["fuel_type"]
            if r["gps_km_tracked"]:
                tracked.add(r["id"])
            if r["registration_normalized"] in by_reg:
                ambiguous.add(r["registration_normalized"])
            by_reg[r["registration_normalized"]] = r["id"]
        cur.execute("SELECT alias_normalized, vehicle_id FROM vehicle_aliases")
        aliases = {r["alias_normalized"]: r["vehicle_id"] for r in cur.fetchall()}
    return VehicleIndex(by_reg=by_reg, fuel_type=fuel, by_alias=aliases, ambiguous=ambiguous, gps_tracked=tracked)


def load_settings(conn: psycopg.Connection) -> dict:
    with conn.cursor() as cur:
        cur.execute("SELECT setting_key, setting_value FROM app_settings")
        return {r["setting_key"]: r["setting_value"] for r in cur.fetchall()}


def parse_import(conn: psycopg.Connection, storage: Storage, batch_id: int) -> dict:
    b = _batch(conn, batch_id, "PARSE_PENDING")
    mapping = b["column_mapping"] or {}
    options = b["options"] or {}
    sheet = b["selected_sheet"]
    rows = _with_file(storage, b, lambda p: read_rows(p, None if sheet == "CSV" else sheet))
    index = load_vehicle_index(conn)
    settings = load_settings(conn)
    today = date.today()
    parser = {"GPS_KM": parse_gps, "FUEL": parse_fuel, "VEHICLE_MASTER": parse_master}[b["import_type"]]
    result = parser(rows, b["header_row"] or 1, mapping, options, index, settings, today)
    save_staging(conn, batch_id, result)
    conn.commit()
    return {"batchId": batch_id, **result.counts()}


def save_staging(conn: psycopg.Connection, batch_id: int, result: ParseResult) -> None:
    """Writes staging rows and parse errors, then marks the batch PARSED - all in the caller's transaction."""
    with conn.cursor() as cur:
        cur.execute("DELETE FROM import_staging_rows WHERE batch_id = %s", (batch_id,))
        cur.execute("DELETE FROM import_row_errors WHERE batch_id = %s", (batch_id,))
        staging, errors = [], []
        for r in result.rows:
            staging.append((batch_id, r.row_number, Jsonb(r.raw), Jsonb(r.parsed) if r.parsed is not None else None,
                            r.vehicle_id, r.record_date, r.status, r.group_key, Jsonb(r.messages)))
            for e in r.errors():
                errors.append((batch_id, r.row_number, e["field"], e["code"], e["message"][:500], e.get("rawValue")))
        for i in range(0, len(staging), BATCH):
            cur.executemany("""
                INSERT INTO import_staging_rows (batch_id, row_number, raw, parsed, vehicle_id, record_date, status, group_key, messages)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""", staging[i:i + BATCH])
        for i in range(0, len(errors), BATCH):
            cur.executemany("""
                INSERT INTO import_row_errors (batch_id, row_number, field_name, error_code, message, raw_value, stage)
                VALUES (%s, %s, %s, %s, %s, %s, 'PARSE')""", errors[i:i + BATCH])
        c = result.counts()
        cur.execute("""
            UPDATE import_batches SET status = 'PARSED', total_rows = %s, valid_rows = %s, warning_rows = %s, rejected_rows = %s,
                   duplicate_rows = %s, excluded_rows = %s, error_message = NULL, updated_at = now()
            WHERE id = %s""", (c["total"], c["VALID"], c["WARNING"], c["REJECTED"], c["DUPLICATE"], c["EXCLUDED"], batch_id))


# ---------------------------------------------------------------------------------------------- analytics job

def reconciliation_report(conn: psycopg.Connection, storage: Storage, payload: dict, job_id: int) -> dict:
    start = date.fromisoformat(payload["from"])
    end = date.fromisoformat(payload["to"])
    with conn.cursor() as cur:
        cur.execute("SELECT id, registration_number, status FROM vehicles WHERE status IN ('ACTIVE', 'IDLE', 'WORKSHOP')")
        vehicles = pd.DataFrame(cur.fetchall(), columns=["id", "registration_number", "status"])
        cur.execute("SELECT vehicle_id, km_date, distance_km FROM gps_daily_km WHERE is_current AND km_date BETWEEN %s AND %s", (start, end))
        gps = pd.DataFrame(cur.fetchall(), columns=["vehicle_id", "km_date", "distance_km"])
        cur.execute("""SELECT id, vehicle_id, txn_date, energy_type, odometer_km IS NOT NULL AS has_odometer
                       FROM fuel_transactions WHERE NOT is_void AND txn_date BETWEEN %s AND %s""", (start, end))
        fuel = pd.DataFrame(cur.fetchall(), columns=["id", "vehicle_id", "txn_date", "energy_type", "has_odometer"])
    sheets = reconcile(vehicles, gps, fuel, start, end)
    tmp = Path(tempfile.mkdtemp()) / f"reconciliation_{start:%d-%m-%Y}_to_{end:%d-%m-%Y}.xlsx"
    try:
        write_workbook(sheets, tmp, f"GPS vs fuel reconciliation {start:%d-%m-%Y} to {end:%d-%m-%Y}")
        data = tmp.read_bytes()
        key = f"report/{datetime.now():%Y/%m}/{uuid.uuid4()}-{tmp.name}"
        ctype = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        storage.upload(key, tmp, ctype)
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO stored_files (storage_key, original_filename, content_type, size_bytes, sha256, purpose)
                VALUES (%s, %s, %s, %s, %s, 'REPORT') RETURNING id""",
                        (key, tmp.name, ctype, len(data), hashlib.sha256(data).hexdigest()))
            file_id = cur.fetchone()["id"]
        conn.commit()
    finally:
        tmp.unlink(missing_ok=True)
    return {"fileId": file_id, "vehicles": len(sheets["Summary"]), "kmWithoutFuelWeeks": len(sheets["Km without fuel (weeks)"]),
            "fuelWithoutKm": len(sheets["Fuel without km"])}
