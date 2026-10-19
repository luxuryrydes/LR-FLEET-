package com.lrtaxi.fleet.gps;

import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.reports.Exporter;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gps")
public class GpsController {

    private final GpsService gps;

    public GpsController(GpsService gps) {
        this.gps = gps;
    }

    public record ManualKm(@NotNull Long vehicleId, @NotNull LocalDate date, @NotNull @DecimalMin("0") BigDecimal km,
                           @Size(max = 500) String notes, @Size(max = 500) String correctionReason) {
    }

    @GetMapping
    @PreAuthorize(Roles.READ)
    public PageResult<Map<String, Object>> list(@ModelAttribute FleetFilter filter,
                                                @RequestParam(defaultValue = "false") boolean includeHistory,
                                                @RequestParam(required = false) String source,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String sort) {
        filter.validate();
        return gps.list(filter, includeHistory, source, page, size, sort);
    }

    @GetMapping("/export")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<byte[]> export(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String source,
                                         @RequestParam(defaultValue = "xlsx") String format) {
        filter.validate();
        return Exporter.respond("daily_gps_km", format, List.of(
                Exporter.date("Date", "kmDate"), Exporter.col("Vehicle", "registrationNumber"), Exporter.num("Distance (km)", "distanceKm"),
                Exporter.col("Record type", "recordMode"), Exporter.integer("Trips", "tripCount"), Exporter.col("Source", "source"),
                Exporter.col("GPS device name", "sourceDeviceName"), Exporter.integer("Import batch", "importBatchId"),
                Exporter.col("Notes", "notes")), gps.listForExport(filter, source));
    }

    @PostMapping
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> manual(@Valid @RequestBody ManualKm req) {
        return gps.manual(req.vehicleId(), req.date(), req.km(), req.notes(), req.correctionReason());
    }
}
