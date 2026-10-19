package com.lrtaxi.fleet.fuel;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** A fuel / charging transaction from manual entry or an import row. */
public record FuelInput(
        @NotNull Long vehicleId,
        @NotNull LocalDate txnDate,
        LocalTime txnTime,
        @NotBlank @Pattern(regexp = "PETROL|DIESEL|CNG|ELECTRICITY") String energyType,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
        @DecimalMin("0") BigDecimal unitPrice,
        @DecimalMin("0") BigDecimal grossAmount,
        @DecimalMin("0") BigDecimal discountAmount,
        @DecimalMin("0") BigDecimal taxAmount,
        @DecimalMin("0") BigDecimal invoiceAmount,
        @DecimalMin("0") BigDecimal odometerKm,
        Boolean isFullTank,
        @Size(max = 200) String stationName,
        Long vendorId,
        @Size(max = 80) String invoiceNumber,
        @Size(max = 80) String transactionRef,
        @Pattern(regexp = "CASH|CARD|UPI|FUEL_CARD|CREDIT|BANK_TRANSFER|OTHER") String paymentMethod,
        Long attachmentFileId,
        @Size(max = 1000) String notes) {
}
