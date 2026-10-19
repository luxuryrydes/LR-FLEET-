package com.lrtaxi.fleet.settings;

import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.Roles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SettingsController {

    private final SettingsService settings;
    private final JdbcTemplate jdbc;

    public SettingsController(SettingsService settings, JdbcTemplate jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    public record SettingUpdate(String value) {
    }

    @GetMapping("/settings")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> list() {
        return settings.listAll();
    }

    @PutMapping("/settings/{key}")
    @PreAuthorize(Roles.ADMIN)
    public void update(@PathVariable String key, @RequestBody SettingUpdate body) {
        settings.update(key, body.value());
    }

    /** Lookup lists for dropdowns. */
    @GetMapping("/lookups")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> lookups() {
        return Map.of(
                "vehicleCategories", jdbc.query("SELECT code, name FROM vehicle_categories ORDER BY name", Rows::map),
                "serviceCategories", jdbc.query("SELECT code, name, resets_service_interval FROM service_categories WHERE is_active ORDER BY name", Rows::map),
                "departments", jdbc.query("SELECT id, code, name FROM departments WHERE is_active ORDER BY name", Rows::map),
                "vendors", jdbc.query("SELECT id, name, vendor_type FROM vendors WHERE is_active ORDER BY name", Rows::map),
                "drivers", jdbc.query("SELECT id, full_name FROM drivers WHERE is_active ORDER BY full_name", Rows::map),
                "fuelTypes", List.of("PETROL", "DIESEL", "CNG", "CNG_PETROL", "ELECTRIC", "HYBRID", "PLUGIN_HYBRID"),
                "energyTypes", List.of("PETROL", "DIESEL", "CNG", "ELECTRICITY"),
                "ownershipTypes", List.of("OWNED", "LEASED", "VENDOR_ATTACHED", "RENTED"),
                "vehicleStatuses", List.of("ACTIVE", "INACTIVE", "IDLE", "WORKSHOP", "SOLD", "SCRAPPED"),
                "documentTypes", List.of("INSURANCE", "FITNESS", "PUC", "PERMIT", "REGISTRATION", "ROAD_TAX", "DRIVING_LICENCE", "OTHER"));
    }
}
