package com.lrtaxi.fleet.common;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.LocalDate;

/**
 * Filters shared by the dashboard, lists and reports, so that an export and the dashboard
 * with the same filters run the same WHERE clause.
 * Driver/department/vendor filters use the vehicle's current assignment; the department report
 * uses effective-dated assignments instead (see ReportService).
 */
public record FleetFilter(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        Long vehicleId,
        String vehicle,
        String fuelType,
        String category,
        String ownership,
        Long vendorId,
        Long driverId,
        Long departmentId,
        String status,
        String operatorType) {

    public static final int MAX_RANGE_DAYS = 3660;

    public LocalDate fromOrDefault() {
        return from != null ? from : toOrDefault().minusDays(29);
    }

    public LocalDate toOrDefault() {
        return to != null ? to : LocalDate.now();
    }

    public void validate() {
        if (fromOrDefault().isAfter(toOrDefault())) {
            throw ApiException.badRequest("INVALID_RANGE", "'From' date must be on or before 'To' date");
        }
        if (fromOrDefault().plusDays(MAX_RANGE_DAYS).isBefore(toOrDefault())) {
            throw ApiException.badRequest("INVALID_RANGE", "Date range cannot exceed 10 years");
        }
    }

    /** Appends " AND ..." conditions on the vehicles table alias {@code v}. */
    public void appendVehicleConditions(StringBuilder sql, MapSqlParameterSource p, String v) {
        if (vehicleId != null) {
            sql.append(" AND ").append(v).append(".id = :f_vehicle_id");
            p.addValue("f_vehicle_id", vehicleId);
        }
        if (vehicle != null && !vehicle.isBlank()) {
            sql.append(" AND ").append(v).append(".registration_normalized LIKE :f_vehicle");
            p.addValue("f_vehicle", "%" + com.lrtaxi.fleet.calc.RegistrationNormalizer.normalize(vehicle) + "%");
        }
        if (fuelType != null && !fuelType.isBlank()) {
            sql.append(" AND ").append(v).append(".fuel_type = :f_fuel");
            p.addValue("f_fuel", fuelType);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND ").append(v).append(".category_code = :f_category");
            p.addValue("f_category", category);
        }
        if (ownership != null && !ownership.isBlank()) {
            sql.append(" AND ").append(v).append(".ownership_type = :f_ownership");
            p.addValue("f_ownership", ownership);
        }
        if (vendorId != null) {
            sql.append(" AND ").append(v).append(".vendor_id = :f_vendor");
            p.addValue("f_vendor", vendorId);
        }
        if (driverId != null) {
            sql.append(" AND ").append(v).append(".current_driver_id = :f_driver");
            p.addValue("f_driver", driverId);
        }
        if (departmentId != null) {
            sql.append(" AND ").append(v).append(".current_department_id = :f_dept");
            p.addValue("f_dept", departmentId);
        }
        if (operatorType != null && !operatorType.isBlank()) {
            sql.append(" AND ").append(v).append(".operator_type = :f_operator");
            p.addValue("f_operator", operatorType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND ").append(v).append(".status = :f_status");
            p.addValue("f_status", status);
        } else {
            sql.append(" AND ").append(v).append(".status <> 'DELETED'");   // deleted vehicles only show when asked for
        }
    }

    public MapSqlParameterSource dateParams() {
        return new MapSqlParameterSource().addValue("from", fromOrDefault()).addValue("to", toOrDefault());
    }

    public static FleetFilter empty() {
        return new FleetFilter(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
