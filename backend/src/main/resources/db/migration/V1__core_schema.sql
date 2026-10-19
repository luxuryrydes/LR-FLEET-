-- LR Taxi Services - Fleet Mileage, Fuel & Maintenance Management
-- V1: core schema. All objects live in the "fleet" schema (Flyway default schema).
-- A dedicated schema keeps tables out of Supabase's auto-exposed "public" REST API.

-- ---------------------------------------------------------------------------
-- Users, roles, settings
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_logins   INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE roles (
    code        VARCHAR(30) PRIMARY KEY,
    description VARCHAR(200) NOT NULL
);
INSERT INTO roles (code, description) VALUES
    ('ADMIN',    'Full access including users, roles and system settings'),
    ('MANAGER',  'All data changes, import replacements, alert resolution'),
    ('OPERATOR', 'Data entry: vehicles, GPS km, fuel, service, documents, imports (skip mode)'),
    ('VIEWER',   'Read-only dashboards and reports');

CREATE TABLE user_roles (
    user_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_code VARCHAR(30) NOT NULL REFERENCES roles (code),
    PRIMARY KEY (user_id, role_code)
);

CREATE TABLE app_settings (
    setting_key  VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL,
    value_type   VARCHAR(20)  NOT NULL CHECK (value_type IN ('INTEGER', 'DECIMAL', 'STRING', 'LIST', 'BOOLEAN')),
    description  VARCHAR(300) NOT NULL,
    updated_by   BIGINT REFERENCES users (id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Masters
-- ---------------------------------------------------------------------------
CREATE TABLE departments (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(30)  NOT NULL UNIQUE,
    name       VARCHAR(150) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE vendors (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    vendor_type    VARCHAR(20)  NOT NULL CHECK (vendor_type IN ('FUEL_STATION', 'WORKSHOP', 'VEHICLE_VENDOR', 'OTHER')),
    contact_person VARCHAR(150),
    phone          VARCHAR(30),
    email          VARCHAR(200),
    gstin          VARCHAR(20),
    address        VARCHAR(500),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_vendors_name_type ON vendors (lower(name), vendor_type);

CREATE TABLE drivers (
    id             BIGSERIAL PRIMARY KEY,
    full_name      VARCHAR(150) NOT NULL,
    phone          VARCHAR(30),
    licence_number VARCHAR(40),
    licence_expiry DATE,
    vendor_id      BIGINT REFERENCES vendors (id),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    remarks        VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_drivers_licence ON drivers (upper(licence_number)) WHERE licence_number IS NOT NULL;

CREATE TABLE vehicle_categories (
    code                    VARCHAR(30) PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    service_interval_km     INT CHECK (service_interval_km > 0),
    service_interval_months INT CHECK (service_interval_months > 0)
);
INSERT INTO vehicle_categories (code, name) VALUES
    ('HATCHBACK', 'Hatchback'), ('SEDAN', 'Sedan'), ('SUV', 'SUV'), ('MUV', 'MUV / MPV'),
    ('TEMPO_TRAVELLER', 'Tempo Traveller'), ('BUS', 'Bus'), ('LCV', 'Light Commercial Vehicle'), ('OTHER', 'Other');

CREATE TABLE stored_files (
    id                BIGSERIAL PRIMARY KEY,
    storage_key       VARCHAR(300) NOT NULL UNIQUE,
    original_filename VARCHAR(300) NOT NULL,
    content_type      VARCHAR(150) NOT NULL,
    size_bytes        BIGINT       NOT NULL CHECK (size_bytes >= 0),
    sha256            CHAR(64)     NOT NULL,
    purpose           VARCHAR(30)  NOT NULL CHECK (purpose IN ('IMPORT', 'FUEL_RECEIPT', 'SERVICE_INVOICE', 'VEHICLE_DOCUMENT', 'REPORT', 'OTHER')),
    uploaded_by       BIGINT REFERENCES users (id),
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_stored_files_sha ON stored_files (sha256);

-- ---------------------------------------------------------------------------
-- Vehicles
-- ---------------------------------------------------------------------------
CREATE TABLE vehicles (
    id                          BIGSERIAL PRIMARY KEY,
    registration_number         VARCHAR(20)  NOT NULL,
    registration_normalized     VARCHAR(20)  NOT NULL CHECK (registration_normalized ~ '^[A-Z0-9]{4,20}$'),
    duplicate_registration_reason VARCHAR(300),
    make                        VARCHAR(80)  NOT NULL,
    model                       VARCHAR(80)  NOT NULL,
    model_year                  SMALLINT CHECK (model_year BETWEEN 1980 AND 2100),
    fuel_type                   VARCHAR(20)  NOT NULL CHECK (fuel_type IN ('PETROL', 'DIESEL', 'CNG', 'CNG_PETROL', 'ELECTRIC', 'HYBRID', 'PLUGIN_HYBRID')),
    category_code               VARCHAR(30)  NOT NULL REFERENCES vehicle_categories (code),
    ownership_type              VARCHAR(20)  NOT NULL CHECK (ownership_type IN ('OWNED', 'LEASED', 'VENDOR_ATTACHED', 'RENTED')),
    vendor_id                   BIGINT REFERENCES vendors (id),
    current_driver_id           BIGINT REFERENCES drivers (id),
    current_department_id       BIGINT REFERENCES departments (id),
    customer_name               VARCHAR(200),
    status                      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'IDLE', 'WORKSHOP', 'SOLD', 'SCRAPPED')),
    expected_mileage            NUMERIC(8, 3) CHECK (expected_mileage > 0),
    service_interval_km         INT CHECK (service_interval_km > 0),
    service_interval_months     INT CHECK (service_interval_months > 0),
    tank_capacity               NUMERIC(8, 2) CHECK (tank_capacity > 0),
    battery_capacity_kwh        NUMERIC(8, 2) CHECK (battery_capacity_kwh > 0),
    purchase_date               DATE,
    purchase_cost               NUMERIC(14, 2) CHECK (purchase_cost >= 0),
    chassis_number              VARCHAR(50),
    engine_number               VARCHAR(50),
    gps_device_name             VARCHAR(100),
    remarks                     VARCHAR(1000),
    deactivated_at              TIMESTAMPTZ,
    created_by                  BIGINT REFERENCES users (id),
    updated_by                  BIGINT REFERENCES users (id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One vehicle per registration unless a documented exception (reason) is recorded.
CREATE UNIQUE INDEX ux_vehicles_registration ON vehicles (registration_normalized) WHERE duplicate_registration_reason IS NULL;
CREATE INDEX ix_vehicles_status ON vehicles (status);
CREATE INDEX ix_vehicles_fuel ON vehicles (fuel_type);
CREATE INDEX ix_vehicles_dept ON vehicles (current_department_id);
CREATE INDEX ix_vehicles_vendor ON vehicles (vendor_id);
CREATE INDEX ix_vehicles_driver ON vehicles (current_driver_id);

-- GPS device names / spreadsheet spellings that map to a vehicle.
CREATE TABLE vehicle_aliases (
    id               BIGSERIAL PRIMARY KEY,
    vehicle_id       BIGINT      NOT NULL REFERENCES vehicles (id),
    alias_normalized VARCHAR(100) NOT NULL UNIQUE,
    alias_raw        VARCHAR(150) NOT NULL,
    created_by       BIGINT REFERENCES users (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Effective-dated history of driver, department, vendor, ownership and status.
CREATE TABLE vehicle_assignments (
    id              BIGSERIAL PRIMARY KEY,
    vehicle_id      BIGINT      NOT NULL REFERENCES vehicles (id),
    assignment_type VARCHAR(20) NOT NULL CHECK (assignment_type IN ('DRIVER', 'DEPARTMENT', 'VENDOR', 'OWNERSHIP', 'STATUS', 'CUSTOMER')),
    driver_id       BIGINT REFERENCES drivers (id),
    department_id   BIGINT REFERENCES departments (id),
    vendor_id       BIGINT REFERENCES vendors (id),
    value_text      VARCHAR(200),
    effective_from  DATE        NOT NULL,
    effective_to    DATE,
    reason          VARCHAR(300),
    changed_by      BIGINT REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX ix_assign_vehicle ON vehicle_assignments (vehicle_id, assignment_type, effective_from);
CREATE UNIQUE INDEX ux_assign_open ON vehicle_assignments (vehicle_id, assignment_type) WHERE effective_to IS NULL;

CREATE TABLE mileage_benchmarks (
    id                BIGSERIAL PRIMARY KEY,
    scope             VARCHAR(12) NOT NULL CHECK (scope IN ('MODEL', 'FUEL_TYPE')),
    make              VARCHAR(80),
    model             VARCHAR(80),
    energy_type       VARCHAR(12) NOT NULL CHECK (energy_type IN ('PETROL', 'DIESEL', 'CNG', 'ELECTRICITY')),
    expected_mileage  NUMERIC(8, 3) NOT NULL CHECK (expected_mileage > 0),
    low_threshold_pct NUMERIC(5, 2) CHECK (low_threshold_pct > 0 AND low_threshold_pct < 100),
    CHECK ((scope = 'MODEL' AND make IS NOT NULL AND model IS NOT NULL) OR (scope = 'FUEL_TYPE' AND make IS NULL AND model IS NULL))
);
CREATE UNIQUE INDEX ux_benchmark ON mileage_benchmarks (scope, coalesce(lower(make), ''), coalesce(lower(model), ''), energy_type);

-- ---------------------------------------------------------------------------
-- Imports and background jobs
-- ---------------------------------------------------------------------------
CREATE TABLE import_batches (
    id                    BIGSERIAL PRIMARY KEY,
    import_type           VARCHAR(10) NOT NULL CHECK (import_type IN ('GPS_KM', 'FUEL')),
    file_id               BIGINT      NOT NULL REFERENCES stored_files (id),
    original_filename     VARCHAR(300) NOT NULL,
    file_sha256           CHAR(64)    NOT NULL,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('HEADERS_PENDING', 'HEADERS_READY', 'PARSE_PENDING', 'PARSED', 'COMMITTING', 'COMMITTED', 'FAILED', 'CANCELLED')),
    sheet_info            JSONB,
    selected_sheet        VARCHAR(150),
    header_row            INT,
    column_mapping        JSONB,
    options               JSONB NOT NULL DEFAULT '{}'::jsonb,
    total_rows            INT NOT NULL DEFAULT 0,
    valid_rows            INT NOT NULL DEFAULT 0,
    warning_rows          INT NOT NULL DEFAULT 0,
    rejected_rows         INT NOT NULL DEFAULT 0,
    duplicate_rows        INT NOT NULL DEFAULT 0,
    existing_conflict_rows INT NOT NULL DEFAULT 0,
    inserted_rows         INT NOT NULL DEFAULT 0,
    replaced_rows         INT NOT NULL DEFAULT 0,
    skipped_rows          INT NOT NULL DEFAULT 0,
    duplicate_policy      VARCHAR(10) CHECK (duplicate_policy IN ('SKIP', 'REPLACE')),
    replace_reason        VARCHAR(500),
    committed_by          BIGINT REFERENCES users (id),
    committed_at          TIMESTAMPTZ,
    error_message         VARCHAR(2000),
    created_by            BIGINT REFERENCES users (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_import_batches_created ON import_batches (created_at DESC);
CREATE INDEX ix_import_batches_sha ON import_batches (file_sha256);

CREATE TABLE import_staging_rows (
    id          BIGSERIAL PRIMARY KEY,
    batch_id    BIGINT      NOT NULL REFERENCES import_batches (id) ON DELETE CASCADE,
    row_number  INT         NOT NULL,
    raw         JSONB       NOT NULL,
    parsed      JSONB,
    vehicle_id  BIGINT REFERENCES vehicles (id),
    record_date DATE,
    status      VARCHAR(12) NOT NULL CHECK (status IN ('VALID', 'WARNING', 'REJECTED', 'DUPLICATE')),
    group_key   VARCHAR(200),
    messages    JSONB NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX ix_staging_batch ON import_staging_rows (batch_id, status, row_number);

CREATE TABLE import_row_errors (
    id          BIGSERIAL PRIMARY KEY,
    batch_id    BIGINT      NOT NULL REFERENCES import_batches (id) ON DELETE CASCADE,
    row_number  INT         NOT NULL,
    field_name  VARCHAR(60),
    error_code  VARCHAR(60) NOT NULL,
    message     VARCHAR(500) NOT NULL,
    raw_value   VARCHAR(500),
    stage       VARCHAR(10) NOT NULL CHECK (stage IN ('PARSE', 'COMMIT')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_row_errors_batch ON import_row_errors (batch_id, row_number);

CREATE TABLE background_jobs (
    id           BIGSERIAL PRIMARY KEY,
    job_type     VARCHAR(40) NOT NULL CHECK (job_type IN ('DETECT_HEADERS', 'PARSE_IMPORT', 'RECONCILIATION_REPORT')),
    status       VARCHAR(12) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    payload      JSONB       NOT NULL,
    result       JSONB,
    attempts     INT         NOT NULL DEFAULT 0,
    max_attempts INT         NOT NULL DEFAULT 3,
    locked_by    VARCHAR(100),
    locked_at    TIMESTAMPTZ,
    run_after    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error   VARCHAR(4000),
    created_by   BIGINT REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX ix_jobs_queue ON background_jobs (status, run_after) WHERE status IN ('QUEUED', 'RUNNING');

-- ---------------------------------------------------------------------------
-- Measurements: odometer, GPS km, fuel
-- ---------------------------------------------------------------------------
CREATE TABLE odometer_readings (
    id               BIGSERIAL PRIMARY KEY,
    vehicle_id       BIGINT       NOT NULL REFERENCES vehicles (id),
    reading_date     DATE         NOT NULL,
    reading_km       NUMERIC(10, 1) NOT NULL CHECK (reading_km >= 0),
    source           VARCHAR(16)  NOT NULL CHECK (source IN ('VEHICLE_MASTER', 'SERVICE', 'FUEL', 'MANUAL')),
    source_ref_id    BIGINT,
    is_trusted       BOOLEAN      NOT NULL DEFAULT TRUE,
    untrusted_reason VARCHAR(200),
    is_void          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by       BIGINT REFERENCES users (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_odo_vehicle_date ON odometer_readings (vehicle_id, reading_date, reading_km) WHERE NOT is_void;

CREATE TABLE gps_daily_km (
    id                  BIGSERIAL PRIMARY KEY,
    vehicle_id          BIGINT       NOT NULL REFERENCES vehicles (id),
    km_date             DATE         NOT NULL,
    distance_km         NUMERIC(9, 2) NOT NULL CHECK (distance_km >= 0),
    record_mode         VARCHAR(12)  NOT NULL CHECK (record_mode IN ('DAILY_TOTAL', 'TRIP_SUM', 'MANUAL')),
    trip_count          INT CHECK (trip_count >= 0),
    source              VARCHAR(12)  NOT NULL CHECK (source IN ('IMPORT', 'MANUAL', 'CORRECTION')),
    source_device_name  VARCHAR(150),
    import_batch_id     BIGINT REFERENCES import_batches (id),
    import_row_numbers  VARCHAR(500),
    is_current          BOOLEAN      NOT NULL DEFAULT TRUE,
    superseded_by_id    BIGINT REFERENCES gps_daily_km (id),
    superseded_at       TIMESTAMPTZ,
    superseded_by_user  BIGINT REFERENCES users (id),
    supersede_reason    VARCHAR(500),
    notes               VARCHAR(500),
    created_by          BIGINT REFERENCES users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Exactly one current daily figure per vehicle per day: repeat imports cannot double count.
CREATE UNIQUE INDEX ux_gps_current ON gps_daily_km (vehicle_id, km_date) WHERE is_current;
CREATE INDEX ix_gps_date ON gps_daily_km (km_date) WHERE is_current;
CREATE INDEX ix_gps_batch ON gps_daily_km (import_batch_id);

CREATE TABLE fuel_transactions (
    id                 BIGSERIAL PRIMARY KEY,
    vehicle_id         BIGINT       NOT NULL REFERENCES vehicles (id),
    txn_date           DATE         NOT NULL,
    txn_time           TIME,
    energy_type        VARCHAR(12)  NOT NULL CHECK (energy_type IN ('PETROL', 'DIESEL', 'CNG', 'ELECTRICITY')),
    unit               VARCHAR(4)   NOT NULL CHECK (unit IN ('L', 'KG', 'KWH')),
    quantity           NUMERIC(12, 3) NOT NULL CHECK (quantity > 0),
    unit_price         NUMERIC(12, 4) CHECK (unit_price >= 0),
    unit_price_derived BOOLEAN      NOT NULL DEFAULT FALSE,
    gross_amount       NUMERIC(14, 2) CHECK (gross_amount >= 0),
    discount_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_amount         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    invoice_amount     NUMERIC(14, 2) CHECK (invoice_amount >= 0),
    calculated_amount  NUMERIC(14, 2),
    expense_amount     NUMERIC(14, 2) NOT NULL CHECK (expense_amount >= 0),
    amount_source      VARCHAR(20)  NOT NULL CHECK (amount_source IN ('INVOICE', 'GROSS_LESS_DISCOUNT', 'CALCULATED')),
    amount_mismatch    BOOLEAN      NOT NULL DEFAULT FALSE,
    amount_difference  NUMERIC(14, 2),
    odometer_km        NUMERIC(10, 1) CHECK (odometer_km >= 0),
    is_full_tank       BOOLEAN,
    station_name       VARCHAR(200),
    vendor_id          BIGINT REFERENCES vendors (id),
    invoice_number     VARCHAR(80),
    transaction_ref    VARCHAR(80),
    payment_method     VARCHAR(16) CHECK (payment_method IN ('CASH', 'CARD', 'UPI', 'FUEL_CARD', 'CREDIT', 'BANK_TRANSFER', 'OTHER')),
    attachment_file_id BIGINT REFERENCES stored_files (id),
    notes              VARCHAR(1000),
    quantity_flag      VARCHAR(40),
    source             VARCHAR(10)  NOT NULL CHECK (source IN ('IMPORT', 'MANUAL')),
    import_batch_id    BIGINT REFERENCES import_batches (id),
    import_row_number  INT,
    dedupe_key         CHAR(64)     NOT NULL,
    is_void            BOOLEAN      NOT NULL DEFAULT FALSE,
    void_reason        VARCHAR(500),
    voided_by          BIGINT REFERENCES users (id),
    voided_at          TIMESTAMPTZ,
    created_by         BIGINT REFERENCES users (id),
    updated_by         BIGINT REFERENCES users (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((energy_type IN ('PETROL', 'DIESEL') AND unit = 'L') OR (energy_type = 'CNG' AND unit = 'KG') OR (energy_type = 'ELECTRICITY' AND unit = 'KWH'))
);
CREATE UNIQUE INDEX ux_fuel_dedupe ON fuel_transactions (dedupe_key) WHERE NOT is_void;
CREATE UNIQUE INDEX ux_fuel_txn_ref ON fuel_transactions (upper(transaction_ref)) WHERE transaction_ref IS NOT NULL AND NOT is_void;
CREATE INDEX ix_fuel_vehicle_date ON fuel_transactions (vehicle_id, txn_date) WHERE NOT is_void;
CREATE INDEX ix_fuel_date ON fuel_transactions (txn_date) WHERE NOT is_void;
CREATE INDEX ix_fuel_invoice ON fuel_transactions (upper(invoice_number)) WHERE invoice_number IS NOT NULL AND NOT is_void;

-- Authoritative efficiency intervals computed by the Java engine (MileageEngine).
-- Dashboards and reports only SUM these; they never re-derive intervals.
CREATE TABLE fuel_efficiency_intervals (
    id               BIGSERIAL PRIMARY KEY,
    vehicle_id       BIGINT       NOT NULL REFERENCES vehicles (id),
    energy_type      VARCHAR(12)  NOT NULL,
    method           VARCHAR(20)  NOT NULL CHECK (method IN ('FULL_TANK', 'SUCCESSIVE_REFUEL')),
    distance_source  VARCHAR(10)  NOT NULL CHECK (distance_source IN ('ODOMETER', 'GPS')),
    start_txn_id     BIGINT       NOT NULL REFERENCES fuel_transactions (id),
    end_txn_id       BIGINT       NOT NULL REFERENCES fuel_transactions (id),
    start_date       DATE         NOT NULL,
    end_date         DATE         NOT NULL,
    start_odometer   NUMERIC(10, 1),
    end_odometer     NUMERIC(10, 1),
    distance_km      NUMERIC(10, 2),
    quantity         NUMERIC(12, 3) NOT NULL,
    mileage          NUMERIC(10, 3),
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('ELIGIBLE', 'EXCLUDED')),
    exclusion_reason VARCHAR(40),
    computed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_fei_vehicle ON fuel_efficiency_intervals (vehicle_id, end_date);
CREATE INDEX ix_fei_period ON fuel_efficiency_intervals (end_date, energy_type) WHERE status = 'ELIGIBLE';

-- ---------------------------------------------------------------------------
-- Maintenance
-- ---------------------------------------------------------------------------
CREATE TABLE service_categories (
    code                    VARCHAR(30) PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    resets_service_interval BOOLEAN NOT NULL DEFAULT FALSE,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO service_categories (code, name, resets_service_interval) VALUES
    ('ROUTINE_SERVICE', 'Routine / periodic service', TRUE),
    ('OIL_CHANGE', 'Engine oil change', TRUE),
    ('EV_PERIODIC_SERVICE', 'EV periodic service', TRUE),
    ('FILTERS', 'Filters', FALSE),
    ('BRAKES', 'Brakes', FALSE),
    ('TYRES', 'Tyres', FALSE),
    ('BATTERY', 'Battery', FALSE),
    ('EV_MAINTENANCE', 'EV component repair', FALSE),
    ('REPAIR', 'General repair', FALSE),
    ('ACCIDENT', 'Accident repair', FALSE),
    ('BODY_WORK', 'Body / paint work', FALSE),
    ('OTHER', 'Other', FALSE);

CREATE TABLE maintenance_records (
    id                       BIGSERIAL PRIMARY KEY,
    vehicle_id               BIGINT       NOT NULL REFERENCES vehicles (id),
    service_date             DATE         NOT NULL,
    odometer_km              NUMERIC(10, 1) CHECK (odometer_km >= 0),
    category_code            VARCHAR(30)  NOT NULL REFERENCES service_categories (code),
    description              VARCHAR(2000) NOT NULL,
    parts_cost               NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (parts_cost >= 0),
    labour_cost              NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (labour_cost >= 0),
    other_cost               NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (other_cost >= 0),
    tax_amount               NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    calculated_total         NUMERIC(14, 2) NOT NULL,
    total_cost               NUMERIC(14, 2) NOT NULL CHECK (total_cost >= 0),
    cost_mismatch            BOOLEAN      NOT NULL DEFAULT FALSE,
    workshop_name            VARCHAR(200),
    vendor_id                BIGINT REFERENCES vendors (id),
    invoice_number           VARCHAR(80),
    job_card_number          VARCHAR(80),
    attachment_file_id       BIGINT REFERENCES stored_files (id),
    next_service_date        DATE,
    next_service_odometer    NUMERIC(10, 1),
    next_service_overridden  BOOLEAN NOT NULL DEFAULT FALSE,
    remarks                  VARCHAR(1000),
    is_void                  BOOLEAN NOT NULL DEFAULT FALSE,
    void_reason              VARCHAR(500),
    voided_by                BIGINT REFERENCES users (id),
    voided_at                TIMESTAMPTZ,
    created_by               BIGINT REFERENCES users (id),
    updated_by               BIGINT REFERENCES users (id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_maint_vehicle_date ON maintenance_records (vehicle_id, service_date) WHERE NOT is_void;
CREATE INDEX ix_maint_date ON maintenance_records (service_date) WHERE NOT is_void;

-- ---------------------------------------------------------------------------
-- Compliance documents
-- ---------------------------------------------------------------------------
CREATE TABLE vehicle_documents (
    id                  BIGSERIAL PRIMARY KEY,
    vehicle_id          BIGINT REFERENCES vehicles (id),
    driver_id           BIGINT REFERENCES drivers (id),
    doc_type            VARCHAR(20) NOT NULL CHECK (doc_type IN ('INSURANCE', 'FITNESS', 'PUC', 'PERMIT', 'REGISTRATION', 'ROAD_TAX', 'DRIVING_LICENCE', 'OTHER')),
    document_number     VARCHAR(80),
    issuer              VARCHAR(200),
    issue_date          DATE,
    expiry_date         DATE,
    verification_status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    verified_by         BIGINT REFERENCES users (id),
    verified_at         TIMESTAMPTZ,
    file_id             BIGINT REFERENCES stored_files (id),
    remarks             VARCHAR(1000),
    is_void             BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          BIGINT REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (vehicle_id IS NOT NULL OR driver_id IS NOT NULL),
    CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date >= issue_date)
);
CREATE INDEX ix_docs_vehicle ON vehicle_documents (vehicle_id, doc_type, expiry_date) WHERE NOT is_void;
CREATE INDEX ix_docs_expiry ON vehicle_documents (expiry_date) WHERE NOT is_void;

-- ---------------------------------------------------------------------------
-- Alerts and audit
-- ---------------------------------------------------------------------------
CREATE TABLE alerts (
    id                   BIGSERIAL PRIMARY KEY,
    alert_type           VARCHAR(40) NOT NULL,
    severity             VARCHAR(10) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    vehicle_id           BIGINT REFERENCES vehicles (id),
    dedupe_key           VARCHAR(200) NOT NULL,
    title                VARCHAR(300) NOT NULL,
    message              VARCHAR(2000) NOT NULL,
    rule_explanation     JSONB NOT NULL,
    contributing_records JSONB NOT NULL DEFAULT '[]'::jsonb,
    period_start         DATE,
    period_end           DATE,
    status               VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'DISMISSED')),
    assigned_to          BIGINT REFERENCES users (id),
    first_detected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ,
    resolved_by          BIGINT REFERENCES users (id),
    resolution_note      VARCHAR(2000)
);
-- An open alert is re-detected (updated), never duplicated.
CREATE UNIQUE INDEX ux_alerts_open ON alerts (dedupe_key) WHERE status IN ('OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS');
CREATE INDEX ix_alerts_status ON alerts (status, severity, last_detected_at DESC);
CREATE INDEX ix_alerts_vehicle ON alerts (vehicle_id);

CREATE TABLE alert_events (
    id         BIGSERIAL PRIMARY KEY,
    alert_id   BIGINT      NOT NULL REFERENCES alerts (id) ON DELETE CASCADE,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('CREATED', 'REDETECTED', 'ACKNOWLEDGED', 'ASSIGNED', 'NOTE', 'STATUS_CHANGED', 'RESOLVED', 'REOPENED')),
    note       VARCHAR(2000),
    user_id    BIGINT REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_alert_events ON alert_events (alert_id, created_at);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users (id),
    user_email  VARCHAR(255),
    action      VARCHAR(40)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   VARCHAR(60),
    before_data JSONB,
    after_data  JSONB,
    ip_address  VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_created ON audit_logs (created_at DESC);
