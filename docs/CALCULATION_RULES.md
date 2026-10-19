# Calculation Rules (single source of truth)

These rules are implemented **once**, in the Java backend package `com.lrtaxi.fleet.calc`.
The Python worker only parses, normalises and pre-validates spreadsheets; the React frontend
only shows previews. Every saved figure (mileage, expense, cost/km, service status) comes from
the Java engine. Hand-calculated examples live in [`spec/calculation-cases.json`](../spec/calculation-cases.json)
and are asserted by `SharedSpecCasesTest`.

## 1. Units

| Energy | Quantity unit | Efficiency unit |
|---|---|---|
| CNG | kg | km/kg |
| Petrol | litre | km/L |
| Diesel | litre | km/L |
| Electricity | kWh | km/kWh |

Different energy types are **never** added together in one denominator.

Vehicle fuel types and the energy they may buy:

| Vehicle fuel type | Allowed energy | Multi-energy? |
|---|---|---|
| PETROL / HYBRID | Petrol | no |
| DIESEL | Diesel | no |
| CNG | CNG | no |
| CNG_PETROL (bi-fuel) | CNG, Petrol | yes |
| ELECTRIC | Electricity | no |
| PLUGIN_HYBRID | Petrol, Electricity | yes |

A fuel transaction whose energy is not allowed for the vehicle is rejected.

## 2. Precision and rounding

* All money, quantities and distances use `BigDecimal` (Java) / `NUMERIC` (PostgreSQL). `double`/`float` are never used for totals.
* Rounding is `HALF_UP`.
* Money: 2 decimals. Mileage: 3 decimals stored, 2 shown. Cost per km: 2 decimals. Unit price: 4 decimals. Percentages: 2 decimals.
* Indian formatting: `₹12,34,567.89`; dates `DD-MM-YYYY`.

## 3. Registration numbers

1. **Normalise**: uppercase, remove every character that is not `A–Z` or `0–9`. `"dl 1c ab 1234"` → `DL1CAB1234`.
2. **Match an imported vehicle name** in this order:
   1. exact normalised registration;
   2. a saved alias (GPS device name) in `vehicle_aliases`;
   3. a plate extracted from the normalised text with the first match of
      `(?<!\d)(?:\d{2}BH\d{4}[A-Z]{1,2}|[A-Z]{2}\d{1,2}[A-Z]{0,3}\d{4}(?!\d))` (Bharat series or state series).
      `"DL1RT1234 (CNG)"` → `DL1RT1234`.
   4. Otherwise the row is rejected as *unmatched vehicle*. Imports never create vehicles.
3. Duplicate registrations are blocked by a unique index unless a documented reason is recorded on the vehicle.

## 4. GPS daily kilometres

* One **current** figure per vehicle per day (unique index). Older figures are kept as superseded with who/when/why.
* Valid distance: `0 ≤ km ≤ gps.max_daily_km` (default 1,500). Zero is a real measurement (vehicle idle). A missing day is **missing**, never zero.
* **Daily-total files** (`DAILY_TOTAL`): rows for the same vehicle and day with the same distance are copies; one row is used and the rest are reported as duplicates. Different distances for the same vehicle and day are a **conflict**: none of those rows are imported, so they can't be summed or guessed.
* **Trip files** (`TRIP`): a trip is identified by vehicle + trip start timestamp + distance. Distinct trips are summed per day, and repeated copies of the same trip are dropped. Trip mode requires a start timestamp.
* **Existing records**: if the same km is already saved for that vehicle and day, the row is skipped. If a different km is saved, it is an *existing conflict*. With policy `SKIP` (the default) the existing value stays. With `REPLACE` (MANAGER/ADMIN, reason required) the old row is superseded and an audit entry is written.
* The same file uploaded again therefore cannot double count.

## 5. Fuel transaction amounts

The invoice amount is authoritative and is never overwritten.

```
calculated  = quantity × unit_price                      (if unit price given)
expense     = invoice_amount                              (source INVOICE)
            | gross_amount − discount + tax               (source GROSS_LESS_DISCOUNT)
            | calculated − discount + tax                 (source CALCULATED)
reference   = (gross_amount or calculated) − discount + tax
mismatch    = |expense − reference| > fuel.amount_tolerance  (default ₹1.00)
```

Mismatches are flagged (`amount_mismatch`, `amount_difference`) and the transaction is still saved
with the invoice amount. If unit price is missing it is derived as `gross ÷ quantity` and marked as derived.

