package com.cmms.web;

import com.cmms.security.Current;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> dashboard() {
        Long me = Current.user().id();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", AssetController.camel(jdbc.queryForMap(COUNTS, me)));
        out.put("monthlySpend", jdbc.queryForList("""
            SELECT to_char(m, 'YYYY-MM') AS month,
                   COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type = 'LABOR'), 0) AS labor,
                   COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type = 'PARTS'), 0) AS parts,
                   COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type = 'OTHER'), 0) AS other
              FROM generate_series(date_trunc('month', CURRENT_DATE) - interval '11 months', date_trunc('month', CURRENT_DATE), interval '1 month') m
              LEFT JOIN v_cost_lines c ON date_trunc('month', c.cost_date) = m
             GROUP BY m ORDER BY m"""));
        out.put("overdue", jdbc.queryForList("""
            SELECT wo.id, wo.number, wo.title, wo.priority, wo.status, wo.due_date AS "dueDate", a.tag AS "assetTag"
              FROM work_orders wo LEFT JOIN assets a ON a.id = wo.asset_id
             WHERE wo.status IN ('OPEN','ASSIGNED','IN_PROGRESS') AND wo.due_date < CURRENT_DATE
             ORDER BY wo.due_date LIMIT 10"""));
        out.put("upcomingPm", jdbc.queryForList("""
            SELECT p.id, p.title, p.next_due AS "nextDue", a.tag AS "assetTag", a.name AS "assetName"
              FROM pm_schedules p JOIN assets a ON a.id = p.asset_id
             WHERE p.active AND p.next_due <= CURRENT_DATE + 14 ORDER BY p.next_due LIMIT 10"""));
        return out;
    }

    static final String COUNTS = """
        SELECT
          (SELECT COUNT(*) FROM work_orders WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS open_work_orders,
          (SELECT COUNT(*) FROM work_orders WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS') AND due_date < CURRENT_DATE) AS overdue_work_orders,
          (SELECT COUNT(*) FROM work_orders WHERE status = 'COMPLETED') AS awaiting_sign_off,
          (SELECT COUNT(*) FROM maintenance_requests WHERE status = 'NEW') AS new_requests,
          (SELECT COUNT(*) FROM parts WHERE active AND qty_on_hand <= reorder_point) AS low_stock_parts,
          (SELECT COUNT(*) FROM assets WHERE status = 'DOWN') AS assets_down,
          (SELECT COUNT(*) FROM pm_schedules WHERE active AND next_due <= CURRENT_DATE + 14) AS pm_due_14_days,
          (SELECT COUNT(*) FROM work_orders wo JOIN wo_assignments x ON x.work_order_id = wo.id
             WHERE x.user_id = ? AND wo.status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS my_open_work_orders,
          (SELECT COALESCE(SUM(amount),0) FROM v_cost_lines WHERE cost_date >= date_trunc('month', CURRENT_DATE)) AS spend_this_month,
          (SELECT COALESCE(SUM(amount),0) FROM v_cost_lines WHERE cost_date >= date_trunc('year', CURRENT_DATE)) AS spend_this_year,
          (SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE completed_at::date <= due_date) / NULLIF(COUNT(*),0), 1)
             FROM work_orders WHERE type = 'PM' AND completed_at >= CURRENT_DATE - 90) AS pm_on_time_pct
        """;
}
