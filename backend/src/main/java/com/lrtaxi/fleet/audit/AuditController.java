package com.lrtaxi.fleet.audit;

import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.common.Paging;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.Roles;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize(Roles.MANAGE)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String entityType,
                                                @RequestParam(required = false) String entityId,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        Paging paging = Paging.of(page, size, null, Map.of(), "created_at DESC");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (entityType != null && !entityType.isBlank()) {
            where.append(" AND entity_type = :et");
            p.addValue("et", entityType);
        }
        if (entityId != null && !entityId.isBlank()) {
            where.append(" AND entity_id = :eid");
            p.addValue("eid", entityId);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_logs" + where, p, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT id, user_email, action, entity_type, entity_id, before_data, after_data, ip_address, created_at
                FROM audit_logs""" + where + paging.sql(), p, Rows::map);
        return new PageResult<>(items, total == null ? 0 : total, paging.page(), paging.size());
    }
}
