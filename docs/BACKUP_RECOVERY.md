# Backup & Recovery

## What must be protected
| Data | Where | Backup |
|---|---|---|
| All records, audit log, import history | PostgreSQL `fleet` schema | Daily automated + weekly logical dump |
| Uploaded imports, receipts, invoices, documents | Private bucket `fleet-files` (or `STORAGE_LOCAL_DIR`) | Weekly copy to a second location |
| Secrets (DB password, JWT secret, S3 keys) | Render / Vercel dashboards, password manager | Keep in the company password manager |

## Supabase
* **Daily backups** are included on paid plans (Database → Backups). Turn on **Point-in-Time Recovery** for production so you can restore to any minute.
* **Weekly logical dump** (kept off-platform, e.g. in company Google Drive or S3):
```bash
pg_dump "postgresql://postgres.<ref>:<password>@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require" --schema=fleet --format=custom --file=fleet_$(date +%Y%m%d).dump
```
* **Files**: copy the bucket with any S3 client (for example `rclone sync supabase:fleet-files backup:fleet-files`). Database rows refer to files by `stored_files.storage_key`, so restore the files under the same keys.

## Docker / self-hosted
```bash
docker compose exec db pg_dump -U fleet -d fleet --schema=fleet -Fc > fleet_$(date +%Y%m%d).dump
```
```bash
docker run --rm -v lr-fleet-manager_files:/data -v "$PWD":/backup alpine tar czf /backup/files_$(date +%Y%m%d).tgz -C /data .
```
Schedule both nightly (cron or Windows Task Scheduler) and keep at least 30 daily and 12 monthly copies.

## Restore
1. Stop the API and worker, so nothing writes during the restore.
2. Restore the database into an **empty** database (or a new Supabase project):
```bash
pg_restore --no-owner --dbname "<target-url>" fleet_YYYYMMDD.dump
```
3. Restore the files to the bucket or volume under the same keys.
4. Start the API. Flyway checks the schema history and applies only newer migrations. Then start the worker.
5. Check: sign in, open the dashboard, open a vehicle, download one stored file and run **Alerts → Run checks now**.

Practise a restore into a test project every quarter and record how long it took.

## Correcting data without restoring
Restoring a backup to fix one wrong upload loses everything entered since. Correct the data instead:
* **GPS km**: re-import the corrected file with policy **REPLACE** (manager, with a reason), or enter a correction on the GPS page. Old figures stay in the history.
* **Fuel / service records**: **Void** with a reason, then re-enter. Voided rows stay in the database for audit.
* **Odometer**: mark a reading untrusted (or trusted) on the vehicle's Odometer tab. Mileage and service status update.

Every correction is in `audit_logs` (who, when, before, after, IP).
