# Excel / CSV Import Specification

Supported files: `.xlsx`, `.xls` (legacy, read with `xlrd`) and `.csv` (UTF-8 with or without BOM, or Windows-1252;
comma, semicolon, tab or pipe delimited). Maximum 50 MB (`MAX_UPLOAD_MB`). The file's content must match its extension.

Sample files are in [`samples/`](../samples): `gps_daily_km_sample.xlsx`, `fuel_format1_sample.csv`, `fuel_format2_sample.xlsx`.

## Workflow

Excel files are read by the separate Python **import worker**. If no worker is running, the file waits in the queue at
"Reading the file…". The import screen then says that the worker is not running; nothing is lost, and the file continues as
soon as a worker starts. The worker reports in every 15 seconds (`worker_heartbeats`); `GET /api/worker/status` shows it.

| Step | Who | What happens |
|---|---|---|
| 1. Upload | User → Java | The file is stored privately and a SHA-256 fingerprint is recorded. An `import_batches` row is created as `HEADERS_PENDING`. |
| 2. Detect | Python worker | Lists worksheets, finds the header row (titles above the table are skipped), reads sample rows and suggests a column mapping. Status becomes `HEADERS_READY`. |
| 3. Map | User | Chooses the worksheet, header row and mapping. GPS files also need the **record type** (daily total or individual trips). |
| 4. Parse | Python worker | Normalises and pre-validates every row into `import_staging_rows`, writes reasons to `import_row_errors`, and marks in-file duplicates. Status becomes `PARSED`. |
| 5. Preview / review | User | Sees counts and every row with its reasons. For GPS files, a dry run against saved data shows new, identical and conflicting vehicle-days. |
| 6. Commit | Java (single DB transaction) | Re-validates every row, applies the duplicate policy, saves records, recomputes mileage intervals and writes the audit log. If anything fails unexpectedly, the whole commit is rolled back and the batch is marked `FAILED` with a reason. |
| 7. Summary | User | Inserted / replaced / skipped / rejected counts. The error report can be downloaded as Excel or CSV. |

A worker failure on a bad file is final: the batch is marked `FAILED` with a readable message. Temporary failures
(database or storage unavailable) are retried up to 3 times with back-off. **Retry** on a failed batch rebuilds the
staging rows from scratch, so it is always safe.

## GPS daily kilometres

| Target field | Required | Recognised headers (case/punctuation-insensitive) |
|---|---|---|
| vehicle | yes | Device Name, Vehicle No, Vehicle, Registration No, Reg No, Asset Name … |
| date | yes | Date, Start Time, Report Date, Trip Date … |
| km | yes | Distance (Kms), Distance KM, Distance, KMs, Total Distance … |
| tripStart | trip files | Start Time, Trip Start … (defaults to the date column) |

Rules:
* Vehicle matching follows [CALCULATION_RULES.md §3](CALCULATION_RULES.md). `DL1RT1234 (CNG)` matches `DL1RT1234`. Imports never create vehicles. Unmatched names are rejected; save the GPS name as an alias on the vehicle and re-import.
* Dates are day-first (`01/10/2026` = 1 October). Excel dates, Excel serial numbers, `DD-MM-YYYY`, `DD/MM/YYYY`, `YYYY-MM-DD`, `01-Oct-2026` and timestamps are accepted. Blank, invalid, future or pre-2000 dates are rejected.
* Km must be a number from 0 to `gps.max_daily_km` (default 1,500). Blank km is rejected, never treated as zero.
* **Daily totals**: identical repeated rows are counted once. Rows that conflict for the same vehicle and day are all rejected.
* **Trips**: distinct trips (start time + distance) are summed per day. Repeated trip rows are dropped.
* **Existing data**: the same km already saved is skipped. A different km already saved is kept (`SKIP`) or superseded (`REPLACE`). REPLACE is for MANAGER/ADMIN only, needs a reason, and keeps the old row with who, when and why.

## Fuel and energy transactions

| Target field | Required | Recognised headers |
|---|---|---|
| vehicle | yes | Vehicle No, Vehicle, Registration No |
| date | yes | Date, Request Date, Transaction Date, Bill Date |
| time | no | Time, Transaction Time (or the time part of the date) |
| energyType | yes* | Fuel Type, Fuel, Product (Petrol/MS, Diesel/HSD, CNG, EV/Electric) |
| quantity | yes | Quantity, Qty, Litres, Kg, kWh |
| unitPrice | one amount | Rate, Price, Unit Price |
| grossAmount | one amount | Value, Gross Amount |
| discountAmount | no | Discount, Cashback |
| taxAmount | no | Tax, GST |
| invoiceAmount | one amount | Cost, Amount, Total Amount, Invoice Amount, Net Amount |
| odometerKm | no | Odometer, OD Meter, KM Reading |
| isFullTank | no | Full Tank (Y/N, Yes/No, Full/Partial) |
| stationName | no | Station, Pump, Outlet, Supplier, Dealer |
| invoiceNumber | no | Invoice No, Bill No, Receipt No |
| transactionRef | no | Transaction No, Transaction ID, Ref No, UTR |
| paymentMethod | no | Payment Mode (Cash, Card, UPI, Fuel Card, Credit, NEFT…) |
| notes | no | Notes, Remarks |

