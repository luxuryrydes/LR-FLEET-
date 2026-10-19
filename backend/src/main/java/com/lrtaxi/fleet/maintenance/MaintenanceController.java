package com.lrtaxi.fleet.maintenance;

import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.reports.Exporter;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MaintenanceController {

    private final MaintenanceService maintenance;
    private final ServiceStatusService status;

    public MaintenanceController(MaintenanceService maintenance, ServiceStatusService status) {
        this.maintenance = maintenance;
        this.status = status;
    }

    public record VoidRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping("/maintenance")
    @PreAuthorize(Roles.READ)
    public PageResult<Map<String, Object>> list(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String category,
                                                @RequestParam(defaultValue = "false") boolean includeVoid,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String sort) {
        filter.validate();
        return maintenance.list(filter, category, includeVoid, page, size, sort);
    }

    @GetMapping("/maintenance/{id}")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> get(@PathVariable long id) {
        return maintenance.get(id);
    }

    @PostMapping("/maintenance")
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> create(@Valid @RequestBody MaintenanceService.MaintenanceInput in) {
        return maintenance.create(in);
    }

    @PostMapping("/maintenance/{id}/void")
    @PreAuthorize(Roles.MANAGE)
    public void voidRecord(@PathVariable long id, @Valid @RequestBody VoidRequest req) {
        maintenance.voidRecord(id, req.reason());
    }

    @GetMapping("/service-status")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> serviceStatus(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String due) {
        return status.fleet(filter, due);
    }

    @GetMapping("/service-status/export")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<byte[]> export(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String due,
                                         @RequestParam(defaultValue = "xlsx") String format) {
        return Exporter.respond("service_due", format, SERVICE_COLUMNS, status.fleet(filter, due));
    }

    public static final List<Exporter.Col> SERVICE_COLUMNS = List.of(
            Exporter.col("Vehicle", "registrationNumber"), Exporter.col("Make", "make"), Exporter.col("Model", "model"),
            Exporter.col("Department", "departmentName"), Exporter.col("Status", "status"),
            Exporter.date("Last service", "lastServiceDate"), Exporter.num("Last service odometer", "lastServiceOdometer"),
            Exporter.integer("Interval km", "intervalKm"), Exporter.num("Next service odometer", "nextServiceOdometer"),
            Exporter.num("Current est. odometer", "currentOdometer"), Exporter.num("Remaining km", "remainingKm"),
            Exporter.date("Next service date", "nextServiceDate"), Exporter.integer("Days remaining", "daysRemaining"),
            Exporter.date("Odometer baseline date", "baselineDate"), Exporter.num("Baseline km", "baselineKm"),
            Exporter.num("GPS km since baseline", "gpsKmAfterBaseline"));
}
