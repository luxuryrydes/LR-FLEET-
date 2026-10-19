package com.lrtaxi.fleet.fuel;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.EnergyType;
import com.lrtaxi.fleet.calc.FuelAmounts;
import com.lrtaxi.fleet.calc.Odometer;
import com.lrtaxi.fleet.calc.VehicleFuelType;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.common.Paging;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.odometer.OdometerService;
import com.lrtaxi.fleet.security.CurrentUser;
import com.lrtaxi.fleet.settings.SettingsService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FuelService {

    /** Outcome of saving one transaction. status: INSERTED or DUPLICATE (identical transaction already saved). */
    public record SaveResult(Long id, String status, List<String> warnings) {
    }

    static final String SELECT = """
            SELECT f.id, f.vehicle_id, v.registration_number, v.fuel_type AS vehicle_fuel_type, f.txn_date, f.txn_time, f.energy_type,
                   f.unit, f.quantity, f.unit_price, f.unit_price_derived, f.gross_amount, f.discount_amount, f.tax_amount, f.invoice_amount,
                   f.calculated_amount, f.expense_amount, f.amount_source, f.amount_mismatch, f.amount_difference, f.odometer_km,
                   f.is_full_tank, f.station_name, f.vendor_id, vd.name AS vendor_name, f.invoice_number, f.transaction_ref,
                   f.payment_method, f.attachment_file_id, f.notes, f.quantity_flag, f.source, f.import_batch_id, f.import_row_number,
                   f.is_void, f.void_reason, f.voided_at, f.created_at,
                   o.is_trusted AS odometer_trusted, o.untrusted_reason AS odometer_issue
            FROM fuel_transactions f
            JOIN vehicles v ON v.id = f.vehicle_id
            LEFT JOIN vendors vd ON vd.id = f.vendor_id
            LEFT JOIN odometer_readings o ON o.source = 'FUEL' AND o.source_ref_id = f.id AND NOT o.is_void
            """;

    static final Map<String, String> SORTABLE = Map.of(
            "txnDate", "f.txn_date", "registrationNumber", "v.registration_normalized", "energyType", "f.energy_type",
            "quantity", "f.quantity", "expenseAmount", "f.expense_amount", "createdAt", "f.created_at");

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final OdometerService odometer;
    private final EfficiencyService efficiency;
    private final AuditService audit;

    public FuelService(NamedParameterJdbcTemplate jdbc, SettingsService settings, OdometerService odometer,
                       EfficiencyService efficiency, AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.odometer = odometer;
        this.efficiency = efficiency;
        this.audit = audit;
    }

    public PageResult<Map<String, Object>> list(FleetFilter f, String energy, Boolean flagged, boolean includeVoid,
                                                Integer page, Integer size, String sort) {
        Paging paging = Paging.of(page, size, sort, SORTABLE, "f.txn_date DESC, f.id DESC");
        MapSqlParameterSource p = f.dateParams();
        StringBuilder where = where(f, energy, flagged, includeVoid, p);
        Long total = jdbc.queryForObject("SELECT count(*) FROM fuel_transactions f JOIN vehicles v ON v.id = f.vehicle_id" + where, p, Long.class);
        List<Map<String, Object>> items = jdbc.query(SELECT + where + paging.sql(), p, Rows::map);
        return new PageResult<>(items, total == null ? 0 : total, paging.page(), paging.size());
    }

    public List<Map<String, Object>> listForExport(FleetFilter f, String energy, Boolean flagged) {
        MapSqlParameterSource p = f.dateParams();
        return jdbc.query(SELECT + where(f, energy, flagged, false, p) + " ORDER BY f.txn_date, v.registration_normalized LIMIT 500000", p, Rows::map);
    }

    private static StringBuilder where(FleetFilter f, String energy, Boolean flagged, boolean includeVoid, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE f.txn_date BETWEEN :from AND :to");
        if (!includeVoid) {
            w.append(" AND NOT f.is_void");
        }
        if (energy != null && !energy.isBlank()) {
            w.append(" AND f.energy_type = :energy");
            p.addValue("energy", energy);
        }
        if (Boolean.TRUE.equals(flagged)) {
            w.append(" AND (f.amount_mismatch OR f.quantity_flag IS NOT NULL)");
        }
        f.appendVehicleConditions(w, p, "v");
        return w;
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.query(SELECT + " WHERE f.id = :id", Map.of("id", id), Rows::map);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Fuel transaction");
        }
        return rows.get(0);
    }

    /** Manual entry: validates, saves, recalculates the vehicle's efficiency intervals. */
    @Transactional
    public SaveResult createManual(FuelInput in) {
        SaveResult r = save(in, "MANUAL", null, null);
        if ("DUPLICATE".equals(r.status())) {
            throw ApiException.conflict("DUPLICATE_TRANSACTION", "An identical fuel transaction is already recorded (id " + r.id() + ")");
        }
        efficiency.recompute(in.vehicleId());
        audit.record("CREATE", "FUEL_TRANSACTION", r.id(), null, get(r.id()));
        return r;
    }

    /**
     * Validates and inserts one transaction (no efficiency recompute; callers batch that).
     * Throws ApiException for rejected rows so import can record the reason.
     */
    public SaveResult save(FuelInput in, String source, Long batchId, Integer rowNumber) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> vehicle = vehicle(in.vehicleId());
        EnergyType energy = EnergyType.valueOf(in.energyType());
        VehicleFuelType vft = VehicleFuelType.valueOf((String) vehicle.get("fuel_type"));
        if (!vft.allows(energy)) {
            throw ApiException.badRequest("INCOMPATIBLE_FUEL", energy + " is not valid for a " + vft + " vehicle");
        }
        if (in.txnDate().isAfter(LocalDate.now())) {
            throw ApiException.badRequest("FUTURE_DATE", "Transaction date cannot be in the future");
        }
        if (in.txnDate().isBefore(LocalDate.of(2000, 1, 1))) {
            throw ApiException.badRequest("INVALID_DATE", "Transaction date is too old");
        }
        FuelAmounts.Resolution amt = FuelAmounts.resolve(new FuelAmounts.Input(in.quantity(), in.unitPrice(), in.grossAmount(),
                in.discountAmount(), in.taxAmount(), in.invoiceAmount()), settings.decimal("fuel.amount_tolerance", "1.00"));
        if (amt.mismatch()) {
            warnings.add("Invoice amount differs from quantity × price by ₹" + amt.difference() + "; invoice amount kept");
        }

        String quantityFlag = quantityFlag(energy, in.quantity(), vehicle);
        if (quantityFlag != null) {
            warnings.add("Quantity " + in.quantity() + " " + energy.unit() + " is above the configured limit (" + quantityFlag + ")");
        }

        String invoice = blankToNull(in.invoiceNumber());
        String txnRef = blankToNull(in.transactionRef());
        String dedupe = dedupeKey(in.vehicleId(), in.txnDate(), energy, in.quantity(), amt.expenseAmount(), invoice);

        List<Long> same = jdbc.queryForList("SELECT id FROM fuel_transactions WHERE dedupe_key = :k AND NOT is_void",
                Map.of("k", dedupe), Long.class);
        if (!same.isEmpty()) {
            return new SaveResult(same.get(0), "DUPLICATE", List.of("Identical transaction already recorded"));
        }
        if (txnRef != null) {
            List<Long> ref = jdbc.queryForList("SELECT id FROM fuel_transactions WHERE upper(transaction_ref) = upper(:r) AND NOT is_void",
                    Map.of("r", txnRef), Long.class);
            if (!ref.isEmpty()) {
                throw ApiException.conflict("DUPLICATE_TRANSACTION_REF", "Transaction number " + txnRef + " is already recorded (id " + ref.get(0) + ")");
            }
        }
        if (invoice != null) {
            List<Long> inv = jdbc.queryForList("SELECT id FROM fuel_transactions WHERE upper(invoice_number) = upper(:i) AND NOT is_void",
                    Map.of("i", invoice), Long.class);
            if (!inv.isEmpty()) {
                throw ApiException.conflict("REPEATED_INVOICE", "Invoice " + invoice + " is already used by a different transaction (id "
                        + inv.get(0) + "); review before saving");
            }
        }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("v", in.vehicleId()).addValue("d", in.txnDate()).addValue("t", in.txnTime())
                .addValue("e", energy.name()).addValue("u", energy.unit()).addValue("q", in.quantity())
                .addValue("price", amt.unitPrice()).addValue("derived", amt.unitPriceDerived())
                .addValue("gross", in.grossAmount()).addValue("disc", in.discountAmount() == null ? BigDecimal.ZERO : in.discountAmount())
                .addValue("tax", in.taxAmount() == null ? BigDecimal.ZERO : in.taxAmount()).addValue("inv", in.invoiceAmount())
                .addValue("calc", amt.calculatedAmount()).addValue("exp", amt.expenseAmount()).addValue("src", amt.source().name())
                .addValue("mm", amt.mismatch()).addValue("diff", amt.difference()).addValue("odo", in.odometerKm())
                .addValue("full", in.isFullTank()).addValue("station", blankToNull(in.stationName())).addValue("vendor", in.vendorId())
                .addValue("invNo", invoice).addValue("ref", txnRef).addValue("pay", in.paymentMethod())
                .addValue("file", in.attachmentFileId()).addValue("notes", blankToNull(in.notes())).addValue("qflag", quantityFlag)
                .addValue("source", source).addValue("batch", batchId).addValue("row", rowNumber).addValue("key", dedupe)
                .addValue("uid", CurrentUser.idOrNull());
        Long id = jdbc.queryForObject("""
                INSERT INTO fuel_transactions (vehicle_id, txn_date, txn_time, energy_type, unit, quantity, unit_price, unit_price_derived,
                    gross_amount, discount_amount, tax_amount, invoice_amount, calculated_amount, expense_amount, amount_source,
                    amount_mismatch, amount_difference, odometer_km, is_full_tank, station_name, vendor_id, invoice_number, transaction_ref,
                    payment_method, attachment_file_id, notes, quantity_flag, source, import_batch_id, import_row_number, dedupe_key,
                    created_by, updated_by)
                VALUES (:v, :d, :t, :e, :u, :q, :price, :derived, :gross, :disc, :tax, :inv, :calc, :exp, :src, :mm, :diff, :odo, :full,
                    :station, :vendor, :invNo, :ref, :pay, :file, :notes, :qflag, :source, :batch, :row, :key, :uid, :uid)
                RETURNING id""", p, Long.class);

        if (in.odometerKm() != null) {
            Odometer.Check c = odometer.addReading(in.vehicleId(), in.txnDate(), in.odometerKm(), "FUEL", id);
            if (!c.ok()) {
                warnings.add("Odometer not trusted: " + c.message());
            }
        }
        return new SaveResult(id, "INSERTED", warnings);
    }

    /** Corrections never delete: the transaction is voided with a reason and can be re-entered. */
    @Transactional
    public void voidTransaction(long id, String reason) {
        Map<String, Object> before = get(id);
        if (Boolean.TRUE.equals(before.get("isVoid"))) {
            throw ApiException.badRequest("ALREADY_VOID", "Transaction is already void");
        }
        jdbc.update("UPDATE fuel_transactions SET is_void = TRUE, void_reason = :r, voided_by = :u, voided_at = now(), updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("r", reason).addValue("u", CurrentUser.idOrNull()).addValue("id", id));
        odometer.voidBySource("FUEL", id);
        efficiency.recompute(((Number) before.get("vehicleId")).longValue());
        audit.record("VOID", "FUEL_TRANSACTION", id, before, Map.of("reason", reason));
    }

    private Map<String, Object> vehicle(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, fuel_type, status, tank_capacity, battery_capacity_kwh FROM vehicles WHERE id = :id",
                Map.of("id", id));
        if (rows.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_VEHICLE", "Vehicle not found");
        }
        return rows.get(0);
    }

    private String quantityFlag(EnergyType energy, BigDecimal qty, Map<String, Object> vehicle) {
        BigDecimal capacity = energy == EnergyType.ELECTRICITY ? (BigDecimal) vehicle.get("battery_capacity_kwh")
                : (energy != EnergyType.CNG ? (BigDecimal) vehicle.get("tank_capacity") : null);
        if (capacity != null && qty.compareTo(capacity) > 0) {
            return "EXCEEDS_CAPACITY";
        }
        BigDecimal max = settings.decimal("fuel.max_quantity." + energy.name(), "1000");
        if (qty.compareTo(max) > 0) {
            return "EXCEEDS_MAX_QUANTITY";
        }
        return null;
    }

    static String dedupeKey(long vehicleId, LocalDate date, EnergyType energy, BigDecimal qty, BigDecimal amount, String invoice) {
        String raw = vehicleId + "|" + date + "|" + energy + "|" + qty.stripTrailingZeros().toPlainString() + "|"
                + amount.stripTrailingZeros().toPlainString() + "|" + (invoice == null ? "" : invoice.toUpperCase(Locale.ROOT));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
