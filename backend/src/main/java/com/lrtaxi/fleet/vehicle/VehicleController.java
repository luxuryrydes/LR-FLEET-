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
    private final VehicleDeleteService deletes;
    private final com.lrtaxi.fleet.gps.GpsHoldService held;

    public VehicleController(VehicleService vehicles, OdometerService odometer, ServiceStatusService serviceStatus, EfficiencyService efficiency,
                             VehicleDeleteService deletes, com.lrtaxi.fleet.gps.GpsHoldService held) {
        this.deletes = deletes;
        this.held = held;
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
                Exporter.col("Driver type", "operatorType"), Exporter.col("Sheet label", "assignmentLabel"),
                Exporter.col("GPS km imported", "gpsKmTracked"), Exporter.col("Private/Commercial", "registrationClass"),
                Exporter.col("Disposal", "disposalType"), Exporter.date("Disposal date", "disposalDate"),
                Exporter.col("Sold to / dealer", "disposalParty"), Exporter.money("Sale amount", "disposalAmount"),
                Exporter.col("GPS device", "gpsDeviceName"), Exporter.col("Remarks", "remarks")), all);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> v = new HashMap<>(vehicles.get(id));
        v.put("serviceStatus", serviceStatus.forVehicle(id));
        v.put("efficiency", efficiency.vehicleSummary(id, LocalDate.now().minusDays(89), LocalDate.now()));
        v.put("heldGps", held.summary(id));        // GPS km kept aside while the vehicle is not with an LR driver
        return v;
    }

    @PostMapping
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> create(@Valid @RequestBody VehicleRequest req) {
        return Map.of("id", vehicles.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody VehicleRequest req) {
        Map<String, Object> gps = vehicles.update(id, req);
        return gps == null ? Map.of() : Map.of("gpsKm", gps);
    }

    public record IdsRequest(@NotNull @Size(min = 1, max = VehicleDeleteService.MAX_BATCH) java.util.List<Long> ids, @Size(max = 500) String reason) {
    }

    /** Delete one or many vehicles (hidden everywhere, history kept, restorable). */
    @PostMapping("/delete")
    @PreAuthorize(Roles.MANAGE)
    public Map<String, Object> delete(@Valid @RequestBody IdsRequest req) {
        return deletes.delete(req.ids(), req.reason());
    }

    @PostMapping("/restore")
    @PreAuthorize(Roles.MANAGE)
    public Map<String, Object> restore(@Valid @RequestBody IdsRequest req) {
        return deletes.restore(req.ids());
    }

    /** Permanently removes deleted vehicles that have no records. */
    @PostMapping("/purge")
    @PreAuthorize(Roles.ADMIN)
    public Map<String, Object> purge(@Valid @RequestBody IdsRequest req) {
        return deletes.purge(req.ids());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(Roles.MANAGE)
    public void deactivate(@PathVariable long id, @Valid @RequestBody ReasonRequest req) {
        vehicles.deactivate(id, req.reason());
    }

    public record DisposalRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "SOLD|SCRAPPED|RETURNED_TO_VENDOR|TRANSFERRED|OTHER") String type,
                                  @NotNull LocalDate date, @Size(max = 200) String party, @DecimalMin("0") BigDecimal amount,
                                  @Size(max = 1000) String remarks) {
    }

    @PostMapping("/{id}/dispose")
    @PreAuthorize(Roles.MANAGE)
    public void dispose(@PathVariable long id, @Valid @RequestBody DisposalRequest req) {
        vehicles.dispose(id, new VehicleService.Disposal(req.type(), req.date(), req.party(), req.amount(), req.remarks()));
    }

    @PostMapping("/{id}/undo-disposal")
    @PreAuthorize(Roles.MANAGE)
    public void undoDisposal(@PathVariable long id, @Valid @RequestBody ReasonRequest req) {
        vehicles.undoDisposal(id, req.reason());
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
