-- V2: configurable business-rule thresholds (editable by ADMIN in Settings) and database hardening.

INSERT INTO app_settings (setting_key, setting_value, value_type, description) VALUES
    ('service.default_interval_km',   '10000', 'INTEGER', 'Default service interval in km when neither vehicle nor category sets one'),
    ('service.default_interval_months','6',    'INTEGER', 'Default date-based service interval in months'),
    ('service.due_soon_km',           '500',   'INTEGER', 'Service is DUE SOON when remaining km is at or below this'),
    ('service.due_soon_days',         '15',    'INTEGER', 'Service is DUE SOON when the next service date is within this many days'),
    ('gps.max_daily_km',              '1500',  'INTEGER', 'Daily GPS distance above this is rejected as invalid'),
    ('gps.no_report_days',            '3',     'INTEGER', 'Active vehicle with no GPS record for this many days raises an alert'),
    ('fuel.max_quantity.PETROL',      '120',   'DECIMAL', 'Max litres per petrol transaction when tank capacity is unknown'),
    ('fuel.max_quantity.DIESEL',      '400',   'DECIMAL', 'Max litres per diesel transaction when tank capacity is unknown'),
    ('fuel.max_quantity.CNG',         '100',   'DECIMAL', 'Max kg per CNG transaction'),
    ('fuel.max_quantity.ELECTRICITY', '200',   'DECIMAL', 'Max kWh per charging transaction when battery capacity is unknown'),
    ('fuel.amount_tolerance',         '1.00',  'DECIMAL', 'Invoice vs calculated amount difference (Rs) above which a mismatch is flagged'),
    ('fuel.accounting_policy',        'AUTO',  'STRING',  'AUTO = full-tank-to-full-tank when full-tank flags exist, else successive refuel; FULL_TANK_ONLY = only full-tank intervals'),
    ('fuel.bifuel_attribution',       'NO_OTHER_FUEL_IN_INTERVAL', 'STRING', 'NO_OTHER_FUEL_IN_INTERVAL = attribute a bi-fuel interval only when no other fuel was bought inside it; NONE = always mixed-fuel'),
    ('mileage.min.PETROL',            '3',     'DECIMAL', 'Lowest plausible km/L for petrol'),
    ('mileage.max.PETROL',            '35',    'DECIMAL', 'Highest plausible km/L for petrol'),
    ('mileage.min.DIESEL',            '2',     'DECIMAL', 'Lowest plausible km/L for diesel'),
    ('mileage.max.DIESEL',            '35',    'DECIMAL', 'Highest plausible km/L for diesel'),
    ('mileage.min.CNG',               '5',     'DECIMAL', 'Lowest plausible km/kg for CNG'),
    ('mileage.max.CNG',               '45',    'DECIMAL', 'Highest plausible km/kg for CNG'),
    ('mileage.min.ELECTRICITY',       '2',     'DECIMAL', 'Lowest plausible km/kWh'),
    ('mileage.max.ELECTRICITY',       '15',    'DECIMAL', 'Highest plausible km/kWh'),
    ('mileage.low_threshold_pct',     '15',    'DECIMAL', 'Alert when actual mileage is this % or more below expected'),
    ('alert.window_days',             '30',    'INTEGER', 'Look-back window for reconciliation alerts'),
    ('alert.gps_no_fuel_min_km',      '300',   'INTEGER', 'GPS km in window without any fuel record that raises an alert'),
    ('alert.high_cpk_pct',            '30',    'DECIMAL', 'Alert when vehicle fuel cost/km exceeds fleet average for its fuel by this %'),
    ('alert.high_maintenance_amount', '50000', 'DECIMAL', 'Maintenance spend in the window (Rs) that raises an alert'),
    ('docs.reminder_days',            '30,15,7','LIST',   'Days before expiry at which document reminders are raised'),
    ('docs.required_types',           'INSURANCE,FITNESS,PUC,PERMIT,REGISTRATION', 'LIST', 'Documents every active vehicle must hold');

INSERT INTO mileage_benchmarks (scope, energy_type, expected_mileage) VALUES
    ('FUEL_TYPE', 'PETROL', 14), ('FUEL_TYPE', 'DIESEL', 15), ('FUEL_TYPE', 'CNG', 22), ('FUEL_TYPE', 'ELECTRICITY', 7);

-- Supabase exposes "anon" and "authenticated" roles through its REST API.
-- This application talks to PostgreSQL only through the Java backend, so make sure
-- those roles cannot reach the fleet schema even if someone adds it to the exposed schemas.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        EXECUTE 'REVOKE ALL ON SCHEMA fleet FROM anon';
        EXECUTE 'REVOKE ALL ON ALL TABLES IN SCHEMA fleet FROM anon';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        EXECUTE 'REVOKE ALL ON SCHEMA fleet FROM authenticated';
        EXECUTE 'REVOKE ALL ON ALL TABLES IN SCHEMA fleet FROM authenticated';
    END IF;
END
$$;
