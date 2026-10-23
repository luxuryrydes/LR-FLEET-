# REST API

Base URL: `https://<api-host>` (local: `http://localhost:8080`). All endpoints are under `/api` and return JSON unless they
are downloads. Authenticate with `POST /api/auth/login`, then send `Authorization: Bearer <token>`. Tokens last 8 hours
(`JWT_TTL_MINUTES`). Dates are ISO `YYYY-MM-DD` in the API and `DD-MM-YYYY` in exports. Money and quantities are JSON
numbers produced from `BigDecimal`.

**Roles:** `READ` = any signed-in role. `WRITE` = OPERATOR, MANAGER or ADMIN. `MANAGE` = MANAGER or ADMIN. `ADMIN` = ADMIN only.
The server checks the role on every call (`@PreAuthorize`).

**Errors:** `{ "code": "VALIDATION_FAILED", "message": "…", "fieldErrors": [{ "field": "make", "message": "must not be blank" }] }`.
Status codes: 400 validation, 401 not signed in, 403 role, 404 not found, 409 conflict/duplicate, 413 file too large,
429 rate limited (`Retry-After: 60`), 500 with a support reference.

**Paging:** list endpoints take `page` (0-based) and `size` (max 200), and return `{ items, total, page, size }`.
`sort=field` or `sort=field,desc` uses a whitelisted set of fields.

**Common filters** (dashboard, lists, reports): `from`, `to` (default: last 30 days; 10-year maximum), `vehicleId`,
`vehicle` (registration text), `fuelType`, `category`, `ownership`, `vendorId`, `driverId`, `departmentId`, `status`.

## Auth & users
| Method | Path | Role | Notes |
|---|---|---|---|
| POST | /api/auth/login | public | `{email, password}` → `{token, expiresAt, user}`. Locks for 15 min after 5 failures. |
| GET | /api/auth/me | READ | Current user and roles |
| POST | /api/auth/change-password | READ | `{currentPassword, newPassword}` |
| GET/POST | /api/users | ADMIN | List / create `{email, fullName, password, roles[]}` |
| PUT | /api/users/{id} | ADMIN | `{fullName?, active?, roles?}`. You cannot deactivate yourself or remove your own ADMIN role. |
| POST | /api/users/{id}/reset-password | ADMIN | `{password}` |

## Vehicles & masters
| Method | Path | Role | Notes |
|---|---|---|---|
| GET | /api/vehicles | READ | filters + `q` search, paging, `sort=registrationNumber,make,model,status,fuelType,department,createdAt` |
| GET | /api/vehicles/export?format=xlsx\|csv | READ | Same filters as the list |
| GET | /api/vehicles/{id} | READ | Vehicle + `serviceStatus` + 90-day `efficiency` summary |
| POST / PUT | /api/vehicles, /api/vehicles/{id} | WRITE | Registration is normalised; duplicates → 409 unless `duplicateRegistrationReason` is given. Changes to driver, department, vendor, ownership, status or customer are written to the history. Only a manager can set INACTIVE/SOLD/SCRAPPED. |
| POST | /api/vehicles/{id}/deactivate | MANAGE | `{reason}` |
| GET | /api/vehicles/{id}/assignments | READ | Effective-dated history |
| GET/POST/DELETE | /api/vehicles/{id}/aliases[/{aliasId}] | READ/WRITE/MANAGE | GPS device names |
| GET/POST | /api/vehicles/{id}/odometer | READ/WRITE | Manual reading `{date, km}` → `{ok, problem, message}` |
| PUT | /api/vehicles/{id}/odometer/{readingId}/trust | MANAGE | `{trusted, reason}` |
| GET | /api/vehicles/{id}/efficiency-intervals | READ | Every refuelling interval with status and exclusion reason |
| POST | /api/vehicles/{id}/recalculate | WRITE | Recomputes the vehicle's intervals |
| GET/POST/PUT | /api/departments, /api/vendors, /api/drivers | READ/WRITE/MANAGE | `includeInactive=true` to list deactivated records |
| GET | /api/lookups | READ | Dropdown values |

