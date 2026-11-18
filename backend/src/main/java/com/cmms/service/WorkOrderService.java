package com.cmms.service;

import com.cmms.entity.*;
import com.cmms.entity.Enums.*;
import com.cmms.repo.*;
import com.cmms.security.AuthUser;
import com.cmms.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work-order lifecycle: OPEN -> ASSIGNED -> IN_PROGRESS -> COMPLETED -> SIGNED_OFF (or CANCELLED).
 * Once COMPLETED the checklist, labor, parts and costs are frozen until a supervisor signs off or sends
 * the job back, so what gets signed is exactly what was completed.
 */
@Service
public class WorkOrderService {

    public record StepView(Long stepId, int seq, String text, boolean requiresCheck, boolean done,
                           String note, Long doneBy, OffsetDateTime doneAt) {}
    /** labor and total are null when the viewer may not see pay rates. */
    public record Totals(BigDecimal labor, BigDecimal parts, BigDecimal other, BigDecimal total, BigDecimal hours) {}
    public record Detail(WorkOrder workOrder, List<Long> assigneeIds, WorkInstruction instruction, List<StepView> steps,
                         List<LaborEntry> labor, List<PartUsage> parts, List<OtherCost> otherCosts, Totals totals) {}

    static final Set<WoStatus> CLOSED = EnumSet.of(WoStatus.SIGNED_OFF, WoStatus.CANCELLED);
    /** Fields a supervisor may change with PUT /api/work-orders/{id}. Missing fields are left alone. */
    static final Set<String> UPDATABLE = Set.of("title", "description", "priority", "assetId", "locationId", "instructionId", "dueDate");

