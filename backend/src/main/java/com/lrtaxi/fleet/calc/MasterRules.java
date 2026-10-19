package com.lrtaxi.fleet.calc;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vehicle master business rules (docs/CALCULATION_RULES.md section 13), shared with the Python preview through
 * spec/master-cases.json:
 * <ul>
 *   <li>Workshop / accidental vehicles are WORKSHOP.</li>
 *   <li>Vehicles without a driver are IDLE.</li>
 *   <li>Only vehicles driven by LR's own drivers have their GPS km imported.</li>
 *   <li>Vendor vehicles are VENDOR_ATTACHED.</li>
 * </ul>
 */
public final class MasterRules {

    public enum OperatorType { OWN_DRIVER, VENDOR, GOVT_DRIVER, OFFICER, PERSONAL, NONE }

    public record Derived(OperatorType operatorType, String status, String ownership, boolean gpsKmTracked) {
    }

    public record ModelInfo(String make, String model, String category) {
    }

    private MasterRules() {
    }

    private static String key(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    /** From the sheet's "Vendor / Driver" label and the driver/vendor name. */
    public static Derived derive(String operatorLabel, String name) {
        String k = key(operatorLabel);
        boolean hasName = name != null && !name.isBlank();
        if (k.contains("SOLD")) {
            return new Derived(OperatorType.NONE, "SOLD", "OWNED", false);
        }
        if (k.contains("SCRAP")) {
            return new Derived(OperatorType.NONE, "SCRAPPED", "OWNED", false);
        }
        if (k.startsWith("WORKSHOP") || k.contains("ACCIDENT")) {
            return new Derived(OperatorType.NONE, "WORKSHOP", "OWNED", false);
        }
        String active = hasName ? "ACTIVE" : "IDLE";
        return switch (k) {
            case "DRIVER", "OWN DRIVER", "LR DRIVER" -> new Derived(OperatorType.OWN_DRIVER, active, "OWNED", hasName);
            case "VENDOR" -> new Derived(OperatorType.VENDOR, active, "VENDOR_ATTACHED", false);
            case "GOVT DRIVER", "GOVERNMENT DRIVER" -> new Derived(OperatorType.GOVT_DRIVER, active, "OWNED", false);
            case "OFFICER" -> new Derived(OperatorType.OFFICER, "ACTIVE", "OWNED", false);
            case "PERSONAL" -> new Derived(OperatorType.PERSONAL, "ACTIVE", "OWNED", false);
            case "" -> hasName ? new Derived(OperatorType.OWN_DRIVER, "ACTIVE", "OWNED", true)
                    : new Derived(OperatorType.NONE, "IDLE", "OWNED", false);
            default -> new Derived(OperatorType.NONE, "IDLE", "OWNED", false);
        };
    }

    /** "CNG / PETROL" -> CNG_PETROL etc. Null when unknown. */
    public static String fuelType(String raw) {
        String k = key(raw).replace(" ", "");
        return switch (k) {
            case "CNGPETROL", "PETROLCNG", "BIFUEL" -> "CNG_PETROL";
            case "ELECTRIC", "ELECTRICAL", "EV", "BEV" -> "ELECTRIC";
            case "DIESEL" -> "DIESEL";
            case "PETROL" -> "PETROL";
            case "HYBRIDPETROL", "HYBRID", "STRONGHYBRID", "PETROLHYBRID" -> "HYBRID";
            case "PLUGINHYBRID", "PHEV" -> "PLUGIN_HYBRID";
            case "CNG" -> "CNG";
            default -> null;
        };
    }

    private static final Map<String, String> BRANDS = Map.of(
            "TATA", "Tata", "TOYOTA", "Toyota", "MAHINDRA", "Mahindra", "HONDA", "Honda", "HYUNDAI", "Hyundai",
            "KIA", "Kia", "MG", "MG", "MARUTI", "Maruti Suzuki", "SUZUKI", "Maruti Suzuki");

    private static final Map<String, String> MODEL_MAKE = Map.ofEntries(
            Map.entry("CIAZ", "Maruti Suzuki"), Map.entry("DZIRE", "Maruti Suzuki"), Map.entry("ERTIGA", "Maruti Suzuki"),
            Map.entry("XL6", "Maruti Suzuki"), Map.entry("ALTO", "Maruti Suzuki"), Map.entry("EECO", "Maruti Suzuki"),
            Map.entry("GRAND", "Maruti Suzuki"), Map.entry("INVICTO", "Maruti Suzuki"), Map.entry("SWIFT", "Maruti Suzuki"),
            Map.entry("BALENO", "Maruti Suzuki"), Map.entry("WAGONR", "Maruti Suzuki"),
            Map.entry("INNOVA", "Toyota"), Map.entry("RUMION", "Toyota"), Map.entry("HYRYDER", "Toyota"), Map.entry("COROLLA", "Toyota"),
            Map.entry("FORTUNER", "Toyota"), Map.entry("ETIOS", "Toyota"), Map.entry("GLANZA", "Toyota"),
            Map.entry("BOLERO", "Mahindra"), Map.entry("SCORPIO", "Mahindra"), Map.entry("XUV", "Mahindra"), Map.entry("THAR", "Mahindra"),
            Map.entry("XEV", "Mahindra"),
            Map.entry("TIGOR", "Tata"), Map.entry("NEXON", "Tata"), Map.entry("HARRIER", "Tata"), Map.entry("PUNCH", "Tata"),
            Map.entry("TIAGO", "Tata"),
            Map.entry("VERNA", "Hyundai"), Map.entry("CRETA", "Hyundai"), Map.entry("AURA", "Hyundai"),
            Map.entry("CITY", "Honda"), Map.entry("AMAZE", "Honda"),
            Map.entry("SELTOS", "Kia"), Map.entry("CARENS", "Kia"), Map.entry("SONET", "Kia"));

    private static final List<Map.Entry<String, Set<String>>> CATEGORY = List.of(
            Map.entry("LCV", Set.of("CAMPER", "PICKUP")),
            Map.entry("SUV", Set.of("NEXON", "HARRIER", "HYRYDER", "GRAND", "SELTOS", "FORTUNER", "SCORPIO", "XEV", "ZS", "XUV", "THAR",
                    "CRETA", "SONET", "PUNCH")),
            Map.entry("MUV", Set.of("ERTIGA", "XL6", "RUMION", "INNOVA", "INVICTO", "CARENS", "BOLERO", "EECO")),
            Map.entry("SEDAN", Set.of("CIAZ", "DZIRE", "TIGOR", "ELECTRICAL", "VERNA", "CITY", "COROLLA", "AURA", "AMAZE", "ETIOS")),
            Map.entry("HATCHBACK", Set.of("ALTO", "SWIFT", "WAGONR", "BALENO", "TIAGO", "GLANZA")));

    private static final Set<String> KEEP_UPPER = Set.of("EV", "XM", "XZ", "XE", "XEV", "B4", "B6", "B8", "9E", "ZS", "XL6", "EECO", "MG",
            "XUV", "CNG", "AMT", "AT", "MT", "VX", "ZX", "GX", "VXI", "ZXI", "LXI");

    /** "TATA NEXON EV XM" -> (Tata, "Nexon EV XM", SUV). */
    public static ModelInfo model(String raw) {
        List<String> words = Arrays.stream((raw == null ? "" : raw.trim()).split("\\s+")).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty()) {
            return new ModelInfo("Other", "Unknown", "OTHER");
        }
        String make = null;
        List<String> rest = words;
        String first = words.get(0).toUpperCase(Locale.ROOT);
        if (BRANDS.containsKey(first) && words.size() > 1) {
            make = BRANDS.get(first);
            rest = words.subList(1, words.size());
        }
        String modelKey = rest.get(0).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (make == null) {
            make = MODEL_MAKE.getOrDefault(modelKey, "Other");
        }
        String model = rest.stream().map(MasterRules::word).collect(Collectors.joining(" "));
        Set<String> tokens = rest.stream().map(w -> w.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "")).collect(Collectors.toSet());
        String category = "OTHER";
        for (Map.Entry<String, Set<String>> e : CATEGORY) {
            if (tokens.stream().anyMatch(e.getValue()::contains)) {
                category = e.getKey();
                break;
            }
        }
        return new ModelInfo(make, model, category);
    }

    private static String word(String w) {
        String u = w.toUpperCase(Locale.ROOT);
        if (KEEP_UPPER.contains(u.replaceAll("[^A-Z0-9]", "")) || u.chars().anyMatch(Character::isDigit)) {
            return u;
        }
        return u.charAt(0) + u.substring(1).toLowerCase(Locale.ROOT);
    }
}
