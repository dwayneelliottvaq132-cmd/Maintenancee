package com.cmms.web;

import com.cmms.entity.PmSchedule;
import com.cmms.entity.WorkOrder;
import com.cmms.repo.PmScheduleRepository;
import com.cmms.security.Current;
import com.cmms.service.PmService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pm-schedules")
public class PmController {
    private final PmScheduleRepository repo;
    private final PmService service;
    private final JdbcTemplate jdbc;

    public PmController(PmScheduleRepository repo, PmService service, JdbcTemplate jdbc) {
        this.repo = repo;
        this.service = service;
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
            SELECT p.*, a.tag AS asset_tag, a.name AS asset_name, a.meter_reading AS asset_meter, a.meter_unit,
                   wi.title AS instruction_title, u.full_name AS assignee_name,
                   (SELECT w.id FROM work_orders w WHERE w.pm_schedule_id = p.id AND w.status NOT IN ('SIGNED_OFF','CANCELLED') LIMIT 1) AS open_work_order_id,
                   (SELECT w.number FROM work_orders w WHERE w.pm_schedule_id = p.id AND w.status NOT IN ('SIGNED_OFF','CANCELLED') LIMIT 1) AS open_work_order_number,
                   (p.active AND p.next_due IS NOT NULL AND p.next_due < CURRENT_DATE) AS overdue
              FROM pm_schedules p JOIN assets a ON a.id = p.asset_id
              LEFT JOIN work_instructions wi ON wi.id = p.instruction_id
              LEFT JOIN users u ON u.id = p.default_assignee_id
             ORDER BY p.active DESC, p.next_due NULLS LAST, p.title""").stream().map(AssetController::camel).toList();
    }

    @GetMapping("/{id}")
    public PmSchedule get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    @PreAuthorize(Roles.SUPERVISOR)
    public PmSchedule create(@RequestBody PmSchedule in) { return service.save(null, in); }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public PmSchedule update(@PathVariable Long id, @RequestBody PmSchedule in) { return service.save(id, in); }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public void delete(@PathVariable Long id) { repo.deleteById(id); }

    /** Create the work order for this schedule right away, regardless of due date. */
    @PostMapping("/{id}/generate-now")
    @PreAuthorize(Roles.SUPERVISOR)
    public WorkOrder generateNow(@PathVariable Long id) {
        return service.generate(service.get(id), Current.user().id());
    }

    /** Run the same due check the nightly worker runs. */
    @PostMapping("/generate-due")
    @PreAuthorize(Roles.SUPERVISOR)
    public List<WorkOrder> generateDue() { return service.generateDue(Current.user().id()); }
}
