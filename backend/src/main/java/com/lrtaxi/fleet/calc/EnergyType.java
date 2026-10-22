package com.lrtaxi.fleet.calc;

/** Purchasable energy. Each type has exactly one quantity unit; types are never mixed in a denominator. */
public enum EnergyType {
    PETROL("L", "km/L"),
    DIESEL("L", "km/L"),
    CNG("KG", "km/kg"),
    ELECTRICITY("KWH", "km/kWh");

    private final String unit;
    private final String efficiencyUnit;

    EnergyType(String unit, String efficiencyUnit) {
        this.unit = unit;
        this.efficiencyUnit = efficiencyUnit;
    }

    public String unit() {
        return unit;
    }

    public String efficiencyUnit() {
        return efficiencyUnit;
    }

    /**
     * Accepts spreadsheet and fuel-company spellings: "Petrol", "MS", "HSD", "CNG", "EV", and statement names such as
     * "AUTO CNG - BPCL", "PETROL - BPCL", "XtraPremium", "Hi Speed Diesel". Returns null if unknown
     * (spec/energy-cases.json).
     */
    public static EnergyType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase().replaceAll("[^A-Z]", "");
        EnergyType exact = switch (s) {
            case "PETROL", "MS", "GASOLINE", "PET" -> PETROL;
            case "DIESEL", "HSD", "DSL" -> DIESEL;
            case "CNG", "GAS" -> CNG;
            case "ELECTRICITY", "ELECTRIC", "EV", "KWH", "CHARGING" -> ELECTRICITY;
            default -> null;
        };
        if (exact != null) {
            return exact;
        }
        // Brand / statement names: check the most specific words first.
        if (s.contains("CNG")) {
            return CNG;
        }
        if (s.contains("DIESEL") || s.contains("HSD") || s.contains("XTRAMILE") || s.contains("TURBOJET")) {
            return DIESEL;
        }
        if (s.contains("PETROL") || s.contains("XTRAPREMIUM") || s.startsWith("SPEED") || s.contains("GASOLINE")) {
            return PETROL;
        }
        if (s.contains("ELECTRIC") || s.contains("CHARGING") || s.contains("KWH") || s.startsWith("EV")) {
            return ELECTRICITY;
        }
        return null;
    }
}
