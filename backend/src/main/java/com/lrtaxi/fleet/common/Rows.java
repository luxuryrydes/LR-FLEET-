package com.lrtaxi.fleet.common;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** ResultSet helpers that map SQL types to JSON-friendly Java types and snake_case to camelCase. */
public final class Rows {

    private Rows() {
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static BigDecimal dec(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }

    public static Boolean boolOrNull(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    /** Generic row mapper for read-only listings: every column, camelCased; JSONB parsed to JSON. */
    public static Map<String, Object> map(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = camel(md.getColumnLabel(i));
            Object v = rs.getObject(i);
            if (v instanceof Date d) {
                v = d.toLocalDate();
            } else if (v instanceof Timestamp t) {
                v = t.toInstant();
            } else if (v != null && "jsonb".equalsIgnoreCase(md.getColumnTypeName(i))) {
                v = Json.read(rs.getString(i));
            } else if (v instanceof java.sql.Time t) {
                v = t.toLocalTime().toString();
            } else if (v instanceof java.sql.Array a) {
                v = a.getArray();
            } else if (v != null && v.getClass().getName().equals("org.postgresql.util.PGobject")) {
                v = v.toString();
            }
            m.put(name, v);
        }
        return m;
    }

    public static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }
}
