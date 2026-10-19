package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Indian presentation formats: ₹ with lakh/crore grouping and DD-MM-YYYY dates. */
public final class IndianFormat {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private IndianFormat() {
    }

    public static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    /** 1234567.891 -> "₹12,34,567.89". */
    public static String rupees(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        BigDecimal rounded = Calc.money(amount);
        boolean negative = rounded.signum() < 0;
        String plain = rounded.abs().toPlainString();
        int dot = plain.indexOf('.');
        String intPart = plain.substring(0, dot);
        String frac = plain.substring(dot + 1);
        return (negative ? "-₹" : "₹") + groupIndian(intPart) + "." + frac;
    }

    /** Groups digits as 12,34,56,789 (last three, then pairs). */
    public static String groupIndian(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder sb = new StringBuilder();
        int firstGroup = rest.length() % 2;
        if (firstGroup > 0) {
            sb.append(rest, 0, firstGroup);
        }
        for (int i = firstGroup; i < rest.length(); i += 2) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(rest, i, i + 2);
        }
        return sb + "," + last3;
    }
}
