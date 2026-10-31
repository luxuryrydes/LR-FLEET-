"""Backup and restore for a PC-hosted LR Fleet Manager (the embedded PostgreSQL has no pg_dump).

    python -m fleet_worker.backup create  --out "D:\\OneDrive\\LR Fleet Backups" [--keep 14] [--files ..\\data\\files]
    python -m fleet_worker.backup restore --file lr-fleet-backup-2026-10-31_2330.zip --yes [--files ..\\data\\files]

A backup is one zip: every table of the `fleet` schema as CSV, read in one consistent snapshot, plus a manifest (schema
version, row counts) and the uploaded files (scans, imported sheets). Restore replaces all data in the running database
with the backup's, after checking it was made by the same schema version, then resets the id sequences.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

import psycopg

SCHEMA = "fleet"
NAME = re.compile(r"^lr-fleet-backup-\d{4}-\d{2}-\d{2}_\d{4}\.zip$")
SKIP_TABLES = {"flyway_schema_history"}


def _url() -> str:
    return os.environ.get("WORKER_DATABASE_URL") or os.environ.get("DATABASE_URL") or "postgresql://postgres:postgres@localhost:54329/postgres"


def _tables(cur) -> list[str]:
    cur.execute("""SELECT table_name FROM information_schema.tables WHERE table_schema = %s AND table_type = 'BASE TABLE'
                   ORDER BY table_name""", (SCHEMA,))
    return [r[0] for r in cur.fetchall() if r[0] not in SKIP_TABLES]


def _schema_version(cur) -> str | None:
    cur.execute(f"SELECT to_regclass('{SCHEMA}.flyway_schema_history') IS NOT NULL")
    if not cur.fetchone()[0]:
        return None
    cur.execute(f"SELECT max(version::int)::text FROM {SCHEMA}.flyway_schema_history WHERE success")
    return cur.fetchone()[0]


def create(out_dir: Path, keep: int, files_dir: Path | None) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y-%m-%d_%H%M")
    target = out_dir / f"lr-fleet-backup-{stamp}.zip"
    counts: dict[str, int] = {}
    fd, tmp_name = tempfile.mkstemp(suffix=".zip", dir=out_dir)
    os.close(fd)
    tmp = Path(tmp_name)
    try:
        with psycopg.connect(_url()) as conn, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
            conn.execute("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY")   # one consistent snapshot for every table
            with conn.cursor() as cur:
                version = _schema_version(cur)
                for t in _tables(cur):
                    buf = io.BytesIO()
                    with cur.copy(f'COPY {SCHEMA}."{t}" TO STDOUT WITH (FORMAT csv, HEADER true)') as copy:
                        for chunk in copy:
                            buf.write(chunk)
                    data = buf.getvalue()
                    z.writestr(f"tables/{t}.csv", data)
                    counts[t] = max(0, data.count(b"\n") - 1)
            conn.rollback()
            nfiles = 0
            if files_dir and files_dir.is_dir():
                for p in files_dir.rglob("*"):
                    if p.is_file():
                        z.write(p, f"files/{p.relative_to(files_dir).as_posix()}")
                        nfiles += 1
            z.writestr("manifest.json", json.dumps({"app": "LR Fleet Manager", "created": datetime.now().isoformat(timespec="seconds"),
                                                    "schemaVersion": version, "rows": counts, "files": nfiles}, indent=2))
        tmp.replace(target)
    finally:
        tmp.unlink(missing_ok=True)
    old = sorted((p for p in out_dir.iterdir() if NAME.match(p.name)), key=lambda p: p.name, reverse=True)
    for p in old[keep:]:
        p.unlink()
    return target


def restore(file: Path, files_dir: Path | None) -> dict:
    with zipfile.ZipFile(file) as z:
        manifest = json.loads(z.read("manifest.json"))
        with psycopg.connect(_url()) as conn:
            with conn.cursor() as cur:
                current = _schema_version(cur)
                if str(current) != str(manifest["schemaVersion"]):
                    raise SystemExit(f"This backup is from schema version {manifest['schemaVersion']} but the app is at {current}. "
                                     "Restore it with the same app version it was made with.")
                tables = _tables(cur)
                cur.execute("SET session_replication_role = replica")          # load in any order; keys are checked as a set
                cur.execute("TRUNCATE " + ", ".join(f'{SCHEMA}."{t}"' for t in tables) + " RESTART IDENTITY CASCADE")
                for t in tables:
                    name = f"tables/{t}.csv"
                    if name not in z.namelist():
                        continue
                    data = z.read(name)
                    header = data.split(b"\n", 1)[0].decode("utf-8").strip()
                    if not header:
                        continue
                    cols = ", ".join(f'"{c}"' for c in header.split(","))
                    with cur.copy(f'COPY {SCHEMA}."{t}" ({cols}) FROM STDIN WITH (FORMAT csv, HEADER true)') as copy:
                        copy.write(data)
                cur.execute("SET session_replication_role = DEFAULT")
                cur.execute("""SELECT c.table_name, c.column_name FROM information_schema.columns c
                               WHERE c.table_schema = %s AND c.column_default LIKE 'nextval%%'""", (SCHEMA,))
                for t, col in cur.fetchall():
                    cur.execute(f"""SELECT setval(pg_get_serial_sequence('{SCHEMA}."{t}"', '{col}'),
                                    coalesce(max("{col}"), 1), max("{col}") IS NOT NULL) FROM {SCHEMA}."{t}" """)
            conn.commit()
        restored_files = 0
        if files_dir is not None:
            for n in z.namelist():
                if n.startswith("files/") and not n.endswith("/"):
                    rel = Path(n[len("files/"):])
                    if ".." in rel.parts or rel.is_absolute():
                        continue
                    dest = files_dir / rel
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(z.read(n))
                    restored_files += 1
    return {"rows": manifest["rows"], "files": restored_files, "created": manifest["created"]}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="fleet_worker.backup")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create")
    c.add_argument("--out", required=True)
    c.add_argument("--keep", type=int, default=14)
    c.add_argument("--files")
    r = sub.add_parser("restore")
    r.add_argument("--file", required=True)
    r.add_argument("--files")
    r.add_argument("--yes", action="store_true", help="confirm: all current data is replaced by the backup")
    a = ap.parse_args(argv)
    if a.cmd == "create":
        p = create(Path(a.out), a.keep, Path(a.files) if a.files else None)
        print(f"Backup saved: {p} ({p.stat().st_size // 1024} KB)")
        return 0
    if not a.yes:
        print("Restore replaces ALL current data with the backup. Run again with --yes to confirm.")
        return 2
    res = restore(Path(a.file), Path(a.files) if a.files else None)
    print(f"Restored backup from {res['created']}: {sum(res['rows'].values())} rows, {res['files']} files. Restart the app now.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
