"""Private file storage shared with the Java backend: local directory or S3-compatible bucket (e.g. Supabase Storage)."""
from __future__ import annotations

import shutil
import tempfile
from pathlib import Path

from .config import Config


class Storage:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self._s3 = None
        if cfg.storage_type == "s3":
            import boto3  # imported lazily: not needed for local storage
            from botocore.config import Config as BotoConfig
            self._s3 = boto3.client(
                "s3", endpoint_url=cfg.s3_endpoint or None, region_name=cfg.s3_region,
                aws_access_key_id=cfg.s3_access_key, aws_secret_access_key=cfg.s3_secret_key,
                config=BotoConfig(s3={"addressing_style": "path"}))
        self.root = Path(cfg.storage_local_dir).resolve()

    def _local(self, key: str) -> Path:
        p = (self.root / key).resolve()
        if self.root not in p.parents and p != self.root:
            raise ValueError("Invalid storage key")
        return p

    def download(self, key: str, suffix: str) -> Path:
        """Copies the stored object to a temporary file (caller deletes it)."""
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as f:
            target = Path(f.name)
        try:
            if self._s3 is not None:
                self._s3.download_file(self.cfg.s3_bucket, key, str(target))
            else:
                shutil.copyfile(self._local(key), target)
        except Exception:
            target.unlink(missing_ok=True)
            raise
        return target

    def upload(self, key: str, source: Path, content_type: str) -> None:
        if self._s3 is not None:
            self._s3.upload_file(str(source), self.cfg.s3_bucket, key, ExtraArgs={"ContentType": content_type})
        else:
            dest = self._local(key)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, dest)
