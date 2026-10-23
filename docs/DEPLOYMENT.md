# Deployment Guide (GitHub → Vercel + Render + Supabase)

```
 Browser ──► Vercel (React static site)
                │  HTTPS + Bearer token
                ▼
           Render web service: Java Spring Boot API ──► Supabase PostgreSQL (schema "fleet")
                                       │                      ▲
                                       ▼                      │
                          Supabase Storage (private S3 bucket)│
                                       ▲                      │
           Render background worker: Python import worker ────┘
```

**Why not everything on Vercel?** Vercel only hosts static sites and short-lived serverless functions. It can't run a
Java server, a background worker or long Excel imports. The frontend goes on Vercel; the Java API and Python worker run
as Docker services on Render (Railway, Fly.io, AWS or any Docker host work the same way).

## 1. Push to GitHub

> **Don't use GitHub's "Add files via upload" page.** It drops the folders (`frontend/`, `backend/`, `worker/`,
> `.github/`) and only the top-level files arrive. Vercel then fails with *"npm ci can only install with an existing
> package-lock.json"*, and Render guesses the wrong language (Go). Also, don't upload the zip itself. Use git or
> **GitHub Desktop**: clone the repository, copy the *contents* of `lr-fleet-manager` into it, then Commit → Push.
> Afterwards the repository root must show `backend/`, `frontend/`, `worker/`, `vercel.json` and `render.yaml`.

```bash
git init
git add .
git commit -m "LR Fleet Manager"
git branch -M main
git remote add origin https://github.com/<you>/lr-fleet-manager.git
git push -u origin main
```
GitHub Actions (`.github/workflows/ci.yml`) then runs the Java unit and PostgreSQL integration tests, the Python tests
(including the worker–database contract test) and the frontend tests and build. Wait for a green tick before deploying.

## 2. Supabase (database + private file storage)
1. Create a project in region **Mumbai (ap-south-1)**.
2. **Database password**: Project Settings → Database. Save it in your password manager.
3. **Connection string**: Connect → *Session pooler* (port **5432**). The transaction pooler (6543) is not suitable for Flyway or the `search_path` setting.
   * Java: `DB_URL=jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require`, `DB_USERNAME=postgres.<project-ref>`, `DB_PASSWORD=…`
   * Worker: `WORKER_DATABASE_URL=postgresql://postgres.<project-ref>:<password>@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require`
4. **Storage**: Storage → New bucket `fleet-files`, **Public OFF**. Storage → Settings → S3 Connection → create access keys.
   Copy the endpoint (`https://<project-ref>.supabase.co/storage/v1/s3`), access key and secret.
5. You don't need to create tables. Flyway creates the `fleet` schema on the API's first start. Migration V2 revokes the
   `anon`/`authenticated` roles from that schema, so the Supabase Data API can't read fleet data.

## 3. Render (API + worker)
> If you already created a **Web Service** by hand and the log says `Using Go version` / `go.mod file not found`, delete
> that service. It was created with the wrong runtime. Use the Blueprint below, which creates both services as **Docker**
> from `backend/Dockerfile` and `worker/Dockerfile`. To create the API manually instead, choose *New → Web Service*,
> set **Language: Docker**, **Root Directory: `backend`** and **Health check path: `/actuator/health`**.

1. Render → **New + → Blueprint** → choose the repository. It reads `render.yaml`.
2. Fill in the prompted values: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `WORKER_DATABASE_URL`, `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`,
   `ADMIN_EMAIL`, `ADMIN_PASSWORD` (at least 10 characters with letters and digits), and `ALLOWED_ORIGINS` (your Vercel URL; you can fill it after step 4).
   `JWT_SECRET` is generated automatically.
3. Deploy. Check `https://lr-fleet-api.onrender.com/actuator/health` → `{"status":"UP"}`. The worker logs show `worker … started`.
4. Use the **Starter** plan (or higher) for both. Free web services sleep, and free plans don't offer background workers.

**Free test setup (`render-free.yaml`).** Render has no free background worker, so this blueprint runs the import worker as a
second free **web service**, `lr-fleet-worker-free`. It answers a health check on `WORKER_HEALTH_PORT`.
1. Render → New + → Blueprint → choose `render-free.yaml`, and fill in the same database and S3 values for both services.
2. After the first deploy, copy the worker's URL and set it on the API as `WORKER_WAKE_URL=https://<worker>.onrender.com/health`.
   Free services sleep after 15 minutes without traffic. The API calls this URL whenever a file is queued, so the first file after
   a quiet spell waits about a minute while the worker wakes up.

