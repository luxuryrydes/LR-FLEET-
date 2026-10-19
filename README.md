# LR Fleet Manager

**Fleet mileage, fuel & maintenance management for LR Taxi Services Private Limited**

Tracks vehicle master records, daily GPS kilometres, CNG/petrol/diesel/EV consumption, weighted mileage, operating cost per km,
service due dates, compliance documents, alerts and 16 management reports. Built for hundreds to thousands of vehicles and
millions of historical rows.

| Layer | Technology | Responsibility |
|---|---|---|
| `backend/` | Java 21, Spring Boot 3.3, JDBC, Flyway, PostgreSQL | **The authoritative calculation engine** and REST API: business rules, mileage, fuel and cost calculations, odometer and service-due logic, import commit, alerts, reports, auth/roles, audit log |
| `worker/` | Python 3.12, pandas, openpyxl, xlrd, Pydantic, psycopg | Background jobs: reads Excel/CSV, detects headers, maps columns, normalises registrations, flags duplicates and invalid rows, writes staging rows and error rows, builds the GPS-vs-fuel coverage workbook. It never writes final mileage or money figures. |
| `frontend/` | React 18, TypeScript, Vite, TanStack Query, Recharts | Dashboard, lists with server-side paging, import wizard, entry forms, vehicle history, alerts, reports, users and settings |
| `spec/` | JSON | Shared, hand-calculated test cases used by the Java, Python **and** TypeScript tests, so the three can't drift apart |
| `docs/` | Markdown | [Calculation rules](docs/CALCULATION_RULES.md) · [Import spec](docs/IMPORT_SPEC.md) · [API](docs/API.md) · [Deployment](docs/DEPLOYMENT.md) · [Backup & recovery](docs/BACKUP_RECOVERY.md) |

## Go live (GitHub → Vercel + Render + Supabase)

Vercel hosts the React frontend; it can't run a Java server or a background worker. The Java API and Python worker run on
Render as Docker services, and Supabase provides PostgreSQL plus private file storage. Full steps are in
**[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**. In short:

1. **GitHub**: push this folder. CI (`.github/workflows/ci.yml`) runs every test suite.
2. **Supabase**: create a project in Mumbai, copy the *session pooler* connection string, and create a **private** bucket `fleet-files` with S3 keys.
3. **Render**: New → Blueprint → this repo (`render.yaml`). Enter the database URL, S3 keys, admin email/password and allowed origin.
4. **Vercel**: import the repo with the root directory unchanged (`vercel.json` builds `frontend/`) and set `VITE_API_BASE_URL` to the Render API URL.
5. Sign in with the bootstrap admin, create users, and start importing.

## Run locally
```bash
cp .env.example .env
docker compose up --build
```
```bash
cd frontend && npm install && npm run dev
```
Then open http://localhost:5173. The API runs on :8080, and the worker and PostgreSQL run in Docker. To try the sample files,
run `samples/demo_seed.sql` on the test database, then import `samples/gps_daily_km_sample.xlsx`,
`samples/fuel_format1_sample.csv` and `samples/fuel_format2_sample.xlsx`.

## Tests
| Suite | Command | Covers |
|---|---|---|
| Java unit | `mvn -f backend/pom.xml test` | Mileage formulas, weighted fleet mileage, fuel price, cost/km, ₹ rounding, mixed fuel, invalid/zero quantities, zero denominators, GPS duplicates, odometer progression, service due, baseline resets, shared spec cases |
| Java integration | `mvn -f backend/pom.xml verify` (needs Docker) | Real PostgreSQL via Testcontainers: role checks, repeat imports, REPLACE with audit history, conflicting daily totals, trip sums, fuel import repeat-safety, full rollback on failure, dashboard = report totals, service baseline reset, odometer alerts |
| Python | `cd worker && python -m pytest -q` | Excel/CSV parsing, header detection, date conversion, column mapping (both fuel layouts), duplicates, error generation, reconciliation; with `WORKER_TEST_DATABASE_URL`, the full worker ↔ PostgreSQL contract |
| Frontend | `cd frontend && npm test` | Filters, upload preview, mapping step, validation messages, permission-gated controls, shared registration rules, Indian formats |

## Security
* JWT bearer tokens (HS256), BCrypt (cost 12) password hashes, lockout after 5 failed logins, and rate limits on login and the API.
* Four roles (ADMIN, MANAGER, OPERATOR, VIEWER), enforced on the server for every endpoint. The UI only hides controls the user can't use.
* All data lives in the `fleet` schema, closed to Supabase's public roles. Files sit in private storage behind authorised download endpoints.
* No secrets in the repository or the frontend. Uploads are checked by type and content. CSV exports are protected against formula injection.
* `audit_logs` records every change (before/after, user, IP). Corrections void or supersede records and never delete them.

## Project layout
```
backend/   Spring Boot app (src/main/java/com/lrtaxi/fleet/...), Flyway migrations, unit + integration tests, Dockerfile
worker/    fleet_worker package, pytest suite, Dockerfile
frontend/  React app (src/pages, src/components, src/lib), vitest suite
spec/      shared calculation and registration test cases
samples/   example GPS and fuel import files + demo seed SQL
docs/      rules, import spec, API, deployment, backup
vercel.json  render.yaml  docker-compose.yml  .github/workflows/ci.yml  .env.example
```
