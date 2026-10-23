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
        return Map.ofEntries(
                Map.entry("vehicleCategories", jdbc.query("SELECT code, name FROM vehicle_categories ORDER BY name", Rows::map)),
                Map.entry("serviceCategories", jdbc.query("SELECT code, name, resets_service_interval FROM service_categories WHERE is_active ORDER BY name", Rows::map)),
                Map.entry("departments", jdbc.query("SELECT id, code, name FROM departments WHERE is_active ORDER BY name", Rows::map)),
                Map.entry("vendors", jdbc.query("SELECT id, name, vendor_type FROM vendors WHERE is_active ORDER BY name", Rows::map)),
                Map.entry("drivers", jdbc.query("SELECT id, full_name FROM drivers WHERE is_active ORDER BY full_name", Rows::map)),
                Map.entry("fuelTypes", List.of("PETROL", "DIESEL", "CNG", "CNG_PETROL", "ELECTRIC", "HYBRID", "PLUGIN_HYBRID")),
                Map.entry("energyTypes", List.of("PETROL", "DIESEL", "CNG", "ELECTRICITY")),
                Map.entry("ownershipTypes", List.of("OWNED", "LEASED", "VENDOR_ATTACHED", "RENTED")),
                Map.entry("vehicleStatuses", List.of("ACTIVE", "INACTIVE", "IDLE", "WORKSHOP", "SOLD", "SCRAPPED")),
                Map.entry("documentTypes", List.of("INSURANCE", "INSURANCE_OD", "PUC", "FITNESS", "ROAD_TAX", "PERMIT", "PERMIT_1Y", "REGISTRATION",
                        "DRIVING_LICENCE", "OTHER")),
                Map.entry("operatorTypes", List.of("OWN_DRIVER", "VENDOR", "GOVT_DRIVER", "OFFICER", "PERSONAL", "NONE")),
                Map.entry("disposalTypes", List.of("SOLD", "SCRAPPED", "RETURNED_TO_VENDOR", "TRANSFERRED", "OTHER")));
    }
}
