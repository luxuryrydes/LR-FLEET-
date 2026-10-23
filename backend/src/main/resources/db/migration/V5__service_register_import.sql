-- V5: vehicle service register import (last service odometer / date / next service per vehicle).
-- The register sometimes has the last service odometer without its date ("DATE NOT RECORDED"): such imported records
-- keep the km-based reminder (next service odometer) and have no date-based one.

ALTER TABLE import_batches DROP CONSTRAINT import_batches_import_type_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_import_type_check
    CHECK (import_type IN ('GPS_KM', 'FUEL', 'VEHICLE_MASTER', 'DOCUMENTS', 'SERVICE'));

ALTER TABLE maintenance_records
    ADD COLUMN source          VARCHAR(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'IMPORT')),
    ADD COLUMN import_batch_id BIGINT REFERENCES import_batches (id);
ALTER TABLE maintenance_records ALTER COLUMN service_date DROP NOT NULL;
ALTER TABLE maintenance_records ADD CONSTRAINT maintenance_date_or_odometer
    CHECK (service_date IS NOT NULL OR (source = 'IMPORT' AND odometer_km IS NOT NULL));
