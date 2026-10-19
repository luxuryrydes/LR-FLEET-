package com.lrtaxi.fleet.fuel;

import com.lrtaxi.fleet.calc.FuelAmounts;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.reports.Exporter;
import com.lrtaxi.fleet.security.Roles;
import com.lrtaxi.fleet.settings.SettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fuel")
public class FuelController {

    private final FuelService fuel;
    private final SettingsService settings;

    public FuelController(FuelService fuel, SettingsService settings) {
        this.fuel = fuel;
        this.settings = settings;
    }

    public record VoidRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping
    @PreAuthorize(Roles.READ)
    public PageResult<Map<String, Object>> list(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String energyType,
                                                @RequestParam(required = false) Boolean flagged,
                                                @RequestParam(defaultValue = "false") boolean includeVoid,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String sort) {
        filter.validate();
        return fuel.list(filter, energyType, flagged, includeVoid, page, size, sort);
    }

    @GetMapping("/export")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<byte[]> export(@ModelAttribute FleetFilter filter, @RequestParam(required = false) String energyType,
                                         @RequestParam(required = false) Boolean flagged, @RequestParam(defaultValue = "xlsx") String format) {
        filter.validate();
        return Exporter.respond("fuel_transactions", format, COLUMNS, fuel.listForExport(filter, energyType, flagged));
    }

    static final List<Exporter.Col> COLUMNS = List.of(
            Exporter.date("Date", "txnDate"), Exporter.col("Vehicle", "registrationNumber"), Exporter.col("Energy", "energyType"),
            Exporter.num("Quantity", "quantity"), Exporter.col("Unit", "unit"), Exporter.num("Unit price", "unitPrice"),
            Exporter.money("Gross", "grossAmount"), Exporter.money("Discount", "discountAmount"), Exporter.money("Tax", "taxAmount"),
            Exporter.money("Invoice amount", "invoiceAmount"), Exporter.money("Calculated", "calculatedAmount"),
            Exporter.money("Expense", "expenseAmount"), Exporter.col("Amount source", "amountSource"),
            Exporter.col("Amount mismatch", "amountMismatch"), Exporter.num("Odometer", "odometerKm"),
            Exporter.col("Full tank", "isFullTank"), Exporter.col("Station", "stationName"), Exporter.col("Invoice no", "invoiceNumber"),
            Exporter.col("Transaction no", "transactionRef"), Exporter.col("Payment", "paymentMethod"),
            Exporter.col("Quantity flag", "quantityFlag"), Exporter.col("Source", "source"), Exporter.col("Notes", "notes"));

    @GetMapping("/{id}")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> get(@PathVariable long id) {
        return fuel.get(id);
    }

    @PostMapping
    @PreAuthorize(Roles.WRITE)
    public FuelService.SaveResult create(@Valid @RequestBody FuelInput in) {
        return fuel.createManual(in);
    }

    /** Server-side amount preview so the form shows exactly what will be saved. Nothing is written. */
    @PostMapping("/preview-amount")
    @PreAuthorize(Roles.WRITE)
    public FuelAmounts.Resolution preview(@RequestBody FuelInput in) {
        return FuelAmounts.resolve(new FuelAmounts.Input(in.quantity(), in.unitPrice(), in.grossAmount(), in.discountAmount(),
                in.taxAmount(), in.invoiceAmount()), settings.decimal("fuel.amount_tolerance", "1.00"));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize(Roles.MANAGE)
    public void voidTxn(@PathVariable long id, @Valid @RequestBody VoidRequest req) {
        fuel.voidTransaction(id, req.reason());
    }
}