Duplicate protection:
* `dedupe_key` = SHA-256 of vehicle + date + energy + quantity + expense + invoice no. Re-uploading a file is skipped row by row.
* `transaction_ref` (bank or fuel-card transaction ID) must be unique.
* If an invoice number is already used by a *different* transaction, the row is rejected for review.

Quantity checks: more than the vehicle's tank or battery capacity, or more than `fuel.max_quantity.<ENERGY>`, is flagged `quantity_flag` and raises an alert.

## 6. Mileage (fuel efficiency)

**Basic formula:** `mileage = eligible km ÷ compatible quantity`. Examples: 250 km ÷ 10 kg = 25 km/kg; 250 km ÷ 20 L = 12.5 km/L;
400 km ÷ 40 L = 10 km/L; 200 km ÷ 25 kWh = 8 km/kWh. These are illustrations only, not fleet benchmarks.

Fuel purchases are never matched to arbitrary GPS km. Mileage is calculated over **intervals between refuelling events**
of the same energy type for the same vehicle.

**Fuel accounting policy** (`fuel.accounting_policy`):
* **Full tank to full tank**: used when any fill of that energy type is flagged full-tank, or always under `FULL_TANK_ONLY`.
  For consecutive full-tank fills F₁ → F₂: distance = odo(F₂) − odo(F₁), quantity = every fill **after** F₁ up to **and including** F₂.
  Fills before the first full tank or after the last one are left unaccounted.
* **Successive refuel**: used under `AUTO` when there are no full-tank flags. Each fill is assumed to replace the fuel used since the
  previous fill. Interval k: distance = odo(k) − odo(k−1), quantity = quantity(k).

**Distance source:**
1. `ODOMETER` when both ends of the interval have an odometer reading.
2. Otherwise `GPS`: the sum of current GPS daily km for days strictly after the start fill date up to and including the end fill date.
   Every day in that range must have a GPS figure, otherwise the interval is excluded as `INCOMPLETE_GPS`. Same-day fills without
   odometers are excluded as `SAME_DAY_NO_ODOMETER`.

**Exclusions** (kept in `fuel_efficiency_intervals` with a reason, never silently counted):
`DECREASING_ODOMETER`, `ZERO_DISTANCE`, `ZERO_QUANTITY`, `INCOMPLETE_GPS`, `SAME_DAY_NO_ODOMETER`, `MIXED_FUEL`, `IMPLAUSIBLE_MILEAGE`
(outside `mileage.min/max.<ENERGY>`).

**Bi-fuel / plug-in hybrid** (`fuel.bifuel_attribution`): kilometres can't be split between fuels from odometer data alone.
With `NO_OTHER_FUEL_IN_INTERVAL` (default), an interval counts only if no other energy was bought between its start and end fill dates.
Otherwise it is `MIXED_FUEL`. With `NONE`, every bi-fuel interval is `MIXED_FUEL`. A vehicle whose intervals are all mixed shows
status **MIXED_FUEL / insufficient data**.

**Period assignment:** an interval belongs to the reporting period that contains its **end** fill date.

**Recalculation:** intervals for a vehicle are recomputed whenever its fuel, GPS or odometer data changes (manual entry, import commit, void).

## 7. Fleet averages

```
fleet mileage (per energy) = Σ eligible interval km ÷ Σ eligible interval quantity
weighted unit price        = Σ expense ÷ Σ quantity
```
A simple average of vehicle mileages is **never** used. Example: 500 km/20 kg and 100 km/10 kg gives 20 km/kg, not 17.5.

## 8. Cost per kilometre

All cost metrics are on a **purchase basis** for the selected period, and state their components and distance basis:

* **Fuel cost/km** = fuel expense in the period ÷ GPS km in the period, across vehicles that have **both** km > 0 and fuel expense > 0.
  Vehicles with only one of the two are reported as coverage gaps, not counted as zero.
* **Maintenance cost/km** = maintenance spend in the period ÷ GPS km in the period, across vehicles with km > 0.
* **Fuel + maintenance cost/km** = (fuel + maintenance) ÷ km over the fuel cost/km vehicle set.
  It is labelled *Fuel + maintenance*, never *total operating cost*, because driver, insurance, tax and depreciation are not included.

## 9. Mileage variance

