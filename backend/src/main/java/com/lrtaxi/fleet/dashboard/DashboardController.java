package com.lrtaxi.fleet.dashboard;

import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/kpis")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> kpis(@ModelAttribute FleetFilter filter) {
        filter.validate();
        return dashboard.kpis(filter);
    }

    @GetMapping("/charts")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> charts(@ModelAttribute FleetFilter filter) {
        filter.validate();
        return dashboard.charts(filter);
    }
}