## GPS km, fuel, maintenance, documents
| Method | Path | Role | Notes |
|---|---|---|---|
| GET | /api/gps | READ | filters, `source`, `includeHistory` |
| GET | /api/gps/export | READ | |
| POST | /api/gps | WRITE | Manual `{vehicleId, date, km, notes?, correctionReason?}`. Replacing an existing day needs MANAGE and a reason. |
| GET | /api/fuel | READ | filters, `energyType`, `flagged`, `includeVoid` |
| GET | /api/fuel/export | READ | |
| POST | /api/fuel | WRITE | See `FuelInput`. Returns `{id, status, warnings[]}`. An identical transaction → 409. |
| POST | /api/fuel/preview-amount | WRITE | Server-side preview of the expense that would be saved. Writes nothing. |
| POST | /api/fuel/{id}/void | MANAGE | `{reason}`. Kept for audit and excluded from calculations. |
| GET/POST | /api/maintenance | READ/WRITE | Returns `{id, warnings, nextServiceOdometer, nextServiceDate}` |
| POST | /api/maintenance/{id}/void | MANAGE | |
| GET | /api/service-status[/export] | READ | `due=OVERDUE,DUE_SOON,…` |
| GET | /api/documents | READ | `vehicleId`, `driverId`, `docType` |
| GET | /api/documents/compliance | READ | `{expired, expiring, missing, pendingVerification, driverLicences, reminderDays}` |
| GET | /api/documents/matrix | READ | Documents dashboard: `q`, `registrationClass=COMMERCIAL\|PRIVATE\|UNKNOWN`, `docType`, `state=ATTENTION\|EXPIRED\|DUE_SOON\|MISSING\|NO_FILE` → `{types, summary, rows[{vehicle…, documents:{TYPE:{status, expiryDate, fileId…}}}]}` |
| GET | /api/documents/matrix/export?format=xlsx\|csv | READ | Same as Excel |
| POST | /api/documents/{id}/file | WRITE | `{fileId}`: attach or replace the scan (upload first with `/api/files`, purpose VEHICLE_DOCUMENT) |
| POST/PUT | /api/documents[/{id}] | WRITE | |
| POST | /api/documents/{id}/verify | MANAGE | `{status: VERIFIED\|REJECTED, remarks?}` |
| POST | /api/files (multipart `file`, `purpose`) | WRITE | Attachments: FUEL_RECEIPT, SERVICE_INVOICE, VEHICLE_DOCUMENT, OTHER |
| GET | /api/files/{id} | READ | Authorised download; storage is never public |

## Imports & jobs
| Method | Path | Role | Notes |
|---|---|---|---|
| POST | /api/imports (multipart `file`, `type=GPS_KM\|FUEL\|VEHICLE_MASTER\|DOCUMENTS`) | WRITE | Creates a batch and queues header detection |
| GET | /api/imports, /api/imports/{id} | READ | Batch includes `sheetInfo` and `sameFileImports` |
| POST | /api/imports/{id}/mapping | WRITE | `{sheet, headerRow, mapping:{field: header}, options:{recordMode}}` |
| GET | /api/imports/{id}/rows | READ | Staging rows, `status=VALID\|WARNING\|DUPLICATE\|REJECTED` |
| GET | /api/imports/{id}/review | READ | GPS dry run: new, identical and conflicting vehicle-days |
| POST | /api/imports/{id}/commit | WRITE | `{policy: SKIP\|REPLACE, reason?}`. REPLACE needs MANAGE and a reason. |
| POST | /api/imports/{id}/retry, /cancel | WRITE | |
| GET | /api/imports/{id}/errors?format=xlsx\|csv | READ | Row-level error report |
| GET | /api/jobs/{id} | READ | Background job status/result |
| GET | /api/worker/status | READ | `{online, lastSeen, queuedJobs, oldestQueuedSeconds, wakeConfigured}`. A pending import batch includes the same as `worker`. |

## Dashboard, reports, alerts, admin
| Method | Path | Role | Notes |
|---|---|---|---|
| GET | /api/dashboard/kpis, /api/dashboard/charts | READ | Filters |
| GET | /api/monthly-mileage?month=YYYY-MM | READ | Monthly mileage calculator: per-vehicle Rs/km and km/kg + fleet totals (filters apply). Mixed-fuel rows have `combinedQuantity` (kg + L) and `mileageUnit` `km/kgL`; `totals.mixedFuel` = `{km, quantity, mileage, unit, vehicles}` |
| GET | /api/monthly-mileage/trend?month=YYYY-MM&months=12 | READ | Fleet Rs/km, km, km/kg and mixed km/kgL per month |
| GET | /api/monthly-mileage/export?month=YYYY-MM&format=xlsx\|csv | READ | Same rows as Excel/CSV |
| GET | /api/reports | READ | Catalog of the 16 reports |
| GET | /api/reports/{key} | READ | Preview: columns, notes, first 500 rows, total |
| GET | /api/reports/{key}/export?format=xlsx\|csv | READ | Full report; the Excel file has a Notes sheet with filters and definitions |
| POST | /api/reports/reconciliation-workbook | READ | Queues the Python coverage workbook → `{jobId}` |
| GET | /api/alerts, /api/alerts/summary, /api/alerts/{id} | READ | `status=ACTIVE` = open, acknowledged or in progress |
| POST | /api/alerts/{id}/acknowledge, /notes | WRITE | `{note}` |
| POST | /api/alerts/{id}/assign, /status | MANAGE | Resolving or dismissing requires a note |
| POST | /api/alerts/run | MANAGE | Runs all rules now (also runs nightly at 01:30 IST) |
| GET | /api/settings | READ | |
| PUT | /api/settings/{key} | ADMIN | `{value}`, validated by type |
| GET | /api/audit-logs | MANAGE | `entityType`, `entityId` |
| GET | /actuator/health | public | Liveness / readiness |

Report keys: `daily-gps-km`, `monthly-utilisation`, `vehicle-mileage-history`, `fuel-consumption-by-type`, `fuel-expenditure-by-vehicle`,
`fleet-mileage-comparison`, `low-efficiency-vehicles`, `fuel-cost-per-km`, `maintenance-history`, `maintenance-cost-by-vehicle-month`,
`service-due`, `document-expiry`, `missing-data-reconciliation`, `duplicate-rejected-imports`, `department-utilisation-expenditure`,
`vehicle-expense-history`.
