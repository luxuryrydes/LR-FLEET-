-- V8: GPS km of vehicles whose km are not counted (vendor, idle, govt driver...) are kept aside instead of being dropped at
-- import. When the vehicle moves to an LR driver, the kept days from the change date are added automatically; when it
-- moves to a vendor, counted days from the change date are moved here.
CREATE TABLE gps_held_km (
    id                 BIGSERIAL PRIMARY KEY,
    vehicle_id         BIGINT        NOT NULL REFERENCES vehicles (id),
    km_date            DATE          NOT NULL,
    distance_km        NUMERIC(9, 2) NOT NULL CHECK (distance_km >= 0),
    record_mode        VARCHAR(12)   NOT NULL,
    trip_count         INT,
    source_device_name VARCHAR(150),
    import_batch_id    BIGINT REFERENCES import_batches (id),
    held_reason        VARCHAR(200)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (vehicle_id, km_date)
);
