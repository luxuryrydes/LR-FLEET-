package com.lrtaxi.fleet.calc;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vehicle document rules (docs/CALCULATION_RULES.md section 15): which documents a vehicle must hold and the status of
 * the current one. The current document of a type is the non-void, non-rejected one with the latest expiry.
 */
public final class DocumentRules {

    /** Vehicle document columns in display order, with their labels. */
    public static final Map<String, String> VEHICLE_TYPES = types();

    /** Commercial (taxi) vehicles also need fitness and both permits. */
    public static final List<String> COMMERCIAL_EXTRA = List.of("FITNESS", "PERMIT", "PERMIT_1Y");

    public enum Status { VALID, DUE_SOON, EXPIRED, NOT_REQUIRED, NO_EXPIRY, MISSING, OPTIONAL }

    private DocumentRules() {
    }

    private static Map<String, String> types() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("INSURANCE", "Insurance");
        m.put("INSURANCE_OD", "Own damage insurance");
        m.put("PUC", "Pollution (PUC)");
        m.put("FITNESS", "Fitness");
        m.put("ROAD_TAX", "Road tax");
        m.put("PERMIT", "Permit 5 years");
        m.put("PERMIT_1Y", "Permit 1 year");
        m.put("REGISTRATION", "Registration (RC)");
        return m;
    }

    /** @param base the docs.required_types setting (every vehicle); commercial vehicles add {@link #COMMERCIAL_EXTRA}. */
    public static List<String> required(String registrationClass, List<String> base) {
        List<String> out = new ArrayList<>(base);
        if ("COMMERCIAL".equals(registrationClass)) {
            for (String t : COMMERCIAL_EXTRA) {
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * @param exists        a current document of this type exists
     * @param notApplicable it is recorded as not required (NA / Not Required / lifetime tax)
     * @param expiry        its expiry date (null = no expiry recorded)
     * @param required      the vehicle must hold this type
     * @param windowDays    reminder window: expiring within this many days = DUE_SOON
     */
    public static Status status(boolean exists, boolean notApplicable, LocalDate expiry, boolean required, LocalDate today, int windowDays) {
        if (!exists) {
            return required ? Status.MISSING : Status.OPTIONAL;
        }
        if (notApplicable) {
            return Status.NOT_REQUIRED;
        }
        if (expiry == null) {
            return Status.NO_EXPIRY;
        }
        long days = ChronoUnit.DAYS.between(today, expiry);
        if (days < 0) {
            return Status.EXPIRED;
        }
        return days <= windowDays ? Status.DUE_SOON : Status.VALID;
    }
}
