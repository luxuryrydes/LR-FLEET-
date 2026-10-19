-- OPTIONAL demo data for a fresh test database (not for production).
-- Creates the vehicles used by the sample import files in this folder.
--   psql "$DATABASE_URL" -f samples/demo_seed.sql
SET search_path TO fleet;

INSERT INTO departments (code, name) VALUES ('AIRPORT', 'Airport transfers'), ('CORP', 'Corporate rentals'), ('OUTSTN', 'Outstation')
ON CONFLICT (code) DO NOTHING;

INSERT INTO vendors (name, vendor_type, contact_person, phone) VALUES
  ('IGL CNG Station Mahipalpur', 'FUEL_STATION', 'Station manager', '+91-11-0000-0000'),
  ('Sharma Auto Works', 'WORKSHOP', 'R. Sharma', '+91-98-0000-0000')
ON CONFLICT DO NOTHING;

INSERT INTO drivers (full_name, phone, licence_number, licence_expiry) VALUES
  ('Ramesh Kumar', '+91-90000-00001', 'DL0420110012345', DATE '2027-03-31'),
  ('Suresh Yadav', '+91-90000-00002', 'MH1220150054321', DATE '2026-11-05')
ON CONFLICT DO NOTHING;

INSERT INTO vehicles (registration_number, registration_normalized, make, model, model_year, fuel_type, category_code, ownership_type,
                      current_department_id, current_driver_id, expected_mileage, tank_capacity, battery_capacity_kwh, gps_device_name)
SELECT v.reg, v.reg, v.make, v.model, v.yr, v.fuel, v.cat, 'OWNED', d.id, dr.id, v.exp, v.tank, v.batt, v.gps
FROM (VALUES
  ('DL1RT1234', 'Maruti Suzuki', 'Dzire Tour S', 2023, 'CNG', 'SEDAN', 'AIRPORT', 'Ramesh Kumar', 24.0, NULL, NULL, 'DL1RT1234 (CNG)'),
  ('MH12AB1234', 'Toyota', 'Innova Crysta', 2022, 'PETROL', 'MUV', 'CORP', 'Suresh Yadav', 11.0, 55, NULL, NULL),
  ('KA05MN4321', 'Maruti Suzuki', 'Ertiga', 2024, 'CNG', 'MUV', 'OUTSTN', NULL, 22.0, NULL, NULL, 'KA05MN4321-GPS'),
  ('HR26DK0002', 'Tata', 'Nexon EV', 2024, 'ELECTRIC', 'SUV', 'CORP', NULL, 7.5, NULL, 40.5, NULL)
) AS v(reg, make, model, yr, fuel, cat, dept, driver, exp, tank, batt, gps)
JOIN departments d ON d.code = v.dept
LEFT JOIN drivers dr ON dr.full_name = v.driver
WHERE NOT EXISTS (SELECT 1 FROM vehicles x WHERE x.registration_normalized = v.reg);

INSERT INTO vehicle_aliases (vehicle_id, alias_normalized, alias_raw)
SELECT id, 'DL1RT1234CNG', 'DL1RT1234 (CNG)' FROM vehicles WHERE registration_normalized = 'DL1RT1234'
ON CONFLICT DO NOTHING;

INSERT INTO vehicle_assignments (vehicle_id, assignment_type, value_text, effective_from, reason)
SELECT id, 'STATUS', 'ACTIVE', DATE '2026-01-01', 'Demo seed' FROM vehicles
WHERE NOT EXISTS (SELECT 1 FROM vehicle_assignments a WHERE a.vehicle_id = vehicles.id AND a.assignment_type = 'STATUS');
INSERT INTO vehicle_assignments (vehicle_id, assignment_type, department_id, effective_from, reason)
SELECT id, 'DEPARTMENT', current_department_id, DATE '2026-01-01', 'Demo seed' FROM vehicles
WHERE current_department_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM vehicle_assignments a WHERE a.vehicle_id = vehicles.id AND a.assignment_type = 'DEPARTMENT');

INSERT INTO odometer_readings (vehicle_id, reading_date, reading_km, source)
SELECT id, DATE '2026-08-31', v.km, 'VEHICLE_MASTER'
FROM vehicles JOIN (VALUES ('DL1RT1234', 45000), ('MH12AB1234', 30210), ('KA05MN4321', 52000), ('HR26DK0002', 18000)) AS v(reg, km)
  ON v.reg = vehicles.registration_normalized
WHERE NOT EXISTS (SELECT 1 FROM odometer_readings o WHERE o.vehicle_id = vehicles.id);

INSERT INTO maintenance_records (vehicle_id, service_date, odometer_km, category_code, description, parts_cost, labour_cost,
                                 calculated_total, total_cost, workshop_name, next_service_date, next_service_odometer)
SELECT id, DATE '2026-08-20', 44700, 'ROUTINE_SERVICE', 'Periodic service, oil and filters', 2800, 900, 3700, 3700,
       'Sharma Auto Works', DATE '2027-02-20', 54700
FROM vehicles WHERE registration_normalized = 'DL1RT1234'
  AND NOT EXISTS (SELECT 1 FROM maintenance_records m WHERE m.vehicle_id = vehicles.id);
