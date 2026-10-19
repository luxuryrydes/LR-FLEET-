"""Worker <-> PostgreSQL contract test. Runs only when WORKER_TEST_DATABASE_URL points at an empty test database
(CI provides one). Applies the backend's Flyway SQL migrations, then runs the real jobs end to end."""
import os
from pathlib import Path

import pytest

from fleet_worker.config import Config

URL = os.environ.get("WORKER_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not URL, reason="WORKER_TEST_DATABASE_URL not set")
MIGRATIONS = Path(__file__).resolve().parents[2] / "backend" / "src" / "main" / "resources" / "db" / "migration"


@pytest.fixture
def env(tmp_path):
    import psycopg
    from fleet_worker.jobs import connect
    from fleet_worker.storage import Storage

    with psycopg.connect(URL, autocommit=True) as c:
        c.execute("DROP SCHEMA IF EXISTS fleet CASCADE")
        c.execute("CREATE SCHEMA fleet")
        c.execute("SET search_path TO fleet")
        for f in sorted(MIGRATIONS.glob("V*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
            c.execute(f.read_text(encoding="utf-8"))
    cfg = Config(URL, "local", str(tmp_path / "files"), "", "", "", "", "", 0.1, "test", 15)
    conn = connect(cfg)
    yield conn, Storage(cfg), tmp_path
    conn.close()


def test_detect_and_parse_gps_import(env):
    from openpyxl import Workbook
    from fleet_worker.jobs import run_one

    conn, storage, tmp = env
    wb = Workbook()
    ws = wb.active
    ws.title = "Distance"
    ws.append(["Distance report"])
    ws.append(["Device Name", "Start Time", "Distance (Kms)"])
    ws.append(["DL1RT1234 (CNG)", "01-10-2026 08:00:00", 120.5])
    ws.append(["DL1RT1234 (CNG)", "01-10-2026 08:00:00", 120.5])
    ws.append(["UNKNOWN 1", "01-10-2026", 10])
    key = "import/2026/10/test-gps.xlsx"
    dest = tmp / "files" / key
    dest.parent.mkdir(parents=True)
    wb.save(dest)

    with conn.cursor() as cur:
        cur.execute("""INSERT INTO vehicles (registration_number, registration_normalized, make, model, fuel_type, category_code, ownership_type)
                       VALUES ('DL1RT1234', 'DL1RT1234', 'Maruti', 'Dzire', 'CNG', 'SEDAN', 'OWNED') RETURNING id""")
        vid = cur.fetchone()["id"]
        cur.execute("""INSERT INTO stored_files (storage_key, original_filename, content_type, size_bytes, sha256, purpose)
                       VALUES (%s, 'test-gps.xlsx', 'x', 1, repeat('0', 64), 'IMPORT') RETURNING id""", (key,))
        fid = cur.fetchone()["id"]
        cur.execute("""INSERT INTO import_batches (import_type, file_id, original_filename, file_sha256, status)
                       VALUES ('GPS_KM', %s, 'test-gps.xlsx', repeat('0', 64), 'HEADERS_PENDING') RETURNING id""", (fid,))
        bid = cur.fetchone()["id"]
        cur.execute("INSERT INTO background_jobs (job_type, payload) VALUES ('DETECT_HEADERS', %s)", (f'{{"batchId": {bid}}}',))
    conn.commit()

    assert run_one(conn, storage, "test") is True
    with conn.cursor() as cur:
        cur.execute("SELECT status, sheet_info FROM import_batches WHERE id = %s", (bid,))
        b = cur.fetchone()
    assert b["status"] == "HEADERS_READY"
    sheet = b["sheet_info"]["sheets"][0]
    assert sheet["headerRow"] == 2
    mapping = sheet["suggestedMapping"]["GPS_KM"]

    with conn.cursor() as cur:
        cur.execute("""UPDATE import_batches SET status = 'PARSE_PENDING', selected_sheet = 'Distance', header_row = 2,
                       column_mapping = %s, options = '{"recordMode": "DAILY_TOTAL"}' WHERE id = %s""",
                    (__import__("json").dumps(mapping), bid))
        cur.execute("INSERT INTO background_jobs (job_type, payload) VALUES ('PARSE_IMPORT', %s)", (f'{{"batchId": {bid}}}',))
    conn.commit()
    assert run_one(conn, storage, "test") is True

    with conn.cursor() as cur:
        cur.execute("SELECT status, total_rows, valid_rows, rejected_rows, duplicate_rows FROM import_batches WHERE id = %s", (bid,))
        b = cur.fetchone()
        cur.execute("SELECT row_number, status, vehicle_id, parsed FROM import_staging_rows WHERE batch_id = %s ORDER BY row_number", (bid,))
        rows = cur.fetchall()
        cur.execute("SELECT row_number, error_code FROM import_row_errors WHERE batch_id = %s", (bid,))
        errors = cur.fetchall()
        cur.execute("SELECT status FROM background_jobs ORDER BY id")
        jobs = [r["status"] for r in cur.fetchall()]
    assert b == {"status": "PARSED", "total_rows": 3, "valid_rows": 1, "rejected_rows": 1, "duplicate_rows": 1}
    assert [r["status"] for r in rows] == ["VALID", "DUPLICATE", "REJECTED"]
    assert rows[0]["vehicle_id"] == vid and rows[0]["parsed"]["km"] == "120.5"
    assert errors == [{"row_number": 5, "error_code": "UNMATCHED_VEHICLE"}]
    assert jobs == ["SUCCEEDED", "SUCCEEDED"]


def test_unreadable_file_fails_batch_without_retry(env):
    from fleet_worker.jobs import run_one

    conn, storage, tmp = env
    key = "import/bad.xlsx"
    (tmp / "files" / "import").mkdir(parents=True)
    (tmp / "files" / key).write_bytes(b"PK\x03\x04 not really a workbook")
    with conn.cursor() as cur:
        cur.execute("""INSERT INTO stored_files (storage_key, original_filename, content_type, size_bytes, sha256, purpose)
                       VALUES (%s, 'bad.xlsx', 'x', 1, repeat('0', 64), 'IMPORT') RETURNING id""", (key,))
        fid = cur.fetchone()["id"]
        cur.execute("""INSERT INTO import_batches (import_type, file_id, original_filename, file_sha256, status)
                       VALUES ('FUEL', %s, 'bad.xlsx', repeat('0', 64), 'HEADERS_PENDING') RETURNING id""", (fid,))
        bid = cur.fetchone()["id"]
        cur.execute("INSERT INTO background_jobs (job_type, payload) VALUES ('DETECT_HEADERS', %s)", (f'{{"batchId": {bid}}}',))
    conn.commit()
    run_one(conn, storage, "test")
    with conn.cursor() as cur:
        cur.execute("SELECT status, error_message FROM import_batches WHERE id = %s", (bid,))
        b = cur.fetchone()
        cur.execute("SELECT status, attempts FROM background_jobs")
        j = cur.fetchone()
    assert b["status"] == "FAILED" and "Cannot open Excel file" in b["error_message"]
    assert j == {"status": "FAILED", "attempts": 1}
