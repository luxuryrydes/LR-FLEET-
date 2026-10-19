package com.lrtaxi.fleet.common;

import java.util.Map;

/** Server-side pagination and whitelisted sorting (never interpolates user text into SQL). */
public record Paging(int page, int size, String orderBy) {

    public static final int MAX_SIZE = 200;

    /**
     * @param sortable map of API sort keys to trusted SQL expressions
     * @param sort     "field" or "field,desc"
     */
    public static Paging of(Integer page, Integer size, String sort, Map<String, String> sortable, String defaultOrder) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 25 : Math.min(size, MAX_SIZE);
        String order = defaultOrder;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String expr = sortable.get(parts[0].trim());
            if (expr != null) {
                boolean desc = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc");
                order = expr + (desc ? " DESC NULLS LAST" : " ASC NULLS LAST");
            }
        }
        return new Paging(p, s, order);
    }

    public int offset() {
        return page * size;
    }

    public String sql() {
        return " ORDER BY " + orderBy + " LIMIT " + size + " OFFSET " + offset();
    }
}
