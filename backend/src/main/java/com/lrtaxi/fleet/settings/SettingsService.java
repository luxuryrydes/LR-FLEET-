package com.lrtaxi.fleet.settings;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.EnergyType;
import com.lrtaxi.fleet.calc.MileageEngine;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Configurable business-rule thresholds (app_settings), cached in memory and refreshed on change. */
@Service
public class SettingsService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt;

    public SettingsService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private Map<String, String> all() {
        if (cache.isEmpty() || System.currentTimeMillis() - loadedAt > 60_000) {
            reload();
        }
        return cache;
    }

    public synchronized void reload() {
        Map<String, String> fresh = new ConcurrentHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM app_settings",
                rs -> {
                    fresh.put(rs.getString(1), rs.getString(2));
                });
        cache.clear();
        cache.putAll(fresh);
        loadedAt = System.currentTimeMillis();
    }

    public String string(String key, String def) {
        return all().getOrDefault(key, def);
    }

    public int integer(String key, int def) {
        String v = all().get(key);
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public BigDecimal decimal(String key, String def) {
        String v = all().get(key);
        try {
            return new BigDecimal(v == null ? def : v.trim());
        } catch (NumberFormatException e) {
            return new BigDecimal(def);
        }
    }

    public List<String> list(String key, String def) {
        return Arrays.stream(string(key, def).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public MileageEngine.Policy mileagePolicy() {
        Map<EnergyType, MileageEngine.Range> ranges = new EnumMap<>(EnergyType.class);
        for (EnergyType e : EnergyType.values()) {
            ranges.put(e, new MileageEngine.Range(decimal("mileage.min." + e, "0"), decimal("mileage.max." + e, "1000")));
        }
        MileageEngine.AccountingPolicy acc = "FULL_TANK_ONLY".equals(string("fuel.accounting_policy", "AUTO"))
                ? MileageEngine.AccountingPolicy.FULL_TANK_ONLY : MileageEngine.AccountingPolicy.AUTO;
        boolean attribution = !"NONE".equals(string("fuel.bifuel_attribution", "NO_OTHER_FUEL_IN_INTERVAL"));
        return new MileageEngine.Policy(acc, attribution, ranges);
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.query("SELECT setting_key, setting_value, value_type, description, updated_at FROM app_settings ORDER BY setting_key", Rows::map);
    }

    @Transactional
    public void update(String key, String value) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT setting_value, value_type FROM app_settings WHERE setting_key = ?", key);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Setting " + key);
        }
        String type = (String) rows.get(0).get("value_type");
        String v = value == null ? "" : value.trim();
        validate(key, type, v);
        jdbc.update("UPDATE app_settings SET setting_value = ?, updated_by = ?, updated_at = now() WHERE setting_key = ?",
                v, CurrentUser.idOrNull(), key);
        audit.record("UPDATE", "SETTING", key, Map.of("value", rows.get(0).get("setting_value")), Map.of("value", v));
        reload();
    }

    private static void validate(String key, String type, String v) {
        try {
            switch (type) {
                case "INTEGER" -> {
                    if (Integer.parseInt(v) < 0) {
                        throw new NumberFormatException();
                    }
                }
                case "DECIMAL" -> {
                    if (new BigDecimal(v).signum() < 0) {
                        throw new NumberFormatException();
                    }
                }
                case "BOOLEAN" -> {
                    if (!v.equals("true") && !v.equals("false")) {
                        throw new NumberFormatException();
                    }
                }
                case "LIST" -> {
                    if (v.isEmpty()) {
                        throw new NumberFormatException();
                    }
                }
                default -> {
                    if (key.equals("fuel.accounting_policy") && !List.of("AUTO", "FULL_TANK_ONLY").contains(v)) {
                        throw new NumberFormatException();
                    }
                    if (key.equals("fuel.bifuel_attribution") && !List.of("NO_OTHER_FUEL_IN_INTERVAL", "NONE").contains(v)) {
                        throw new NumberFormatException();
                    }
                }
            }
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_SETTING", "Invalid value '" + v + "' for " + key + " (" + type + ")");
        }
    }
}
