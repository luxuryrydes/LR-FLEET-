package com.lrtaxi.fleet.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** JSON helper for JSONB columns. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {
    }

    public static String write(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialisation failed", e);
        }
    }

    public static JsonNode read(String s) {
        if (s == null) {
            return null;
        }
        try {
            return MAPPER.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in database", e);
        }
    }
}
