# Excel / CSV Import Specification

Supported files: `.xlsx`, `.xls` (legacy, read with `xlrd`) and `.csv` (UTF-8 with or without BOM, or Windows-1252;
comma, semicolon, tab or pipe delimited). Maximum 50 MB (`MAX_UPLOAD_MB`). The file's content must match its extension.

Sample files are in [`samples/`](../samples): `gps_daily_km_sample.xlsx`, `fuel_format1_sample.csv`, `fuel_format2_sample.xlsx`.

## Workflow

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

## Error report columns

`Row` (spreadsheet row number), `Field`, `Error code`, `Reason`, `Value`, `Stage` (`PARSE` = worker, `COMMIT` = Java).

Common codes: `UNMATCHED_VEHICLE`, `MISSING_VEHICLE`, `BLANK_DATE`, `INVALID_DATE`, `FUTURE_DATE`, `MISSING_KM`, `NEGATIVE_KM`,
`KM_ABOVE_LIMIT`, `INVALID_NUMBER`, `DAILY_TOTAL_CONFLICT`, `INVALID_FUEL_TYPE`, `INCOMPATIBLE_FUEL`, `INVALID_QUANTITY`,
`NO_AMOUNT`, `DUPLICATE_TRANSACTION_REF`, `REPEATED_INVOICE`.