**"Reading the file…" never finishes:** no import worker is running. The import screen says so, and `GET /api/worker/status` shows
`"online": false`. Locally, keep the *LR Fleet - Import worker* window open (start `run-local.cmd` again if it was closed).
On Render, check that the worker service is deployed and its log shows `worker … started`. The queued file then continues by
itself.

## 4. Vercel (frontend)
1. Vercel → **Add New → Project** → import the repository. Keep the **root directory as the repository root**:
   `vercel.json` installs and builds `frontend/` and serves `frontend/dist`.
2. Environment variable: `VITE_API_BASE_URL = https://lr-fleet-api.onrender.com` (no trailing slash).
3. Deploy, then copy the production URL (e.g. `https://lr-fleet.vercel.app`) into Render's `ALLOWED_ORIGINS` and redeploy the API.
   Add custom domains to both places if you use them.
4. Sign in with `ADMIN_EMAIL` / `ADMIN_PASSWORD`, create real users under **Users & roles**, and change the admin password.
   Clearing `ADMIN_PASSWORD` in Render afterwards is fine; it is only used while the users table is empty.

## 5. First data
* Add departments, vendors and drivers under **Drivers & vendors**, then vehicles. Optionally run `samples/demo_seed.sql` on a
  *test* database to try the sample files.
* Import GPS km and fuel files with the wizard (see [IMPORT_SPEC.md](IMPORT_SPEC.md)).
* **Alerts → Run checks now** generates alerts immediately. The nightly run is at 01:30 IST (`ALERTS_CRON`).

## Local development
Requirements: Java 21, Maven 3.9, Node 20+, Python 3.12+, PostgreSQL 16 (or Docker).

```bash
cp .env.example .env
docker compose up --build
```
Or run each part on its own:
```bash
cd backend && mvn spring-boot:run
```
```bash
cd worker && python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m fleet_worker.main
```
```bash
cd frontend && npm install && npm run dev
```
The backend reads `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_EMAIL` and `ADMIN_PASSWORD` from the environment.
The worker reads `WORKER_DATABASE_URL`. With `STORAGE_TYPE=local`, both must point `STORAGE_LOCAL_DIR` at the same folder.

## Tests
```bash
mvn -f backend/pom.xml test
```
```bash
mvn -f backend/pom.xml verify
```
```bash
cd worker && python -m pytest -q
```
```bash
cd frontend && npm test
```
`test` runs the unit tests; `verify` adds the PostgreSQL integration tests and needs Docker. To run the worker's DB contract
test, set `WORKER_TEST_DATABASE_URL` to an **empty** test database (the test drops and recreates the `fleet` schema).

## Environment variables
See [`.env.example`](../.env.example) and [`frontend/.env.example`](../frontend/.env.example). Secrets (database password, JWT
secret, S3 keys, admin password) go only in the Render/Vercel dashboards or a local `.env`, never in Git and never in `VITE_*`
variables, which are shipped to the browser.

## Operations
* **Scaling**: the API is stateless, so you can run several instances. The in-memory rate limiter is per instance; add a gateway or Cloudflare rate limit if you scale out. Workers claim jobs with `FOR UPDATE SKIP LOCKED`, so several workers are safe. The nightly alert run takes a PostgreSQL advisory lock, so only one instance runs it.
* **Logs**: one structured line per request (`req=<id> user=<id> method path status durationMs`). The request id is returned in `X-Request-Id`, and 500 responses include a short reference to quote to support.
* **Stuck jobs**: jobs locked for more than 15 minutes (`WORKER_STALE_MINUTES`) are re-queued automatically. Use **Retry** on a failed import.
* **Migrations**: add a new `backend/src/main/resources/db/migration/V<n>__description.sql`. Never edit an applied migration, and never use `flyway clean` (`clean-disabled: true` is set).
* **Large fleets**: lists are server-paginated (max 200 rows per page), exports stream up to 0.5–1 M rows, and the hot tables have date and vehicle indexes. Past roughly 50 M GPS rows, consider partitioning `gps_daily_km` by year.
