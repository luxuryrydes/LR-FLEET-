package com.lrtaxi.fleet.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Asserts the engine against independently hand-calculated examples shared with the docs. */
class SharedSpecCasesTest {

    static JsonNode calc;
    static JsonNode reg;

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper om = new ObjectMapper();
        calc = om.readTree(locate("calculation-cases.json"));
        reg = om.readTree(locate("registration-cases.json"));
    }

    static File locate(String name) {
        for (String p : new String[]{"../spec/", "spec/"}) {
            File f = new File(p + name);
            if (f.exists()) {
                return f;
            }
        }
        throw new IllegalStateException("spec/" + name + " not found");
    }

    static BigDecimal dec(JsonNode n) {
        return n == null || n.isNull() ? null : new BigDecimal(n.asText());
    }

    static void assertOptional(Optional<BigDecimal> actual, JsonNode expected) {
        if (expected.isNull()) {
            assertThat(actual).isEmpty();
        } else {
            assertThat(actual).hasValueSatisfying(v -> assertThat(v.toPlainString()).isEqualTo(expected.asText()));
        }
    }

    @Test
    void mileageExamples() {
        for (JsonNode c : calc.get("mileage")) {
            assertOptional(Calc.mileage(dec(c.get("km")), dec(c.get("quantity"))), c.get("expected"));
        }
    }

    @Test
    void fleetWeighted() {
        for (JsonNode c : calc.get("fleetWeighted")) {
            List<BigDecimal[]> pairs = new ArrayList<>();
            for (JsonNode i : c.get("intervals")) {
                pairs.add(new BigDecimal[]{dec(i.get("km")), dec(i.get("quantity"))});
            }
            assertOptional(MileageEngine.weightedFleetMileage(pairs), c.get("expected"));
        }
    }

    @Test
    void weightedPrice() {
        for (JsonNode c : calc.get("weightedPrice")) {
            assertOptional(Calc.weightedUnitPrice(dec(c.get("amount")), dec(c.get("quantity"))), c.get("expected"));
        }
    }

    @Test
    void costPerKm() {
        for (JsonNode c : calc.get("costPerKm")) {
            assertOptional(Calc.costPerKm(dec(c.get("amount")), dec(c.get("km"))), c.get("expected"));
        }
    }

    @Test
    void variance() {
        for (JsonNode c : calc.get("variancePct")) {
            assertOptional(Calc.variancePct(dec(c.get("actual")), dec(c.get("expected"))), c.get("result"));
        }
    }

    @Test
    void rupeeFormatting() {
        for (JsonNode c : calc.get("rupees")) {
            assertThat(IndianFormat.rupees(dec(c.get("amount")))).isEqualTo(c.get("formatted").asText());
        }
    }

    @Test
    void serviceDue() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        for (JsonNode c : calc.get("serviceDue")) {
            ServiceDue.Result r = ServiceDue.evaluate(new ServiceDue.Input(
                    today.minusMonths(1), dec(c.get("lastServiceOdo")), null, null,
                    c.get("intervalKm").asInt(), 0,
                    new Odometer.Baseline(today.minusDays(5), dec(c.get("baselineKm")), true, 1),
                    dec(c.get("gpsAfterBaseline")), today, c.get("dueSoonKm").asInt(), 15));
            assertThat(r.nextServiceOdometer().toPlainString()).isEqualTo(c.get("expectedNext").asText());
            assertThat(r.remainingKm().toPlainString()).isEqualTo(c.get("expectedRemaining").asText());
            assertThat(r.status().name()).isEqualTo(c.get("expectedStatus").asText());
        }
    }

    @Test
    void fuelNamesFromStatements() throws Exception {
        for (JsonNode c : new ObjectMapper().readTree(locate("energy-cases.json")).get("cases")) {
            EnergyType e = EnergyType.parse(c.get("raw").asText());
            assertThat(e == null ? null : e.name()).as(c.get("raw").asText())
                    .isEqualTo(c.get("energy").isNull() ? null : c.get("energy").asText());
        }
    }

    @Test
    void vehicleMasterRules() throws Exception {
        JsonNode m = new ObjectMapper().readTree(locate("master-cases.json"));
        for (JsonNode c : m.get("operator")) {
            String label = c.get("label").isNull() ? null : c.get("label").asText();
            String name = c.get("name").isNull() ? null : c.get("name").asText();
            MasterRules.Derived d = MasterRules.derive(label, name);
            String as = label + "/" + name;
            assertThat(d.operatorType().name()).as(as).isEqualTo(c.get("operatorType").asText());
            assertThat(d.status()).as(as).isEqualTo(c.get("status").asText());
            assertThat(d.ownership()).as(as).isEqualTo(c.get("ownership").asText());
            assertThat(d.gpsKmTracked()).as(as).isEqualTo(c.get("gpsKmTracked").asBoolean());
        }
        for (JsonNode c : m.get("fuel")) {
            String expected = c.get("fuelType").isNull() ? null : c.get("fuelType").asText();
            assertThat(MasterRules.fuelType(c.get("raw").asText())).as(c.get("raw").asText()).isEqualTo(expected);
        }
        for (JsonNode c : m.get("model")) {
            MasterRules.ModelInfo i = MasterRules.model(c.get("raw").asText());
            assertThat(i).as(c.get("raw").asText())
                    .isEqualTo(new MasterRules.ModelInfo(c.get("make").asText(), c.get("model").asText(), c.get("category").asText()));
        }
    }

    @Test
    void registrationNormalize() {
        for (JsonNode c : reg.get("normalize")) {
            assertThat(RegistrationNormalizer.normalize(c.get("input").asText())).isEqualTo(c.get("expected").asText());
        }
    }

    @Test
    void registrationExtract() {
        for (JsonNode c : reg.get("extract")) {
            String expected = c.get("expected").isNull() ? null : c.get("expected").asText();
            assertThat(RegistrationNormalizer.extractPlate(c.get("input").asText()))
                    .as("extract(%s)", c.get("input").asText())
                    .isEqualTo(expected);
        }
    }
}
