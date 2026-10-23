package com.lrtaxi.fleet.calc;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Test
    void commercialVehiclesAlsoNeedFitnessAndBothPermits() {
        List<String> base = List.of("INSURANCE", "PUC");
        assertThat(DocumentRules.required("PRIVATE", base)).containsExactly("INSURANCE", "PUC");
        assertThat(DocumentRules.required(null, base)).containsExactly("INSURANCE", "PUC");
        assertThat(DocumentRules.required("COMMERCIAL", base)).containsExactly("INSURANCE", "PUC", "FITNESS", "PERMIT", "PERMIT_1Y");
        assertThat(DocumentRules.required("COMMERCIAL", List.of("FITNESS"))).containsExactly("FITNESS", "PERMIT", "PERMIT_1Y");
    }

    @Test
    void statusFromEndDate() {
        assertThat(DocumentRules.status(true, false, TODAY.minusDays(1), true, TODAY, 30)).isEqualTo(DocumentRules.Status.EXPIRED);
        assertThat(DocumentRules.status(true, false, TODAY, true, TODAY, 30)).isEqualTo(DocumentRules.Status.DUE_SOON);
        assertThat(DocumentRules.status(true, false, TODAY.plusDays(30), true, TODAY, 30)).isEqualTo(DocumentRules.Status.DUE_SOON);
        assertThat(DocumentRules.status(true, false, TODAY.plusDays(31), true, TODAY, 30)).isEqualTo(DocumentRules.Status.VALID);
        assertThat(DocumentRules.status(true, true, null, true, TODAY, 30)).isEqualTo(DocumentRules.Status.NOT_REQUIRED);
        assertThat(DocumentRules.status(true, false, null, true, TODAY, 30)).isEqualTo(DocumentRules.Status.NO_EXPIRY);
        assertThat(DocumentRules.status(false, false, null, true, TODAY, 30)).isEqualTo(DocumentRules.Status.MISSING);
        assertThat(DocumentRules.status(false, false, null, false, TODAY, 30)).isEqualTo(DocumentRules.Status.OPTIONAL);
    }
}
