"""Worker entry point: python -m fleet_worker.main"""
from __future__ import annotations

import logging
import signal
import sys
import time

import psycopg

from .config import Config
from .jobs import connect, recover_stale, run_one
from .storage import Storage

_stop = False


def _handle(sig, frame):  # noqa: ARG001
    global _stop
    _stop = True


def main() -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stdout,
                        format="%(asctime)s level=%(levelname)s logger=%(name)s msg=%(message)s")
    log = logging.getLogger("fleet_worker")
    cfg = Config.from_env()
    storage = Storage(cfg)
    signal.signal(signal.SIGTERM, _handle)
    signal.signal(signal.SIGINT, _handle)
    log.info("worker %s started (storage=%s)", cfg.worker_id, cfg.storage_type)
    conn = None
    last_recover = 0.0
    while not _stop:
        try:
            if conn is None or conn.closed:
                conn = connect(cfg)
            if time.time() - last_recover > 60:
                n = recover_stale(conn, cfg.stale_minutes)
                if n:
                    log.warning("re-queued %s stale job(s)", n)
                last_recover = time.time()
            if not run_one(conn, storage, cfg.worker_id):
                time.sleep(cfg.poll_seconds)
        except psycopg.Error as e:
            # Database down, or tables not created yet (the API runs the migrations on its first start).
            log.error("database not ready: %s; retrying in 10s", str(e).strip().splitlines()[0] if str(e) else type(e).__name__)
            try:
                if conn is not None:
                    conn.close()
            except psycopg.Error:
                pass
            conn = None
            time.sleep(10)
    log.info("worker stopped")
    if conn is not None:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