\* If the fuel type is blank and the vehicle has only one fuel, that fuel is assumed and a warning is shown.

Both layouts in the brief are recognised automatically:
* `Date, Vehicle, Fuel Type, Quantity, Rate, Cost, Odometer, Invoice No.` (Cost = invoice amount)
* `Vehicle No, Request Date, Fuel Type, Quantity, Value, Discount, Price, OD Meter, Invoice No, Transaction No` (Value = gross, Price = rate)

Rules: see [CALCULATION_RULES.md §5](CALCULATION_RULES.md). The fuel must be valid for the vehicle and the quantity must be
greater than zero. A totals mismatch is a warning and the invoice amount is kept. Repeated rows inside the file are saved once.
A reused transaction number is rejected, and an invoice number used on a different transaction is rejected at commit.
Re-uploading the same file adds nothing.

## Vehicle master (vehicles, drivers, vendors, departments)

Upload the company's master sheet from **Vehicles → Upload vehicle master sheet**, or from **Imports → Import vehicle master**.
The `NEW MASTER` layout is recognised automatically:

| Column in the sheet | Target field | Notes |
|---|---|---|
| Register No. | registration (required) | normalised, e.g. `DL 2C BA 7055` → `DL2CBA7055`; repeated registrations in one file are rejected |
| Vehicle Type | model (required) | make, model and category are derived (Ciaz → Maruti Suzuki, Sedan) |
| Fuel Type | fuelType (required) | CNG / PETROL, ELECTRIC, DIESEL, PETROL, HYBRID PETROL, CNG |
| Vehicle Model | modelYear | year, e.g. 2021 |
| Vendor / Driver | operatorLabel | DRIVER, VENDOR, GOVT DRIVER, OFFICER, PERSONAL, FREE LR/JN, WORKSHOP CH/MU, ACCIDENTAL, SOLD |
| Vendor / Driver Name | driverName | blank means no driver, so the vehicle is **Idle** |
| Vendor / Driver Contact | driverPhone | non-numbers such as "N/A" or "OFFICER" are ignored with a warning |
| Department | department | created if new (e.g. LOOSE, MHA, MEA) |
| Private/Commercial | registrationClass | |

Optional columns: Make, Category, Chassis No, Engine No, GPS Device Name, Sold Date, Sold To / Dealer, Remarks.

The status, driver type and GPS-km rules are in [CALCULATION_RULES.md §13](CALCULATION_RULES.md). The preview shows, for every
row, whether the vehicle is new or an update, what its status will be, and whether its GPS km will be imported.

### Updating vehicles from a new master sheet

The confirm step compares the sheet with the saved vehicles before anything is saved (`GET /api/imports/{id}/master-review`).
It shows the new vehicles, the vehicles that will be updated with the fields that change (driver, department, driver type,
status…), the vehicles already up to date, and the vehicles in the app that are missing from the sheet (these are not changed).
For managers the default is **Add new vehicles and update changed ones**, and no reason needs typing. With *Add new vehicles
only*, changed vehicles are kept as they were and the summary shows how many changes were not applied
(`existingConflictRows`), so changes are never skipped silently. Sold vehicles keep their sold status, and deleted vehicles
are not brought back.

**Column mapping is remembered.** When a sheet has headers the app does not recognise, it reuses the columns chosen in the last
successful import of the same type, wherever this file has a column with exactly that header. The mapping screen says which
fields were matched this way.

## Vehicle documents register (permit / pollution / insurance sheet)

Import type `DOCUMENTS`: one row per vehicle with an end-date column per document. Built for the company's
"Permit- pollution- Insurance all details" workbook. The vehicles must already be in the vehicle master.

| Field | Header examples | Document |
|---|---|---|
| registration (required) | Vehicle No, Vehical No | — |
| insuranceExpiry | Insurance End Date | INSURANCE |
| ownDamageExpiry | OWN DAMAGE | INSURANCE_OD |
| pucExpiry | Pullution End Date, Pollution End Date, PUC | PUC |
| fitnessExpiry | Fitness End Date | FITNESS |
| roadTaxExpiry | HR Road Tax End Date, HR TAX END DATE | ROAD_TAX |
| permitExpiry | Permit 5 Years End Date | PERMIT |
| permit1yExpiry | Permit 1 Year End Date | PERMIT_1Y |
| registrationDate | Registration Date | REGISTRATION (issue date) |
| registrationClass | Commercial or Private (else taken from the tab name) | sets the vehicle's commercial / private class if blank |

At least one end-date column must be mapped.

