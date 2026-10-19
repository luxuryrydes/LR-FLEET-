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

    /** Accepts common spreadsheet spellings: "Petrol", "MS", "HSD", "CNG", "EV", "Electric". Returns null if unknown. */
    public static EnergyType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase().replaceAll("[^A-Z]", "");
        return switch (s) {
            case "PETROL", "MS", "GASOLINE", "PET" -> PETROL;
            case "DIESEL", "HSD", "DSL" -> DIESEL;
            case "CNG", "GAS" -> CNG;
            case "ELECTRICITY", "ELECTRIC", "EV", "KWH", "POWER", "CHARGING" -> ELECTRICITY;
            default -> null;
        };
    }
}
