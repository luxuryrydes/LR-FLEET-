package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end data flow: manual entries -> Java engine -> dashboard, reports and service status agree. */
class CalculationFlowIT extends AbstractIT {

    /** Each test uses its own date window so fleet-level figures are not affected by other tests' data. */
    java.time.LocalDate base = D0;

    Map<String, Object> fuel(long v, int day, String energy, String qty, String amount, String odo) {
        Map<String, Object> m = new HashMap<>();
        m.put("vehicleId", v);
        m.put("txnDate", base.plusDays(day).toString());
        m.put("energyType", energy);
        m.put("quantity", qty);
        m.put("invoiceAmount", amount);
        m.put("odometerKm", odo);
        return m;
    }

    void km(long v, int day, String km) throws Exception {
        call(post("/api/gps"), adminToken, Map.of("vehicleId", v, "date", base.plusDays(day).toString(), "km", km), status().isOk());
    }

    @Test
    void manualFuelProducesWeightedMileageAndConsistentDashboard() throws Exception {
        base = java.time.LocalDate.now().minusDays(300);
        long a = createVehicle("UP16T0001", "CNG", "25");
        long b = createVehicle("UP16T0002", "CNG", "25");
        // A: 500 km / 20 kg = 25 ; B: 100 km / 10 kg = 10 -> fleet weighted 600/30 = 20 (simple average would be 17.5)
        call(post("/api/fuel"), adminToken, fuel(a, 0, "CNG", "5", "450", "1000"), status().isOk());
        call(post("/api/fuel"), adminToken, fuel(a, 5, "CNG", "20", "1800", "1500"), status().isOk());
        call(post("/api/fuel"), adminToken, fuel(b, 0, "CNG", "5", "450", "2000"), status().isOk());
        call(post("/api/fuel"), adminToken, fuel(b, 5, "CNG", "10", "900", "2100"), status().isOk());
        // duplicate manual entry is refused
        call(post("/api/fuel"), adminToken, fuel(b, 5, "CNG", "10", "900", "2100"), status().isConflict());

        String q = "?from=" + base + "&to=" + base.plusDays(10) + "&fuelType=CNG";
        JsonNode kpis = getJson("/api/dashboard/kpis" + q, adminToken);
        JsonNode cng = null;
        for (JsonNode m : kpis.get("fleetMileage")) {
            if (m.get("energyType").asText().equals("CNG")) {
                cng = m;
            }
        }
        assertThat(cng).isNotNull();
        assertThat(cng.get("mileage").decimalValue()).isEqualByComparingTo("20");
        assertThat(kpis.get("fuelByEnergy").get("CNG").get("quantity").decimalValue()).isEqualByComparingTo("40");
        assertThat(kpis.get("totalFuelExpense").decimalValue()).isEqualByComparingTo("3600");
        assertThat(kpis.get("fuelByEnergy").get("CNG").get("weightedUnitPrice").decimalValue()).isEqualByComparingTo("90");

        // vehicle B is 60% below expected -> deteriorating; report agrees with dashboard
        assertThat(kpis.get("vehiclesDeterioratingMileage").asLong()).isEqualTo(1);
        JsonNode low = getJson("/api/reports/low-efficiency-vehicles" + q, adminToken);
        assertThat(low.get("totalRows").asInt()).isEqualTo(1);
        assertThat(low.get("rows").get(0).get("registrationNumber").asText()).isEqualTo("UP16T0002");
        assertThat(low.get("rows").get(0).get("variancePct").decimalValue()).isEqualByComparingTo("-60.00");

        JsonNode fuelReport = getJson("/api/reports/fuel-consumption-by-type" + q, adminToken);
        BigDecimal reportTotal = BigDecimal.ZERO;
        for (JsonNode r : fuelReport.get("rows")) {
            reportTotal = reportTotal.add(r.get("expense").decimalValue());
        }
        assertThat(reportTotal).isEqualByComparingTo(kpis.get("totalFuelExpense").decimalValue());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports/fuel-cost-per-km/export" + q + "&format=xlsx")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    }

    @Test
    void costPerKmUsesOnlyVehiclesWithBothKmAndFuel() throws Exception {
        base = java.time.LocalDate.now().minusDays(250);
        long a = createVehicle("GJ01AA0001", "DIESEL");
        long b = createVehicle("GJ01AA0002", "DIESEL");
        km(a, 1, "500");
        km(a, 2, "500");
        km(b, 1, "300");     // km but no fuel -> excluded from ratio, reported as coverage gap
        call(post("/api/fuel"), adminToken, fuel(a, 2, "DIESEL", "50", "4500", null), status().isOk());
        String q = "?from=" + base + "&to=" + base.plusDays(5) + "&fuelType=DIESEL";
        JsonNode cost = getJson("/api/dashboard/kpis" + q, adminToken).get("costPerKm");
        assertThat(cost.get("fuelCostPerKm").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(cost.get("vehiclesIncluded").asInt()).isEqualTo(1);
        assertThat(cost.get("vehiclesWithoutFuel").asInt()).isEqualTo(1);
    }

    @Test
    void serviceDueUsesBaselinePlusGpsAndResetsOnNewService() throws Exception {
        long v = createVehicle("TN09BB0001", "PETROL");
        Map<String, Object> svc = new HashMap<>();
        svc.put("vehicleId", v);
        svc.put("serviceDate", D0.toString());
        svc.put("odometerKm", "50000");
        svc.put("categoryCode", "ROUTINE_SERVICE");
        svc.put("description", "Periodic service");
        svc.put("labourCost", "1500");
        svc.put("partsCost", "2500");
        JsonNode created = call(post("/api/maintenance"), adminToken, svc, status().isOk());
        assertThat(created.get("nextServiceOdometer").decimalValue()).isEqualByComparingTo("60000");
        for (int d = 1; d <= 10; d++) {
            km(v, d, "960");       // 9,600 km after the service baseline
        }
        JsonNode s = getJson("/api/vehicles/" + v, adminToken).get("serviceStatus");
        assertThat(s.get("currentOdometer").decimalValue()).isEqualByComparingTo("59600");
        assertThat(s.get("remainingKm").decimalValue()).isEqualByComparingTo("400");
        assertThat(s.get("status").asText()).isEqualTo("DUE_SOON");

        // New service at 59,700 on day 10 resets the baseline; GPS km up to day 10 are not counted again.
        svc.put("serviceDate", D0.plusDays(10).toString());
        svc.put("odometerKm", "59700");
        call(post("/api/maintenance"), adminToken, svc, status().isOk());
        JsonNode s2 = getJson("/api/vehicles/" + v, adminToken).get("serviceStatus");
        assertThat(s2.get("currentOdometer").decimalValue()).isEqualByComparingTo("59700");
        assertThat(s2.get("nextServiceOdometer").decimalValue()).isEqualByComparingTo("69700");
        assertThat(s2.get("status").asText()).isEqualTo("OK");
        // prior service history is retained
        Long services = jdbc.queryForObject("SELECT count(*) FROM maintenance_records WHERE vehicle_id = ?", Long.class, v);
        assertThat(services).isEqualTo(2);
    }

    @Test
    void decreasingOdometerIsFlaggedAndAlertRaised() throws Exception {
        long v = createVehicle("KL07CC0001", "PETROL");
        call(post("/api/fuel"), adminToken, fuel(v, 0, "PETROL", "20", "2000", "30000"), status().isOk());
        JsonNode r = call(post("/api/fuel"), adminToken, fuel(v, 3, "PETROL", "20", "2000", "29900"), status().isOk());
        assertThat(r.get("warnings").toString()).contains("Odometer not trusted");
        call(post("/api/alerts/run"), adminToken, null, status().isOk());
        Long alerts = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE alert_type = 'INVALID_ODOMETER' AND vehicle_id = ?", Long.class, v);
        assertThat(alerts).isEqualTo(1);
        // re-running does not duplicate the open alert
        call(post("/api/alerts/run"), adminToken, null, status().isOk());
        Long again = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE alert_type = 'INVALID_ODOMETER' AND vehicle_id = ?", Long.class, v);
        assertThat(again).isEqualTo(1);
    }

    @Test
    void incompatibleFuelRejected() throws Exception {
        long v = createVehicle("KL07CC0002", "ELECTRIC");
        JsonNode err = call(post("/api/fuel"), adminToken, fuel(v, 0, "PETROL", "20", "2000", null), status().isBadRequest());
        assertThat(err.get("code").asText()).isEqualTo("INCOMPATIBLE_FUEL");
    }
}
