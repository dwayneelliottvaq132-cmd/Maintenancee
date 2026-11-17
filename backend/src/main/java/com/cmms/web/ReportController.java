package com.cmms.web;

import com.cmms.config.CmmsProperties;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize(Roles.SUPERVISOR)
public class ReportController {
    private final JdbcTemplate jdbc;
    private final CmmsProperties props;

    public ReportController(JdbcTemplate jdbc, CmmsProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Spending grouped by asset, category, employee, month, cost type or work order. */
    @GetMapping("/spending")
    public List<Map<String, Object>> spending(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(defaultValue = "asset") String groupBy) {
        String key, join = "";
        switch (groupBy) {
            case "asset" -> { key = "COALESCE(a.tag || ' — ' || a.name, '(no asset)')"; join = "LEFT JOIN assets a ON a.id = c.asset_id"; }
            case "category" -> { key = "COALESCE(a.category, '(uncategorised)')"; join = "LEFT JOIN assets a ON a.id = c.asset_id"; }
            case "employee" -> { key = "COALESCE(u.full_name, '(not assigned to a person)')"; join = "LEFT JOIN users u ON u.id = c.user_id"; }
            case "month" -> key = "to_char(c.cost_date, 'YYYY-MM')";
            case "type" -> key = "c.cost_type";
            case "workorder" -> { key = "wo.number || ' — ' || wo.title"; join = "JOIN work_orders wo ON wo.id = c.work_order_id"; }
            default -> throw ApiException.bad("groupBy must be asset, category, employee, month, type or workorder");
        }
        return jdbc.queryForList("SELECT " + key + " AS label,"
                + " COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type = 'LABOR'), 0) AS labor,"
                + " COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type = 'PARTS'), 0) AS parts,"
                + " COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type = 'OTHER'), 0) AS other,"
                + " SUM(c.amount) AS total, SUM(c.hours) AS hours, COUNT(DISTINCT c.work_order_id) AS \"workOrders\""
                + " FROM v_cost_lines c " + join
                + " WHERE c.cost_date BETWEEN ? AND ? GROUP BY 1 ORDER BY " + ("month".equals(groupBy) ? "1" : "total DESC"),
                from, to);
    }

    /** Labor hours and cost per employee, including how many work orders they worked on. */
    @GetMapping("/labor")
    public List<Map<String, Object>> labor(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return jdbc.queryForList("""
            SELECT u.full_name AS employee, u.trade, SUM(l.hours) AS hours, SUM(l.hours * l.hourly_rate) AS cost,
                   COUNT(DISTINCT l.work_order_id) AS "workOrders"
              FROM labor_entries l JOIN users u ON u.id = l.user_id
             WHERE l.work_date BETWEEN ? AND ? GROUP BY u.full_name, u.trade ORDER BY hours DESC""", from, to);
    }

    /** Parts/supplies consumed in the period. */
    @GetMapping("/parts")
    public List<Map<String, Object>> parts(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return jdbc.queryForList("""
            SELECT p.part_no AS "partNo", p.name, p.unit, SUM(pu.qty) AS qty, SUM(pu.qty * pu.unit_cost) AS cost,
                   p.qty_on_hand AS "onHand", COUNT(DISTINCT pu.work_order_id) AS "workOrders"
              FROM part_usages pu JOIN parts p ON p.id = pu.part_id
             WHERE pu.used_at::date BETWEEN ? AND ? GROUP BY p.id ORDER BY cost DESC""", from, to);
    }

    /** Files produced by the Python report worker (PDF / Excel). */
    @GetMapping("/files")
    public List<Map<String, Object>> files() throws IOException {
        Path dir = Path.of(props.reportDir());
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .map(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", p.getFileName().toString());
                        try { m.put("size", Files.size(p)); m.put("modified", Files.getLastModifiedTime(p).toString()); }
                        catch (IOException ignored) {}
                        return m;
                    }).toList();
        }
    }

    @GetMapping("/files/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        if (!name.matches("[A-Za-z0-9._-]+")) throw ApiException.bad("Bad file name");
        Path p = Path.of(props.reportDir()).resolve(name);
        if (!Files.isRegularFile(p)) throw ApiException.notFound("Report", name);
        MediaType type = name.endsWith(".pdf") ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new FileSystemResource(p));
    }
}
