package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriverVendorSwitchIT extends AbstractIT {

    Map<String, Object> body(String operator, boolean tracked, String effective) {
        Map<String, Object> b = new HashMap<>();
        b.put("registrationNumber", "DL1SW0001");
        b.put("make", "Maruti");
        b.put("model", "Dzire");
        b.put("fuelType", "CNG");
        b.put("categoryCode", "SEDAN");
        b.put("ownershipType", "VENDOR".equals(operator) ? "VENDOR_ATTACHED" : "OWNED");
        b.put("status", "ACTIVE");
        b.put("operatorType", operator);
        b.put("gpsKmTracked", tracked);
        b.put("effectiveDate", effective);
        return b;
    }

    int currentDays(long v) {
        return jdbc.queryForObject("SELECT count(*) FROM gps_daily_km WHERE vehicle_id = ? AND is_current", Integer.class, v);
    }

    int heldDays(long v) {
        return jdbc.queryForObject("SELECT count(*) FROM gps_held_km WHERE vehicle_id = ?", Integer.class, v);
    }

    @Test
    void gpsKmFollowTheVehicleBetweenVendorAndLrDriver() throws Exception {
        long v = createVehicle("DL1SW0001", "CNG");
        // vendor vehicle: its GPS km are imported but kept aside, not counted
        JsonNode toVendor = call(put("/api/vehicles/" + v), adminToken, body("VENDOR", false, D0.toString()), status().isOk());
        assertThat(toVendor.get("gpsKm").get("direction").asText()).isEqualTo("HELD");
        long b = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", new Object[][]{
                {2, v, D0, "{\"vehicleRaw\":\"DL1SW0001\",\"km\":\"110\"}", "EXCLUDED"},
                {3, v, D0.plusDays(1), "{\"vehicleRaw\":\"DL1SW0001\",\"km\":\"120\"}", "EXCLUDED"},
                {4, v, D0.plusDays(2), "{\"vehicleRaw\":\"DL1SW0001\",\"km\":\"130\"}", "EXCLUDED"}});
        call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(currentDays(v)).isZero();
        assertThat(heldDays(v)).isEqualTo(3);
        assertThat(getJson("/api/vehicles/" + v, adminToken).get("heldGps").get("days").asInt()).isEqualTo(3);

        // moves to an LR driver from day 2: days 2 and 3 are counted automatically, day 1 stays with the vendor period
        JsonNode toDriver = call(put("/api/vehicles/" + v), adminToken, body("OWN_DRIVER", true, D0.plusDays(1).toString()), status().isOk());
        assertThat(toDriver.get("gpsKm").get("direction").asText()).isEqualTo("ADDED");
        assertThat(toDriver.get("gpsKm").get("days").asInt()).isEqualTo(2);
        assertThat(toDriver.get("gpsKm").get("km").decimalValue()).isEqualByComparingTo("250");
        assertThat(currentDays(v)).isEqualTo(2);
        assertThat(heldDays(v)).isEqualTo(1);

        // back to a vendor from day 3: day 3 is taken out of mileage again and kept
        JsonNode back = call(put("/api/vehicles/" + v), adminToken, body("VENDOR", false, D0.plusDays(2).toString()), status().isOk());
        assertThat(back.get("gpsKm").get("days").asInt()).isEqualTo(1);
        assertThat(currentDays(v)).isEqualTo(1);
        assertThat(heldDays(v)).isEqualTo(2);
        // the history shows the driver type change from that date
        assertThat(jdbc.queryForObject("""
                SELECT effective_from FROM vehicle_assignments WHERE vehicle_id = ? AND assignment_type = 'OPERATOR' AND effective_to IS NULL""",
                java.time.LocalDate.class, v)).isEqualTo(D0.plusDays(2));
    }

    @Test
    void newDriverAndVendorCanBeAddedFromTheForm() throws Exception {
        long d = call(post("/api/drivers"), adminToken, Map.of("fullName", "NEW FORM DRIVER", "phone", "9876543210"), status().isOk()).get("id").asLong();
        long vd = call(post("/api/vendors"), adminToken, Map.of("name", "New Form Vendor", "vendorType", "VEHICLE_VENDOR", "gstin", ""), status().isOk())
                .get("id").asLong();
        JsonNode lk = getJson("/api/lookups", adminToken);
        assertThat(lk.get("drivers").toString()).contains("NEW FORM DRIVER").contains(String.valueOf(d));
        assertThat(lk.get("vendors").toString()).contains("New Form Vendor").contains(String.valueOf(vd));
    }
}
