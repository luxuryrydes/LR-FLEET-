package com.lrtaxi.fleet.calc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registration-number rules shared with the Python worker and the frontend
 * (see spec/registration-cases.json and docs/CALCULATION_RULES.md section 3).
 */
public final class RegistrationNormalizer {

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern PLATE = Pattern.compile("(?<!\\d)(?:\\d{2}BH\\d{4}[A-Z]{1,2}|[A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{4}(?!\\d))");

    private RegistrationNormalizer() {
    }

    /** Uppercase and strip everything except A-Z and 0-9. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return NON_ALNUM.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
    }

    /** Extracts an Indian registration plate from a GPS device name such as "DL1RT1234 (CNG)". Null if none. */
    public static String extractPlate(String raw) {
        String n = normalize(raw);
        if (n.isEmpty()) {
            return null;
        }
        Matcher m = PLATE.matcher(n);
        return m.find() ? m.group() : null;
    }
}
