-- V4: vehicle documents register import (permit / pollution / insurance sheet), more document types, "not required"
-- documents, and a heartbeat so the app can tell when the Python import worker is not running.

ALTER TABLE vehicle_documents DROP CONSTRAINT vehicle_documents_doc_type_check;
ALTER TABLE vehicle_documents ADD CONSTRAINT vehicle_documents_doc_type_check CHECK (doc_type IN (
    'INSURANCE', 'INSURANCE_OD', 'FITNESS', 'PUC', 'PERMIT', 'PERMIT_1Y', 'REGISTRATION', 'ROAD_TAX', 'DRIVING_LICENCE', 'OTHER'));

-- NA / Not Required / LTT (lifetime road tax) in the register: the document is recorded as not applicable, with no expiry.
ALTER TABLE vehicle_documents
    ADD COLUMN not_applicable  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN source          VARCHAR(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'IMPORT')),
    ADD COLUMN import_batch_id BIGINT REFERENCES import_batches (id);

ALTER TABLE import_batches DROP CONSTRAINT import_batches_import_type_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_import_type_check
    CHECK (import_type IN ('GPS_KM', 'FUEL', 'VEHICLE_MASTER', 'DOCUMENTS'));

CREATE TABLE worker_heartbeats (
    worker_id  VARCHAR(100) PRIMARY KEY,
    hostname   VARCHAR(200),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen  TIMESTAMPTZ NOT NULL DEFAULT now()
);

UPDATE app_settings SET description = 'Documents every active vehicle must hold (commercial vehicles also need FITNESS, PERMIT and PERMIT_1Y)'
WHERE setting_key = 'docs.required_types';
UPDATE app_settings SET setting_value = 'INSURANCE,PUC'
WHERE setting_key = 'docs.required_types' AND setting_value = 'INSURANCE,FITNESS,PUC,PERMIT,REGISTRATION';
