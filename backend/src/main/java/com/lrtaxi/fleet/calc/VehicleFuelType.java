package com.lrtaxi.fleet.calc;

import java.util.EnumSet;
import java.util.Set;

/** Vehicle powertrain and the energy types it is allowed to purchase. */
public enum VehicleFuelType {
    PETROL(EnumSet.of(EnergyType.PETROL), EnergyType.PETROL),
    DIESEL(EnumSet.of(EnergyType.DIESEL), EnergyType.DIESEL),
    CNG(EnumSet.of(EnergyType.CNG), EnergyType.CNG),
    CNG_PETROL(EnumSet.of(EnergyType.CNG, EnergyType.PETROL), EnergyType.CNG),
    ELECTRIC(EnumSet.of(EnergyType.ELECTRICITY), EnergyType.ELECTRICITY),
    HYBRID(EnumSet.of(EnergyType.PETROL), EnergyType.PETROL),
    PLUGIN_HYBRID(EnumSet.of(EnergyType.PETROL, EnergyType.ELECTRICITY), EnergyType.PETROL);

    private final Set<EnergyType> allowed;
    private final EnergyType primary;

    VehicleFuelType(Set<EnergyType> allowed, EnergyType primary) {
        this.allowed = allowed;
        this.primary = primary;
    }

    public Set<EnergyType> allowedEnergy() {
        return EnumSet.copyOf(allowed);
    }

    public boolean allows(EnergyType energy) {
        return allowed.contains(energy);
    }

    /** Energy that the vehicle-level expected mileage refers to. */
    public EnergyType primaryEnergy() {
        return primary;
    }

    public boolean isMultiEnergy() {
        return allowed.size() > 1;
    }
}