```
variance % = (actual − expected) ÷ expected × 100
```
Expected mileage is resolved as: the vehicle's `expected_mileage` (primary energy) → model benchmark (make + model + energy) →
fuel-type benchmark. An alert is raised when variance ≤ −`mileage.low_threshold_pct` (default 15%), with the contributing
intervals and data-quality status attached. A low figure is a prompt to investigate, not proof of wrongdoing.

## 10. Service due

* Interval: vehicle `service_interval_km` → category → `service.default_interval_km` (10,000). Months: same order, default 6.
* Only categories with `resets_service_interval = true` (routine service, oil change, EV periodic service) reset the interval.
* `next service odometer = last valid service odometer + interval`, unless the user entered an explicit override (flagged).
* **Odometer baseline** = the latest trusted odometer reading (service, fuel, manual, vehicle master). It is unreliable if it is
  lower than an earlier trusted reading.
* `current estimated odometer = baseline km + Σ current GPS km for days strictly after the baseline date`.
  A new service reading becomes the baseline, so earlier km are never counted twice.
* `remaining km = next service odometer − current estimated odometer`.

| Status | Rule |
|---|---|
| MISSING_SERVICE_RECORD | no interval-resetting service on record |
| OVERDUE | remaining km < 0, **or** today > next service date |
| DUE_SOON | 0 ≤ remaining km ≤ `service.due_soon_km` (500), or next date within `service.due_soon_days` |
| MISSING_BASELINE / UNRELIABLE_BASELINE | no trusted odometer, or the baseline went backwards (date overdue still wins) |
| OK | otherwise |

## 11. Odometer progression

A new reading is **untrusted** (still saved with the transaction, flagged, and alerted) if it is:
* lower than the previous trusted reading;
* higher than a later trusted reading;
* a jump greater than `(days between + 1) × gps.max_daily_km`.

Untrusted readings are ignored for baselines and odometer-based mileage.

## 12. Data that is missing is not zero

Dashboards exclude invalid or incomplete measurements. Counts of *vehicles with missing GPS data*, *missing fuel records* and
*missing service records* are shown separately so that gaps are visible.

## 13. Vehicle master, driver type and GPS km

Implemented in `MasterRules.java` (authoritative) and mirrored in `worker/fleet_worker/master.py` for the import preview.
Both are tested against [`spec/master-cases.json`](../spec/master-cases.json).

| "Vendor / Driver" value | Status | Driver type | Ownership | GPS km imported |
|---|---|---|---|---|
| WORKSHOP …, ACCIDENTAL | WORKSHOP | None | Owned | No |
| SOLD / SCRAPPED | SOLD / SCRAPPED | None | Owned | No |
| DRIVER + name | ACTIVE | Own driver | Owned | **Yes** |
| DRIVER, no name | IDLE | Own driver | Owned | No |
| VENDOR | ACTIVE (IDLE if no name) | Vendor | Vendor attached | No |
| GOVT DRIVER | ACTIVE (IDLE if no name) | Govt driver | Owned | No |
| OFFICER, PERSONAL | ACTIVE | Officer / Personal | Owned | No |
| FREE LR, FREE JN, anything else | IDLE | None | Owned | No |
| (blank) + name | ACTIVE | Own driver | Owned | Yes |

* **GPS km are imported only for vehicles with `gps_km_tracked = true`.** These are vehicles driven by LR's own
  drivers. GPS rows for other vehicles are shown as *Not our driver* in the import preview and are never saved. The flag
  can be changed per vehicle (Edit vehicle → "Import GPS km for this vehicle").
* Make, model and category come from the "Vehicle Type" text (e.g. `TATA NEXON EV XM` → Tata, Nexon EV XM, SUV).
  Fuel `CNG / PETROL` → CNG_PETROL (bi-fuel) and `HYBRID PETROL` → HYBRID.
* Drivers are matched by name + phone, vendors by name, and departments by name. Missing ones are created.
* Re-importing the sheet never duplicates vehicles. *Add new only* leaves existing vehicles unchanged. *Add new and update
  existing* (manager, with a reason) updates them and records driver, department, status and driver-type changes in the history.
* **Sold / disposed** (vehicle page → *Mark as sold / disposed*): type (sold, scrapped, returned to vendor, transferred, other),
  date, dealer/buyer (required for a sale), amount and remarks. The vehicle becomes SOLD, SCRAPPED or INACTIVE, its GPS km
  stop being imported, and later master imports never revive it. A manager can undo a disposal recorded by mistake.
