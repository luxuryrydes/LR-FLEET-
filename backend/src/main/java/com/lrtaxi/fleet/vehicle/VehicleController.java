package com.lrtaxi.fleet.vehicle;

import com.lrtaxi.fleet.calc.Odometer;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.fuel.EfficiencyService;
import com.lrtaxi.fleet.maintenance.ServiceStatusService;
import com.lrtaxi.fleet.odometer.OdometerService;
import com.lrtaxi.fleet.reports.Exporter;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicles;
    private final OdometerService odometer;
    private final ServiceStatusService serviceStatus;
    private final EfficiencyService efficiency;

    public VehicleController(VehicleService vehicles, OdometerService odometer, ServiceStatusService serviceStatus, EfficiencyService efficiency) {
        this.vehicles = vehicles;
        this.odometer = odometer;
        this.serviceStatus = serviceStatus;
        this.efficiency = efficiency;
    }

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record AliasRequest(@NotBlank @Size(max = 150) String alias) {
    }

    public record OdometerRequest(@NotNull LocalDate date, @NotNull @DecimalMin("0") BigDecimal km) {
    }

    public record TrustRequest(boolean trusted, @Size(max = 200) String reason) {
    }

    @GetMapping
    @PreAuthorize(Roles.READ)
    public PageResult<Map<String, Object>> list(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String q,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String sort) {
        return vehicles.list(filter, q, page, size, sort);
    }

    @GetMapping("/export")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<byte[]> export(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "xlsx") String format) {
        List<Map<String, Object>> all = vehicles.listForExport(filter, q);
        return Exporter.respond("vehicles", format, List.of(
                Exporter.col("Registration", "registrationNumber"), Exporter.col("Make", "make"), Exporter.col("Model", "model"),
                Exporter.col("Year", "modelYear"), Exporter.col("Fuel type", "fuelType"), Exporter.col("Category", "categoryCode"),
                Exporter.col("Ownership", "ownershipType"), Exporter.col("Vendor", "vendorName"), Exporter.col("Driver", "driverName"),
                Exporter.col("Department", "departmentName"), Exporter.col("Customer", "customerName"), Exporter.col("Status", "status"),
                Exporter.num("Expected mileage", "expectedMileage"), Exporter.num("Service interval km", "serviceIntervalKm"),
                Exporter.date("Purchase date", "purchaseDate"), Exporter.money("Purchase cost", "purchaseCost"),
                Exporter.col("GPS device", "gpsDeviceName"), Exporter.col("Remarks", "remarks")), all);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> v = new HashMap<>(vehicles.get(id));
        v.put("serviceStatus", serviceStatus.forVehicle(id));
        v.put("efficiency", efficiency.vehicleSummary(id, LocalDate.now().minusDays(89), LocalDate.now()));
        return v;
    }

    @PostMapping
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> create(@Valid @RequestBody VehicleRequest req) {
        return Map.of("id", vehicles.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.WRITE)
    public void update(@PathVariable long id, @Valid @RequestBody VehicleRequest req) {
        vehicles.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(Roles.MANAGE)
    public void deactivate(@PathVariable long id, @Valid @RequestBody ReasonRequest req) {
        vehicles.deactivate(id, req.reason());
    }

    @GetMapping("/{id}/assignments")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> assignments(@PathVariable long id) {
        return vehicles.assignments(id);
    }

    @GetMapping("/{id}/aliases")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> aliases(@PathVariable long id) {
        return vehicles.aliases(id);
    }

    @PostMapping("/{id}/aliases")
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> addAlias(@PathVariable long id, @Valid @RequestBody AliasRequest req) {
        return Map.of("id", vehicles.addAlias(id, req.alias()));
    }

    @DeleteMapping("/{id}/aliases/{aliasId}")
    @PreAuthorize(Roles.MANAGE)
    public void deleteAlias(@PathVariable long id, @PathVariable long aliasId) {
        vehicles.deleteAlias(id, aliasId);
    }

    @GetMapping("/{id}/odometer")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> odometer(@PathVariable long id) {
        return odometer.list(id);
    }

    @PostMapping("/{id}/odometer")
    @PreAuthorize(Roles.WRITE)
    public Odometer.Check addOdometer(@PathVariable long id, @Valid @RequestBody OdometerRequest req) {
        vehicles.get(id);
        return odometer.manual(id, req.date(), req.km());
    }

    @PutMapping("/{id}/odometer/{readingId}/trust")
    @PreAuthorize(Roles.MANAGE)
    public void trust(@PathVariable long id, @PathVariable long readingId, @Valid @RequestBody TrustRequest req) {
        odometer.setTrusted(readingId, req.trusted(), req.reason());
    }

    @GetMapping("/{id}/efficiency-intervals")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> intervals(@PathVariable long id) {
        return efficiency.intervals(id);
    }

    @PostMapping("/{id}/recalculate")
    @PreAuthorize(Roles.WRITE)
    public void recalculate(@PathVariable long id) {
        efficiency.recompute(id);
    }
}