    private final WorkOrderRepository wos;
    private final WorkInstructionRepository instructions;
    private final InstructionStepRepository steps;
    private final WoStepResultRepository stepResults;
    private final LaborEntryRepository labor;
    private final PartUsageRepository partUsages;
    private final OtherCostRepository otherCosts;
    private final UserRepository users;
    private final AssetRepository assets;
    private final PmScheduleRepository schedules;
    private final PartService partService;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkOrderService(WorkOrderRepository wos, WorkInstructionRepository instructions, InstructionStepRepository steps,
                            WoStepResultRepository stepResults, LaborEntryRepository labor, PartUsageRepository partUsages,
                            OtherCostRepository otherCosts, UserRepository users, AssetRepository assets,
                            PmScheduleRepository schedules, PartService partService, AuditService audit, JdbcTemplate jdbc,
                            ObjectMapper mapper) {
        this.wos = wos;
        this.instructions = instructions;
        this.steps = steps;
        this.stepResults = stepResults;
        this.labor = labor;
        this.partUsages = partUsages;
        this.otherCosts = otherCosts;
        this.users = users;
        this.assets = assets;
        this.schedules = schedules;
        this.partService = partService;
        this.audit = audit;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public WorkOrder get(Long id) {
        return wos.findById(id).orElseThrow(() -> ApiException.notFound("Work order", id));
    }

    /** Row-locked read for state changes, so two people acting on the same job are applied one after the other. */
    private WorkOrder lock(Long id) {
        return wos.lockById(id).orElseThrow(() -> ApiException.notFound("Work order", id));
    }

    public List<Map<String, Object>> list(String status, Long assetId, Long assigneeId, String type, boolean openOnly) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { where.append(" AND wo.status = ?"); args.add(status); }
        if (type != null && !type.isBlank()) { where.append(" AND wo.type = ?"); args.add(type); }
        if (assetId != null) { where.append(" AND wo.asset_id = ?"); args.add(assetId); }
        if (openOnly) where.append(" AND wo.status NOT IN ('SIGNED_OFF','CANCELLED')");
        if (assigneeId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM wo_assignments x WHERE x.work_order_id = wo.id AND x.user_id = ?)");
            args.add(assigneeId);
        }
        // costs and assignee names are aggregated once for the selected work orders instead of per row
        String sql = """
            WITH sel AS (
              SELECT wo.* FROM work_orders wo %s
               ORDER BY CASE wo.status WHEN 'SIGNED_OFF' THEN 2 WHEN 'CANCELLED' THEN 3 ELSE 1 END, wo.due_date NULLS LAST, wo.id DESC
               LIMIT 1000),
            cost AS (SELECT c.work_order_id, SUM(c.amount) AS total FROM v_cost_lines c
                      WHERE c.work_order_id IN (SELECT id FROM sel) GROUP BY c.work_order_id),
            who AS (SELECT x.work_order_id, string_agg(u.full_name, ', ' ORDER BY u.full_name) AS names
                      FROM wo_assignments x JOIN users u ON u.id = x.user_id
                     WHERE x.work_order_id IN (SELECT id FROM sel) GROUP BY x.work_order_id)
            SELECT sel.id, sel.number, sel.title, sel.type, sel.priority, sel.status, sel.asset_id AS "assetId",
                   a.tag AS "assetTag", a.name AS "assetName", sel.due_date AS "dueDate", sel.created_at AS "createdAt",
                   sel.completed_at AS "completedAt", sel.signed_off_at AS "signedOffAt",
                   (sel.due_date < CURRENT_DATE AND sel.status NOT IN ('COMPLETED','SIGNED_OFF','CANCELLED')) AS overdue,
                   COALESCE(who.names, '') AS assignees, COALESCE(cost.total, 0) AS "totalCost"
              FROM sel LEFT JOIN assets a ON a.id = sel.asset_id
              LEFT JOIN cost ON cost.work_order_id = sel.id LEFT JOIN who ON who.work_order_id = sel.id
             ORDER BY CASE sel.status WHEN 'SIGNED_OFF' THEN 2 WHEN 'CANCELLED' THEN 3 ELSE 1 END, sel.due_date NULLS LAST, sel.id DESC
            """.formatted(where);
        return jdbc.queryForList(sql, args.toArray());
    }

    /** Full view for a supervisor (pay rates included). */
    public Detail detail(Long id) {
        return detail(id, true);
    }

    public Detail detail(Long id, boolean showPay) {
        WorkOrder wo = get(id);
        WorkInstruction wi = wo.instructionId == null ? null : instructions.findById(wo.instructionId).orElse(null);
        List<StepView> stepViews = new ArrayList<>();
        if (wi != null) {
            Map<Long, WoStepResult> results = new HashMap<>();
            stepResults.findByWorkOrderId(id).forEach(r -> results.put(r.stepId, r));
            // current steps, plus retired steps this job already recorded (history stays as it was)
            for (InstructionStep s : steps.findByInstructionIdOrderBySeq(wi.id)) {
                WoStepResult r = results.get(s.id);
                if (!s.active && r == null) continue;
                stepViews.add(new StepView(s.id, s.seq, s.text, s.requiresCheck, r != null && r.done,
                        r == null ? null : r.note, r == null ? null : r.doneBy, r == null ? null : r.doneAt));
            }
        }
        List<LaborEntry> l = labor.findByWorkOrderId(id);
        List<PartUsage> p = partUsages.findByWorkOrderId(id);
        List<OtherCost> o = otherCosts.findByWorkOrderId(id);
        BigDecimal lab = l.stream().map(x -> x.hours.multiply(x.hourlyRate)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal hrs = l.stream().map(x -> x.hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal par = p.stream().map(x -> x.qty.multiply(x.unitCost)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal oth = o.stream().map(x -> x.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!showPay) {
            // don't reveal colleagues' hourly rates (copies, so nothing can be saved by mistake)
            List<LaborEntry> hidden = l.stream().map(x -> {
                LaborEntry c = new LaborEntry();
                c.id = x.id; c.workOrderId = x.workOrderId; c.userId = x.userId; c.workDate = x.workDate;
                c.hours = x.hours; c.note = x.note; c.hourlyRate = null;
                return c;
            }).toList();
            return new Detail(wo, assigneeIds(id), wi, stepViews, hidden, p, o, new Totals(null, par, oth, null, hrs));
        }
        return new Detail(wo, assigneeIds(id), wi, stepViews, l, p, o,
                new Totals(lab, par, oth, lab.add(par).add(oth), hrs));
    }

    public List<Long> assigneeIds(Long woId) {
        return jdbc.queryForList("SELECT user_id FROM wo_assignments WHERE work_order_id = ? ORDER BY user_id", Long.class, woId);
    }

    /** Work orders created by people. Links to PM schedules and requests are only made by the system. */
    @Transactional
    public WorkOrder create(WorkOrder in, List<Long> assigneeIds, Long createdBy) {
        WorkOrder clean = new WorkOrder();
        clean.title = in.title;
        clean.description = in.description;
        clean.type = in.type == WoType.PM ? WoType.PM : WoType.CORRECTIVE;
        clean.priority = in.priority;
        clean.assetId = in.assetId;
        clean.locationId = in.locationId;
        clean.instructionId = in.instructionId;
        clean.dueDate = in.dueDate;
        return createLinked(clean, assigneeIds, createdBy);
    }

    /** Used by PmService and RequestService: keeps pmScheduleId / requestId / type as given. */
    @Transactional
    public WorkOrder createLinked(WorkOrder in, List<Long> assigneeIds, Long createdBy) {
        if (in.title == null || in.title.isBlank()) throw ApiException.bad("Title is required");
        WorkOrder wo = new WorkOrder();
        wo.number = "WO-" + jdbc.queryForObject("SELECT nextval('work_order_number_seq')", Long.class);
        wo.title = in.title.trim();
        wo.description = in.description;
        wo.type = in.type == null ? WoType.CORRECTIVE : in.type;
        wo.priority = in.priority == null ? Priority.MEDIUM : in.priority;
        wo.assetId = in.assetId;
        wo.locationId = in.locationId;
        if (wo.locationId == null && wo.assetId != null) {
            wo.locationId = assets.findById(wo.assetId).map(a -> a.locationId).orElse(null);
        }
        wo.instructionId = in.instructionId;
        wo.pmScheduleId = in.pmScheduleId;
        wo.requestId = in.requestId;
        wo.dueDate = in.dueDate;
        wo.createdBy = createdBy;
        wo.status = WoStatus.OPEN;
        wo = wos.save(wo);
        if (assigneeIds != null && !assigneeIds.isEmpty()) setAssignees(wo, assigneeIds);
        audit.log("work_order", wo.id, "CREATE", wo.number + " " + wo.title);
        return wo;
    }

    /** Partial update: only the fields present in the request change. */
    @Transactional
    public WorkOrder update(Long id, Map<String, Object> changes) {
        WorkOrder wo = workable(id);
        Map<String, Object> allowed = new HashMap<>();
        changes.forEach((k, v) -> { if (UPDATABLE.contains(k)) allowed.put(k, v); });
        if (allowed.containsKey("instructionId") && !Objects.equals(toLong(allowed.get("instructionId")), wo.instructionId)
                && !stepResults.findByWorkOrderId(id).isEmpty()) {
            throw ApiException.conflict("Steps have already been checked on this work order, so its instruction can't be changed");
        }
        try {
            mapper.updateValue(wo, allowed);
        } catch (Exception e) {
            throw ApiException.bad("Invalid value: " + e.getMessage());
        }
        if (wo.title == null || wo.title.isBlank()) throw ApiException.bad("Title is required");
        if (wo.priority == null) wo.priority = Priority.MEDIUM;
        audit.log("work_order", id, "UPDATE", String.join(", ", allowed.keySet()));
        return wos.save(wo);
    }

    private static Long toLong(Object v) {
        return v == null ? null : Long.valueOf(v.toString());
    }

    @Transactional
    public WorkOrder assign(Long id, List<Long> userIds) {
        WorkOrder wo = editable(id);
        setAssignees(wo, userIds == null ? List.of() : userIds);
        return wos.save(wo);
    }

    private void setAssignees(WorkOrder wo, List<Long> userIds) {
        jdbc.update("DELETE FROM wo_assignments WHERE work_order_id = ?", wo.id);
        for (Long uid : new LinkedHashSet<>(userIds)) {
            User u = users.findById(uid).orElseThrow(() -> ApiException.notFound("User", uid));
            if (u.role == Role.REQUESTER) throw ApiException.bad(u.fullName + " is a requester and cannot be assigned work");
            if (!u.active) throw ApiException.bad(u.fullName + " is not active");
            jdbc.update("INSERT INTO wo_assignments(work_order_id, user_id) VALUES (?, ?)", wo.id, uid);
        }
        if (wo.status == WoStatus.OPEN && !userIds.isEmpty()) wo.status = WoStatus.ASSIGNED;
        if (wo.status == WoStatus.ASSIGNED && userIds.isEmpty()) wo.status = WoStatus.OPEN;
        wos.save(wo);
        audit.log("work_order", wo.id, "ASSIGN", userIds.isEmpty() ? "nobody" : String.join(", ",
                users.findAllById(userIds).stream().map(u -> u.fullName).toList()));
    }

    @Transactional
    public WorkOrder start(Long id, AuthUser by) {
        WorkOrder wo = workable(id);
        return startLocked(wo, by);
    }

    private WorkOrder startLocked(WorkOrder wo, AuthUser by) {
        wo.status = WoStatus.IN_PROGRESS;
        if (wo.startedAt == null) wo.startedAt = OffsetDateTime.now();
        // the person starting the job is automatically on it
        if (by.role() != Role.REQUESTER) {
            jdbc.update("INSERT INTO wo_assignments(work_order_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", wo.id, by.id());
        }
        audit.log("work_order", wo.id, "START", null);
        return wos.save(wo);
    }

    private void autoStart(WorkOrder wo, AuthUser by) {
        if (wo.status == WoStatus.OPEN || wo.status == WoStatus.ASSIGNED) startLocked(wo, by);
    }

    @Transactional
    public void setStep(Long woId, Long stepId, boolean done, String note, AuthUser by) {
        WorkOrder wo = workable(woId);
        InstructionStep s = steps.findById(stepId).orElseThrow(() -> ApiException.notFound("Step", stepId));
        if (!Objects.equals(s.instructionId, wo.instructionId)) throw ApiException.bad("Step does not belong to this work order's instruction");
        if (!s.active) throw ApiException.conflict("This step was removed from the instruction");
        WoStepResult r = stepResults.findByWorkOrderIdAndStepId(woId, stepId).orElseGet(WoStepResult::new);
        r.workOrderId = woId;
        r.stepId = stepId;
        r.done = done;
        r.note = note;
        r.doneBy = by.id();
        r.doneAt = OffsetDateTime.now();
        stepResults.save(r);
        autoStart(wo, by);
    }

    @Transactional
    public LaborEntry addLabor(Long woId, Long userId, BigDecimal hours, LocalDate date, String note, AuthUser by) {
        WorkOrder wo = workable(woId);
        Long uid = userId == null ? by.id() : userId;
        if (!uid.equals(by.id()) && !by.isSupervisor()) throw ApiException.forbidden("Technicians can only log their own hours");
        if (hours == null || hours.signum() <= 0) throw ApiException.bad("Hours must be greater than zero");
        if (hours.compareTo(BigDecimal.valueOf(24)) > 0) throw ApiException.bad("One entry cannot be more than 24 hours");
        if (date != null && date.isAfter(LocalDate.now().plusDays(1))) throw ApiException.bad("Work date cannot be in the future");
        User u = users.findById(uid).orElseThrow(() -> ApiException.notFound("User", uid));
        LaborEntry e = new LaborEntry();
        e.workOrderId = woId;
        e.userId = uid;
        e.hours = hours;
        e.hourlyRate = u.hourlyRate;
        e.workDate = date == null ? LocalDate.now() : date;
        e.note = note;
        audit.log("work_order", woId, "LABOR", u.fullName + " " + hours.stripTrailingZeros().toPlainString() + " h");
        autoStart(wo, by);
        return labor.save(e);
    }

    @Transactional
    public PartUsage addPart(Long woId, Long partId, BigDecimal qty, AuthUser by) {
        WorkOrder wo = workable(woId);
        if (partId == null) throw ApiException.bad("Choose a part");
        if (qty == null || qty.signum() <= 0) throw ApiException.bad("Quantity must be greater than zero");
        BigDecimal cost = partService.issue(partId, qty, wo.number, by.id());
        PartUsage u = new PartUsage();
        u.workOrderId = woId;
        u.partId = partId;
        u.qty = qty;
        u.unitCost = cost;
        u.usedBy = by.id();
        Part part = partService.get(partId);
        audit.log("work_order", woId, "PART", part.partNo + " " + part.name + " x " + qty.stripTrailingZeros().toPlainString());
        return partUsages.save(u);
    }

    @Transactional
    public OtherCost addOtherCost(Long woId, OtherCost in) {
        workable(woId);
        if (in.description == null || in.description.isBlank()) throw ApiException.bad("Description is required");
        if (in.amount == null || in.amount.signum() < 0) throw ApiException.bad("Amount must be zero or more");
        OtherCost c = new OtherCost();
        c.workOrderId = woId;
        c.description = in.description;
        c.vendor = in.vendor;
        c.amount = in.amount;
        c.costDate = in.costDate == null ? LocalDate.now() : in.costDate;
        audit.log("work_order", woId, "COST", c.description + " " + c.amount);
        return otherCosts.save(c);
    }

    @Transactional
    public void deleteLabor(Long woId, Long entryId, AuthUser by) {
        workable(woId);
        LaborEntry e = labor.findById(entryId).filter(x -> x.workOrderId.equals(woId))
                .orElseThrow(() -> ApiException.notFound("Labor entry", entryId));
        if (!e.userId.equals(by.id()) && !by.isSupervisor()) throw ApiException.forbidden("Cannot remove another person's hours");
        labor.delete(e);
        audit.log("work_order", woId, "LABOR_REMOVED", e.hours.stripTrailingZeros().toPlainString() + " h");
    }

    @Transactional
    public void deletePart(Long woId, Long usageId, AuthUser by) {
        WorkOrder wo = workable(woId);
        PartUsage u = partUsages.findById(usageId).filter(x -> x.workOrderId.equals(woId))
                .orElseThrow(() -> ApiException.notFound("Part usage", usageId));
        if (!Objects.equals(u.usedBy, by.id()) && !by.isSupervisor()) throw ApiException.forbidden("Cannot return parts another person used");
        partService.returnToStock(u.partId, u.qty, wo.number + " (returned)", by.id());
        partUsages.delete(u);
        audit.log("work_order", woId, "PART_RETURNED", "part " + u.partId + " x " + u.qty.stripTrailingZeros().toPlainString());
    }

    @Transactional
    public void deleteOtherCost(Long woId, Long costId) {
        workable(woId);
        OtherCost c = otherCosts.findById(costId).filter(x -> x.workOrderId.equals(woId))
                .orElseThrow(() -> ApiException.notFound("Cost", costId));
        otherCosts.delete(c);
    }

    private long missingRequiredSteps(Long id) {
        return detail(id).steps().stream().filter(s -> s.requiresCheck() && !s.done()).count();
    }

    @Transactional
    public WorkOrder complete(Long id, String notes, BigDecimal meterReading, AuthUser by) {
        WorkOrder wo = workable(id);
        long missing = missingRequiredSteps(id);
        if (missing > 0) throw ApiException.conflict(missing + " required instruction step(s) are not checked off yet");
        if (meterReading != null && meterReading.signum() < 0) throw ApiException.bad("Meter reading cannot be negative");
        wo.status = WoStatus.COMPLETED;
        wo.completedAt = OffsetDateTime.now();
        wo.completedBy = by.id();
        wo.completionNotes = notes;
        wo.meterAtCompletion = meterReading;
        if (wo.startedAt == null) wo.startedAt = wo.completedAt;
        if (meterReading != null && wo.assetId != null) {
            assets.findById(wo.assetId).ifPresent(a -> {
                if (meterReading.compareTo(a.meterReading) > 0) {
                    a.meterReading = meterReading;
                    assets.save(a);
                }
            });
        }
        audit.log("work_order", id, "COMPLETE", notes);
        return wos.save(wo);
    }

    /** Supervisor sends a completed job back to the technician (this unlocks it again). */
    @Transactional
    public WorkOrder reopen(Long id, String reason) {
        WorkOrder wo = lock(id);
        if (wo.status != WoStatus.COMPLETED) throw ApiException.conflict("Only completed work orders can be sent back");
        wo.status = WoStatus.IN_PROGRESS;
        wo.completedAt = null;
        wo.completedBy = null;
        wo.signOffNotes = reason;
        audit.log("work_order", id, "REOPEN", reason);
        return wos.save(wo);
    }

    @Transactional
    public WorkOrder signOff(Long id, String signatureName, String notes, AuthUser by) {
        if (!by.isSupervisor()) throw ApiException.forbidden("Only supervisors can sign off work");
        WorkOrder wo = lock(id);
        if (wo.status != WoStatus.COMPLETED) throw ApiException.conflict("Work order must be completed before sign-off");
        if (signatureName == null || signatureName.isBlank()) throw ApiException.bad("Type your full name to sign off");
        long missing = missingRequiredSteps(id);   // belt and braces: never sign an incomplete checklist
        if (missing > 0) throw ApiException.conflict(missing + " required instruction step(s) are not checked off yet");
        wo.status = WoStatus.SIGNED_OFF;
        wo.signedOffBy = by.id();
        wo.signedOffAt = OffsetDateTime.now();
        wo.signatureName = signatureName.trim();
        wo.signOffNotes = notes;
        if (wo.pmScheduleId != null) advanceSchedule(wo, wo.completedAt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate());
        audit.log("work_order", id, "SIGN_OFF", wo.signatureName);
        return wos.save(wo);
    }

    /** Moves the PM schedule to its next occurrence, counted from the given date (local server time zone). */
    private void advanceSchedule(WorkOrder wo, LocalDate from) {
        schedules.findById(wo.pmScheduleId).ifPresent(s -> {
            if (wo.status == WoStatus.SIGNED_OFF) s.lastCompletedAt = wo.completedAt;
            if (s.frequencyType == FrequencyType.METER) {
                BigDecimal base = wo.meterAtCompletion;
                if (base == null && wo.assetId != null) base = assets.findById(wo.assetId).map(a -> a.meterReading).orElse(null);
                if (base != null) s.nextMeter = base.add(BigDecimal.valueOf(s.intervalValue));
            } else {
                s.nextDue = PmMath.nextDue(from, s.frequencyType, s.intervalValue);
            }
            schedules.save(s);
        });
    }

    /**
     * Cancelling a PM work order skips that occurrence: the schedule moves on to the following due date,
     * otherwise the nightly job would immediately create the same work order again.
     */
    @Transactional
    public WorkOrder cancel(Long id, String reason) {
        WorkOrder wo = editable(id);
        wo.status = WoStatus.CANCELLED;
        wo.signOffNotes = reason;
        if (wo.pmScheduleId != null) {
            LocalDate from = wo.dueDate != null ? wo.dueDate : LocalDate.now();
            advanceSchedule(wo, from.isAfter(LocalDate.now()) ? from : LocalDate.now());
        }
        audit.log("work_order", id, "CANCEL", reason);
        return wos.save(wo);
    }

    /** Not signed off or cancelled (assignment and cancel are still allowed on a completed job). */
    private WorkOrder editable(Long id) {
        WorkOrder wo = lock(id);
        if (CLOSED.contains(wo.status)) throw ApiException.conflict("Work order " + wo.number + " is " + wo.status + " and can no longer be changed");
        return wo;
    }

    /** Open for work: checklist, labor, parts and costs can only change before the job is marked complete. */
    private WorkOrder workable(Long id) {
        WorkOrder wo = editable(id);
        if (wo.status == WoStatus.COMPLETED) {
            throw ApiException.conflict("Work order " + wo.number + " is completed. A supervisor must send it back before it can be changed");
        }
        return wo;
    }
}
