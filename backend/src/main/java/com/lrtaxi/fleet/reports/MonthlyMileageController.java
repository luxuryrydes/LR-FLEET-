package com.lrtaxi.fleet.reports;

import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.security.Roles;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static com.lrtaxi.fleet.reports.Exporter.col;
import static com.lrtaxi.fleet.reports.Exporter.integer;
import static com.lrtaxi.fleet.reports.Exporter.money;
import static com.lrtaxi.fleet.reports.Exporter.num;

@RestController
@RequestMapping("/api/monthly-mileage")
public class MonthlyMileageController {

    static final List<Exporter.Col> COLUMNS = List.of(
            col("Vehicle", "registrationNumber"), col("Make / model", "vehicle"), col("Fuel type", "fuelType"),
            col("Driver type", "operatorType"), col("Department", "departmentName"), num("GPS km", "km"), integer("GPS days", "gpsDays"),
            num("CNG (kg)", "cngKg"), num("Petrol (L)", "petrolL"), num("Diesel (L)", "dieselL"), num("Electricity (kWh)", "electricityKwh"),
            money("Fuel expense", "fuelExpense"), num("Fuel ₹/km", "costPerKm"), num("Monthly mileage", "mileage"),
            col("Unit", "mileageUnit"), num("Expected", "expectedMileage"), num("Variance %", "variancePct"),
            num("Refuelling-interval mileage", "intervalMileage"), col("Status", "status"));

    private final MonthlyMileageService service;

    public MonthlyMileageController(MonthlyMileageService service) {
        this.service = service;
    }

    /** One month: per-vehicle rows and fleet totals. {@code month} = YYYY-MM (default: current month). */
    @GetMapping
    @PreAuthorize(Roles.READ)
    public Map<String, Object> month(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String month) {
        return service.month(filter, MonthlyMileageService.parseMonth(month));
    }

    @GetMapping("/trend")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> trend(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String month,
                                           @RequestParam(defaultValue = "12") int months) {
        return service.trend(filter, MonthlyMileageService.parseMonth(month), months);
    }

    @GetMapping("/export")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<byte[]> export(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String month,
                                         @RequestParam(defaultValue = "xlsx") String format) {
        YearMonth m = MonthlyMileageService.parseMonth(month);
        List<Map<String, Object>> rows = service.rows(MonthlyMileageService.forMonth(filter, m));
        return Exporter.respond("monthly_mileage_" + m, format, COLUMNS, rows, List.of(
                "Monthly mileage calculator - " + m,
                "Fuel ₹/km = fuel expense in the month ÷ GPS km in the month (purchase basis).",
                "Monthly mileage = GPS km ÷ fuel bought in the month; only when one fuel was bought (MIXED_FUEL otherwise).",
                "NO_KM: fuel bought but no GPS km imported (e.g. vendor vehicles). NO_FUEL: km but no fuel recorded.",
                "Refuelling-interval mileage is the engine's odometer/GPS figure between fills, shown for comparison."));
    }
}
