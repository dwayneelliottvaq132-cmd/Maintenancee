package com.cmms.web;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize(Roles.SUPERVISOR)
public class AuditController {
    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String entity, @RequestParam(required = false) Long entityId) {
        return jdbc.queryForList("""
            SELECT l.id, l.entity, l.entity_id AS "entityId", l.action, l.details, l.created_at AS "createdAt", u.full_name AS "user"
              FROM audit_log l LEFT JOIN users u ON u.id = l.user_id
             WHERE (?::text IS NULL OR l.entity = ?) AND (?::bigint IS NULL OR l.entity_id = ?)
             ORDER BY l.id DESC LIMIT 300""", entity, entity, entityId, entityId);
    }
}
