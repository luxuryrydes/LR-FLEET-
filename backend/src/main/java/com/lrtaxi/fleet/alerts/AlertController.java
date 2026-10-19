package com.lrtaxi.fleet.alerts;

import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alerts;
    private final AlertEngine engine;

    public AlertController(AlertService alerts, AlertEngine engine) {
        this.alerts = alerts;
        this.engine = engine;
    }

    public record NoteRequest(@Size(max = 2000) String note) {
    }

    public record AssignRequest(Long userId, @Size(max = 2000) String note) {
    }

    public record StatusRequest(@NotBlank String status, @Size(max = 2000) String note) {
    }

    @GetMapping
    @PreAuthorize(Roles.READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String severity, @RequestParam(required = false) Long vehicleId,
                                                @RequestParam(required = false) Long assignedTo,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return alerts.list(type, status, severity, vehicleId, assignedTo, page, size);
    }

    @GetMapping("/summary")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> summary() {
        return alerts.summary();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> get(@PathVariable long id) {
        return alerts.get(id);
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize(Roles.WRITE)
    public void acknowledge(@PathVariable long id, @Valid @RequestBody NoteRequest req) {
        alerts.acknowledge(id, req.note());
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize(Roles.MANAGE)
    public void assign(@PathVariable long id, @Valid @RequestBody AssignRequest req) {
        alerts.assign(id, req.userId(), req.note());
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize(Roles.WRITE)
    public void note(@PathVariable long id, @Valid @RequestBody NoteRequest req) {
        alerts.note(id, req.note());
    }

    @PostMapping("/{id}/status")
    @PreAuthorize(Roles.MANAGE)
    public void status(@PathVariable long id, @Valid @RequestBody StatusRequest req) {
        alerts.setStatus(id, req.status(), req.note());
    }

    @PostMapping("/run")
    @PreAuthorize(Roles.MANAGE)
    public Map<String, Integer> run() {
        return engine.run();
    }
}