* **Every tab** (option `allSheets`, on by default): besides the chosen tab, every tab with a vehicle column and an end-date
  column is read. That includes Commercial cars, Private cars, and the monthly FITNESS / INSURANCE / PUC / PERMIT to-do lists.
  Month titles and repeated header rows inside a tab are skipped.
* **Latest date wins:** for each vehicle and document, the latest end date across all tabs is used. Rows whose dates are all older
  are shown as *Older dates (not used)* with the tab and row that won.
* **Values:** dates in any Indian format. `NA`, `Not Required`, `Nil` mean not required, and `LTT` means lifetime tax. Other text
  (e.g. "New Number") is a warning and is ignored.
* **Commit:** a later end date than the saved one is added as a renewal. The same date is skipped. An earlier date is skipped,
  unless a manager chooses *Also correct saved dates* and gives a reason. Vehicles not in the master are rejected with a reason.

## Vehicle service register

Import type `SERVICE`: one row per vehicle with the last service. Built for the company's "VEHICLE SERVICE RECORD &
MAINTENANCE TRACKER" sheet. The summary block above the table is skipped automatically, and vehicles must be in the vehicle master.

| Field | Header examples |
|---|---|
| registration (required) | Registration No., Vehicle No |
| serviceDate | Service Date, Last Service Date |
| odometerKm | Last Service Odometer (KM), Odometer |
| nextServiceOdometer | Next Service, Next Service KM |
| nextServiceDate | Next Service Date |
| recordStatus | Service Record (RECORDED / DATE NOT RECORDED / MISCELLANEOUS) |
| category, description, workshopName, totalCost, invoiceNumber, remarks | Service Type, Work Done, Workshop, Amount, Invoice No, Remarks |

Map the service date or the odometer column (at least one). Rows are checked like this:
* A future service date is rejected.
* An odometer outside 0–2,000,000 is ignored with a warning.
* A next-service km that isn't above the last-service odometer is ignored.
* "Date not recorded" is a warning: the km reminder only.
* MISCELLANEOUS or empty rows show as *No service on record* and are not imported.

At commit, a service already saved (same vehicle, category, date and odometer) is skipped, so the register can be imported again
after each update. The service type comes from the text (oil, tyre, brake, battery, accident, body/paint, filter, EV, repair);
otherwise it is a routine service.

## Fuel-company statements used by LR Taxi

| Statement | How to import | What happens automatically |
|---|---|---|
| **IGL smart-card file** (`Txn Date, Outlet Name, Card Number, Vehicle No., Txn Type, Txn Id, Amount, Quantity`) | Import fuel file, then set **Fuel for rows without a fuel type = CNG** (the file has no fuel column) | Txn Id becomes the transaction no., Outlet the station, Amount the invoice amount; the card number is saved in notes and payment = Fuel card |
| **BPCL slip export / "viewAndAlterReport"** (`Vehicle No, Fuel Type, Slip No, Transaction Date, Quantity, Price, Value, Discount, Invoice No., Request Date, OD Meter`) | Import fuel file (mapping is automatic) | "AUTO CNG - BPCL" / "PETROL - BPCL" / "DIESEL - BPCL" recognised; the fill date is the **Transaction Date** (not Request Date); expense = Value − Discount (the ₹1/L petrol and diesel discount); the **Total** line is skipped; files with a damaged formatting section (BPCL portal exports) are still read |

**The same fill in two statements is saved once.** If a fill has already been imported, for example from the IGL card
file, the same fill arriving in another layout (e.g. `NEw Fuel.xlsx`, which repeats the IGL fills in BPCL format) is
skipped as *already recorded*. A fill counts as the same when the vehicle, date, fuel, quantity and amount match, and the
time matches whenever both statements give one. Importing both files therefore never doubles the fuel or the expense.

**Two charges are kept.** Two transactions with different transaction numbers or times are two charges, even when the
quantity and amount are identical. If they are less than 10 minutes apart, both are saved and flagged as a
*possible double charge* (row warning plus a HIGH alert) so you can check with the fuel company.

Rows are rejected with a reason when the vehicle is unknown (e.g. "7614") or the fuel doesn't fit the vehicle
(e.g. CNG on a vehicle registered as Electric). These rows usually mean the card is linked to the wrong vehicle.

## Error report columns

`Row` (spreadsheet row number), `Field`, `Error code`, `Reason`, `Value`, `Stage` (`PARSE` = worker, `COMMIT` = Java).

Common codes: `UNMATCHED_VEHICLE`, `MISSING_VEHICLE`, `BLANK_DATE`, `INVALID_DATE`, `FUTURE_DATE`, `MISSING_KM`, `NEGATIVE_KM`,
`KM_ABOVE_LIMIT`, `INVALID_NUMBER`, `DAILY_TOTAL_CONFLICT`, `INVALID_FUEL_TYPE`, `INCOMPATIBLE_FUEL`, `INVALID_QUANTITY`,
`NO_AMOUNT`, `DUPLICATE_TRANSACTION_REF`, `REPEATED_INVOICE`.
