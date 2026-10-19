-- V3: vehicle master import, operator type (own driver / vendor / ...), GPS tracking flag and disposal (sold) details.

ALTER TABLE vehicles
    ADD COLUMN operator_type      VARCHAR(16) NOT NULL DEFAULT 'OWN_DRIVER'
        CHECK (operator_type IN ('OWN_DRIVER', 'VENDOR', 'GOVT_DRIVER', 'OFFICER', 'PERSONAL', 'NONE')),
    ADD COLUMN assignment_label   VARCHAR(60),
    -- Only vehicles driven by LR's own drivers have their GPS km imported.
    ADD COLUMN gps_km_tracked     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN registration_class VARCHAR(12) CHECK (registration_class IN ('PRIVATE', 'COMMERCIAL')),
    ADD COLUMN disposal_type      VARCHAR(20) CHECK (disposal_type IN ('SOLD', 'SCRAPPED', 'RETURNED_TO_VENDOR', 'TRANSFERRED', 'OTHER')),
    ADD COLUMN disposal_date      DATE,
    ADD COLUMN disposal_party     VARCHAR(200),
    ADD COLUMN disposal_amount    NUMERIC(14, 2) CHECK (disposal_amount >= 0),
    ADD COLUMN disposal_remarks   VARCHAR(1000);

CREATE INDEX ix_vehicles_operator ON vehicles (operator_type);

ALTER TABLE import_batches DROP CONSTRAINT import_batches_import_type_check;
ALTER TABLE import_batches ALTER COLUMN import_type TYPE VARCHAR(20);
ALTER TABLE import_batches ADD CONSTRAINT import_batches_import_type_check CHECK (import_type IN ('GPS_KM', 'FUEL', 'VEHICLE_MASTER'));
ALTER TABLE import_batches ADD COLUMN excluded_rows INT NOT NULL DEFAULT 0;

-- EXCLUDED = valid row intentionally not imported (e.g. GPS km of a vendor vehicle).
ALTER TABLE import_staging_rows DROP CONSTRAINT import_staging_rows_status_check;
ALTER TABLE import_staging_rows ADD CONSTRAINT import_staging_rows_status_check
    CHECK (status IN ('VALID', 'WARNING', 'REJECTED', 'DUPLICATE', 'EXCLUDED'));

ALTER TABLE vehicle_assignments DROP CONSTRAINT vehicle_assignments_assignment_type_check;
ALTER TABLE vehicle_assignments ADD CONSTRAINT vehicle_assignments_assignment_type_check
    CHECK (assignment_type IN ('DRIVER', 'DEPARTMENT', 'VENDOR', 'OWNERSHIP', 'STATUS', 'CUSTOMER', 'OPERATOR'));

CREATE UNIQUE INDEX ux_departments_name ON departments (lower(name));
CREATE INDEX ix_drivers_phone ON drivers (phone) WHERE phone IS NOT NULL;
