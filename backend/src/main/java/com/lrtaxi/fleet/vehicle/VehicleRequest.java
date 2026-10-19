package com.lrtaxi.fleet.vehicle;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record VehicleRequest(
        @NotBlank @Size(max = 20) String registrationNumber,
        @Size(max = 300) String duplicateRegistrationReason,
        @NotBlank @Size(max = 80) String make,
        @NotBlank @Size(max = 80) String model,
        @Min(1980) @Max(2100) Integer modelYear,
        @NotBlank @Pattern(regexp = "PETROL|DIESEL|CNG|CNG_PETROL|ELECTRIC|HYBRID|PLUGIN_HYBRID") String fuelType,
        @NotBlank @Size(max = 30) String categoryCode,
        @NotBlank @Pattern(regexp = "OWNED|LEASED|VENDOR_ATTACHED|RENTED") String ownershipType,
        Long vendorId,
        Long currentDriverId,
        Long currentDepartmentId,
        @Size(max = 200) String customerName,
        @Pattern(regexp = "ACTIVE|INACTIVE|IDLE|WORKSHOP|SOLD|SCRAPPED") String status,
        @DecimalMin(value = "0", inclusive = false) BigDecimal expectedMileage,
        @Positive @Max(100000) Integer serviceIntervalKm,
        @Positive @Max(60) Integer serviceIntervalMonths,
        @DecimalMin(value = "0", inclusive = false) BigDecimal tankCapacity,
        @DecimalMin(value = "0", inclusive = false) BigDecimal batteryCapacityKwh,
        @PastOrPresent LocalDate purchaseDate,
        @DecimalMin("0") BigDecimal purchaseCost,
        @Size(max = 50) String chassisNumber,
        @Size(max = 50) String engineNumber,
        @Size(max = 100) String gpsDeviceName,
        @Size(max = 1000) String remarks,
        @DecimalMin("0") BigDecimal openingOdometerKm,
        @PastOrPresent LocalDate openingOdometerDate,
        @PastOrPresent LocalDate effectiveDate,
        @Size(max = 300) String changeReason) {
}
