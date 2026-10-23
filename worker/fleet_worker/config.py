"""Environment configuration (same variable names as the Java backend where shared)."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    database_url: str
    storage_type: str
    storage_local_dir: str
    s3_bucket: str
    s3_endpoint: str
    s3_region: str
    s3_access_key: str
    s3_secret_key: str
    poll_seconds: float
    worker_id: str
    stale_minutes: int
    health_port: int = 0

    @staticmethod
    def from_env() -> "Config":
        url = os.environ.get("WORKER_DATABASE_URL") or os.environ.get("DATABASE_URL")
        if not url:
            raise SystemExit("Set WORKER_DATABASE_URL (postgresql://user:pass@host:5432/db?sslmode=require)")
        return Config(
            database_url=url,
            storage_type=os.environ.get("STORAGE_TYPE", "local").lower(),
            storage_local_dir=os.environ.get("STORAGE_LOCAL_DIR", "./data/files"),
            s3_bucket=os.environ.get("S3_BUCKET", ""),
            s3_endpoint=os.environ.get("S3_ENDPOINT", ""),
            s3_region=os.environ.get("S3_REGION", "ap-south-1"),
            s3_access_key=os.environ.get("S3_ACCESS_KEY", ""),
            s3_secret_key=os.environ.get("S3_SECRET_KEY", ""),
            poll_seconds=float(os.environ.get("WORKER_POLL_SECONDS", "2")),
            worker_id=os.environ.get("WORKER_ID", f"worker-{os.getpid()}"),
            stale_minutes=int(os.environ.get("WORKER_STALE_MINUTES", "15")),
            health_port=int(os.environ.get("WORKER_HEALTH_PORT", "0") or 0),
        )
