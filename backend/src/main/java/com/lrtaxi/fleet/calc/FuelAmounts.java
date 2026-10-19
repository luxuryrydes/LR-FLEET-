package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;

/**
 * Resolves the expense amount of a fuel transaction. The invoice amount is authoritative and is never replaced
 * by a calculated figure; differences are flagged. See docs/CALCULATION_RULES.md section 5.
 */
public final class FuelAmounts {

    public enum Source { INVOICE, GROSS_LESS_DISCOUNT, CALCULATED }

    public record Input(BigDecimal quantity, BigDecimal unitPrice, BigDecimal grossAmount,
                        BigDecimal discount, BigDecimal tax, BigDecimal invoiceAmount) {
    }

    public record Resolution(BigDecimal unitPrice, boolean unitPriceDerived, BigDecimal calculatedAmount,
                             BigDecimal expenseAmount, Source source, boolean mismatch, BigDecimal difference) {
    }

    private FuelAmounts() {
    }

    /**
     * @return resolution, or throws IllegalArgumentException when no amount can be determined or values are negative
     */
    public static Resolution resolve(Input in, BigDecimal tolerance) {
        if (!Calc.isPositive(in.quantity())) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        requireNonNegative(in.unitPrice(), "Unit price");
        requireNonNegative(in.grossAmount(), "Gross amount");
        requireNonNegative(in.discount(), "Discount");
        requireNonNegative(in.tax(), "Tax");
        requireNonNegative(in.invoiceAmount(), "Invoice amount");

        BigDecimal discount = Calc.nz(in.discount());
        BigDecimal tax = Calc.nz(in.tax());

        BigDecimal unitPrice = in.unitPrice();
        boolean derived = false;
        if (unitPrice == null && in.grossAmount() != null) {
            unitPrice = Calc.ratio(in.grossAmount(), in.quantity(), Calc.UNIT_PRICE_SCALE).orElse(null);
            derived = unitPrice != null;
        }

        BigDecimal calculated = in.unitPrice() == null ? null : Calc.money(in.quantity().multiply(in.unitPrice()));

        BigDecimal expense;
        Source source;
        if (in.invoiceAmount() != null) {
            expense = Calc.money(in.invoiceAmount());
            source = Source.INVOICE;
        } else if (in.grossAmount() != null) {
            expense = Calc.money(in.grossAmount().subtract(discount).add(tax));
            source = Source.GROSS_LESS_DISCOUNT;
        } else if (calculated != null) {
            expense = Calc.money(calculated.subtract(discount).add(tax));
            source = Source.CALCULATED;
        } else {
            throw new IllegalArgumentException("No amount: provide invoice amount, gross value or unit price");
        }
        if (expense.signum() < 0) {
            throw new IllegalArgumentException("Discount exceeds the transaction value");
        }

        // Cross-check every independent figure we have against the expense used.
        boolean mismatch = false;
        BigDecimal difference = null;
        BigDecimal tol = Calc.nz(tolerance);
        BigDecimal[] references = {
                in.grossAmount() == null ? null : in.grossAmount().subtract(discount).add(tax),
                calculated == null ? null : calculated.subtract(discount).add(tax)
        };
        for (BigDecimal ref : references) {
            if (ref == null) {
                continue;
            }
            BigDecimal diff = Calc.money(expense.subtract(ref));
            if (diff.abs().compareTo(tol) > 0) {
                mismatch = true;
                difference = diff;
                break;
            }
        }
        return new Resolution(unitPrice, derived, calculated, expense, source, mismatch, difference);
    }

    private static void requireNonNegative(BigDecimal v, String name) {
        if (v != null && v.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
