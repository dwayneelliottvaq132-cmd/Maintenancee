package com.cmms.service;

import com.cmms.entity.*;
import com.cmms.entity.Enums.*;
import com.cmms.repo.*;
import com.cmms.security.AuthUser;
import com.cmms.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {

    public record StepView(Long stepId, int seq, String text, boolean requiresCheck, boolean done,
                           String note, Long doneBy, OffsetDateTime doneAt) {}
    public record Totals(BigDecimal labor, BigDecimal parts, BigDecimal other, BigDecimal total, BigDecimal hours) {}
    public record Detail(WorkOrder workOrder, List<Long> assigneeIds, WorkInstruction instruction, List<StepView> steps,
                         List<LaborEntry> labor, List<PartUsage> parts, List<OtherCost> otherCosts, Totals totals) {}

    static final Set<WoStatus> CLOSED = EnumSet.of(WoStatus.SIGNED_OFF, WoStatus.CANCELLED);

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

    public WorkOrderService(WorkOrderRepository wos, WorkInstructionRepository instructions, InstructionStepRepository steps,
                            WoStepResultRepository stepResults, LaborEntryRepository labor, PartUsageRepository partUsages,
                            OtherCostRepository otherCosts, UserRepository users, AssetRepository assets,
                            PmScheduleRepository schedules, PartService partService, AuditService audit, JdbcTemplate jdbc) {
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
    }

    public WorkOrder get(Long id) {
        return wos.findById(id).orElseThrow(() -> ApiException.notFound("Work order", id));
    }

    public List<Map<String, Object>> list(String status, Long assetId, Long assigneeId, String type, boolean openOnly) {
        StringBuilder sql = new StringBuilder("""
            SELECT wo.id, wo.number, wo.title, wo.type, wo.priority, wo.status, wo.asset_id AS "assetId",
                   a.tag AS "assetTag", a.name AS "assetName", wo.due_date AS "dueDate", wo.created_at AS "createdAt",
                   wo.completed_at AS "completedAt", wo.signed_off_at AS "signedOffAt",
                   (wo.due_date < CURRENT_DATE AND wo.status NOT IN ('COMPLETED','SIGNED_OFF','CANCELLED')) AS overdue,
                   COALESCE((SELECT string_agg(u.full_name, ', ' ORDER BY u.full_name) FROM wo_assignments x
                              JOIN users u ON u.id = x.user_id WHERE x.work_order_id = wo.id), '') AS assignees,
                   COALESCE((SELECT SUM(amount) FROM v_cost_lines c WHERE c.work_order_id = wo.id), 0) AS "totalCost"
              FROM work_orders wo LEFT JOIN assets a ON a.id = wo.asset_id WHERE 1=1
            """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND wo.status = ?"); args.add(status); }
        if (type != null && !type.isBlank()) { sql.append(" AND wo.type = ?"); args.add(type); }
        if (assetId != null) { sql.append(" AND wo.asset_id = ?"); args.add(assetId); }
        if (openOnly) sql.append(" AND wo.status NOT IN ('SIGNED_OFF','CANCELLED')");
        if (assigneeId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM wo_assignments x WHERE x.work_order_id = wo.id AND x.user_id = ?)");
            args.add(assigneeId);
        }
        sql.append(" ORDER BY CASE wo.status WHEN 'SIGNED_OFF' THEN 2 WHEN 'CANCELLED' THEN 3 ELSE 1 END, wo.due_date NULLS LAST, wo.id DESC LIMIT 1000");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Detail detail(Long id) {
        WorkOrder wo = get(id);
        WorkInstruction wi = wo.instructionId == null ? null : instructions.findById(wo.instructionId).orElse(null);
        List<StepView> stepViews = new ArrayList<>();
        if (wi != null) {
            Map<Long, WoStepResult> results = new HashMap<>();
            stepResults.findByWorkOrderId(id).forEach(r -> results.put(r.stepId, r));
            for (InstructionStep s : steps.findByInstructionIdOrderBySeq(wi.id)) {
                WoStepResult r = results.get(s.id);
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
        return new Detail(wo, assigneeIds(id), wi, stepViews, l, p, o,
                new Totals(lab, par, oth, lab.add(par).add(oth), hrs));
    }

    public List<Long> assigneeIds(Long woId) {
        return jdbc.queryForList("SELECT user_id FROM wo_assignments WHERE work_order_id = ? ORDER BY user_id", Long.class, woId);
    }

    @Transactional
    public WorkOrder create(WorkOrder in, List<Long> assigneeIds, Long createdBy) {
        if (in.title == null || in.title.isBlank()) throw ApiException.bad("Title is required");
        WorkOrder wo = new WorkOrder();
        wo.number = "WO-" + jdbc.queryForObject("SELECT nextval('work_order_number_seq')", Long.class);
        wo.title = in.title;
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

    @Transactional
    public WorkOrder update(Long id, WorkOrder in) {
        WorkOrder wo = editable(id);
        wo.title = in.title != null ? in.title : wo.title;
        wo.description = in.description;
        if (in.priority != null) wo.priority = in.priority;
        wo.assetId = in.assetId;
        wo.locationId = in.locationId;
        wo.instructionId = in.instructionId;
        wo.dueDate = in.dueDate;
        return wos.save(wo);
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
        WorkOrder wo = editable(id);
        if (wo.status == WoStatus.COMPLETED) throw ApiException.conflict("Work order is already completed");
        wo.status = WoStatus.IN_PROGRESS;
        if (wo.startedAt == null) wo.startedAt = OffsetDateTime.now();
        // the person starting the job is automatically on it
        if (!assigneeIds(id).contains(by.id()) && by.role() != Role.REQUESTER) {
            jdbc.update("INSERT INTO wo_assignments(work_order_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, by.id());
        }
        audit.log("work_order", id, "START", null);
        return wos.save(wo);
    }

    @Transactional
    public void setStep(Long woId, Long stepId, boolean done, String note, AuthUser by) {
        WorkOrder wo = editable(woId);
        InstructionStep s = steps.findById(stepId).orElseThrow(() -> ApiException.notFound("Step", stepId));
        if (!Objects.equals(s.instructionId, wo.instructionId)) throw ApiException.bad("Step does not belong to this work order's instruction");
        WoStepResult r = stepResults.findByWorkOrderIdAndStepId(woId, stepId).orElseGet(WoStepResult::new);
        r.workOrderId = woId;
        r.stepId = stepId;
        r.done = done;
        r.note = note;
        r.doneBy = by.id();
        r.doneAt = OffsetDateTime.now();
        stepResults.save(r);
        if (wo.status == WoStatus.OPEN || wo.status == WoStatus.ASSIGNED) start(woId, by);
    }

    @Transactional
    public LaborEntry addLabor(Long woId, Long userId, BigDecimal hours, LocalDate date, String note, AuthUser by) {
        WorkOrder wo = editable(woId);
        Long uid = userId == null ? by.id() : userId;
        if (!uid.equals(by.id()) && !by.isSupervisor()) throw ApiException.forbidden("Technicians can only log their own hours");
        if (hours == null || hours.signum() <= 0) throw ApiException.bad("Hours must be greater than zero");
        User u = users.findById(uid).orElseThrow(() -> ApiException.notFound("User", uid));
        LaborEntry e = new LaborEntry();
        e.workOrderId = woId;
        e.userId = uid;
        e.hours = hours;
        e.hourlyRate = u.hourlyRate;
        e.workDate = date == null ? LocalDate.now() : date;
        e.note = note;
        audit.log("work_order", woId, "LABOR", u.fullName + " " + hours.stripTrailingZeros().toPlainString() + " h");
        if (wo.status == WoStatus.OPEN || wo.status == WoStatus.ASSIGNED) start(woId, by);
        return labor.save(e);
    }

    @Transactional
    public PartUsage addPart(Long woId, Long partId, BigDecimal qty, AuthUser by) {
        WorkOrder wo = editable(woId);
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
        editable(woId);
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
        editable(woId);
        LaborEntry e = labor.findById(entryId).filter(x -> x.workOrderId.equals(woId))
                .orElseThrow(() -> ApiException.notFound("Labor entry", entryId));
        if (!e.userId.equals(by.id()) && !by.isSupervisor()) throw ApiException.forbidden("Cannot remove another person's hours");
        labor.delete(e);
    }

    @Transactional
    public void deletePart(Long woId, Long usageId, AuthUser by) {
        WorkOrder wo = editable(woId);
        PartUsage u = partUsages.findById(usageId).filter(x -> x.workOrderId.equals(woId))
                .orElseThrow(() -> ApiException.notFound("Part usage", usageId));
        partService.returnToStock(u.partId, u.qty, wo.number + " (returned)", by.id());
        partUsages.delete(u);
    }

    @Transactional
    public void deleteOtherCost(Long woId, Long costId) {
        editable(woId);
        OtherCost c = otherCosts.findById(costId).filter(x -> x.workOrderId.equals(woId))
                .orElseThrow(() -> ApiException.notFound("Cost", costId));
        otherCosts.delete(c);
    }

    @Transactional
    public WorkOrder complete(Long id, String notes, BigDecimal meterReading, AuthUser by) {
        WorkOrder wo = editable(id);
        if (wo.status == WoStatus.COMPLETED) throw ApiException.conflict("Work order is already completed");
        Detail d = detail(id);
        long missing = d.steps().stream().filter(s -> s.requiresCheck() && !s.done()).count();
        if (missing > 0) throw ApiException.conflict(missing + " required instruction step(s) are not checked off yet");
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

    /** Supervisor sends a completed job back to the technician. */
    @Transactional
    public WorkOrder reopen(Long id, String reason) {
        WorkOrder wo = get(id);
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
        WorkOrder wo = get(id);
        if (wo.status != WoStatus.COMPLETED) throw ApiException.conflict("Work order must be completed before sign-off");
        if (signatureName == null || signatureName.isBlank()) throw ApiException.bad("Type your full name to sign off");
        wo.status = WoStatus.SIGNED_OFF;
        wo.signedOffBy = by.id();
        wo.signedOffAt = OffsetDateTime.now();
        wo.signatureName = signatureName.trim();
        wo.signOffNotes = notes;
        if (wo.pmScheduleId != null) advanceSchedule(wo);
        audit.log("work_order", id, "SIGN_OFF", wo.signatureName);
        return wos.save(wo);
    }

    private void advanceSchedule(WorkOrder wo) {
        schedules.findById(wo.pmScheduleId).ifPresent(s -> {
            LocalDate done = wo.completedAt.toLocalDate();
            s.lastCompletedAt = wo.completedAt;
            if (s.frequencyType == FrequencyType.METER) {
                BigDecimal base = wo.meterAtCompletion;
                if (base == null && wo.assetId != null) base = assets.findById(wo.assetId).map(a -> a.meterReading).orElse(null);
                if (base != null) s.nextMeter = base.add(BigDecimal.valueOf(s.intervalValue));
            } else {
                s.nextDue = PmMath.nextDue(done, s.frequencyType, s.intervalValue);
            }
            schedules.save(s);
        });
    }

    @Transactional
    public WorkOrder cancel(Long id, String reason) {
        WorkOrder wo = editable(id);
        wo.status = WoStatus.CANCELLED;
        wo.signOffNotes = reason;
        audit.log("work_order", id, "CANCEL", reason);
        return wos.save(wo);
    }

    private WorkOrder editable(Long id) {
        WorkOrder wo = get(id);
        if (CLOSED.contains(wo.status)) throw ApiException.conflict("Work order " + wo.number + " is " + wo.status + " and can no longer be changed");
        return wo;
    }
}
