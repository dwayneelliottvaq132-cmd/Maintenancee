package com.cmms.service;

import com.cmms.entity.*;
import com.cmms.repo.*;
import com.cmms.web.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstructionService {
    /** id is the existing step's id (null for a new step). */
    public record StepIn(Long id, String text, boolean requiresCheck) {}
    public record PartIn(Long partId, BigDecimal qty) {}
    public record InstructionDto(WorkInstruction instruction, List<InstructionStep> steps, List<InstructionPart> parts) {}

    private final WorkInstructionRepository instructions;
    private final InstructionStepRepository steps;
    private final InstructionPartRepository parts;
    private final WorkOrderRepository wos;
    private final PartRepository partRepo;
    private final JdbcTemplate jdbc;

    public InstructionService(WorkInstructionRepository instructions, InstructionStepRepository steps, InstructionPartRepository parts,
                              WorkOrderRepository wos, PartRepository partRepo, JdbcTemplate jdbc) {
        this.instructions = instructions;
        this.steps = steps;
        this.parts = parts;
        this.wos = wos;
        this.partRepo = partRepo;
        this.jdbc = jdbc;
    }

    /** The current (active) steps of an instruction. */
    public InstructionDto get(Long id) {
        WorkInstruction wi = instructions.findById(id).orElseThrow(() -> ApiException.notFound("Work instruction", id));
        List<InstructionStep> current = steps.findByInstructionIdOrderBySeq(id).stream().filter(s -> s.active).toList();
        return new InstructionDto(wi, current, parts.findByInstructionId(id));
    }

    private boolean hasResults(Long stepId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM wo_step_results WHERE step_id = ?", Integer.class, stepId);
        return n != null && n > 0;
    }

    /**
     * Saves header, steps and parts; editing bumps the version. Steps are matched by id, so reordering or
     * inserting never moves a recorded check-off onto different text. A step that work orders have already
     * checked is retired (kept for history) when it is removed or reworded, and a new step takes its place.
     */
    @Transactional
    public InstructionDto save(Long id, WorkInstruction in, List<StepIn> stepIn, List<PartIn> partIn) {
        if (in == null || in.title == null || in.title.isBlank()) throw ApiException.bad("Title is required");
        if (in.estimatedMinutes != null && in.estimatedMinutes < 0) throw ApiException.bad("Estimated minutes cannot be negative");
        WorkInstruction wi = id == null ? new WorkInstruction() : instructions.findById(id)
                .orElseThrow(() -> ApiException.notFound("Work instruction", id));
        if (id != null) wi.version = wi.version + 1;
        wi.title = in.title.trim();
        wi.description = in.description;
        wi.safetyNotes = in.safetyNotes;
        wi.requiredPpe = in.requiredPpe;
        wi.toolsRequired = in.toolsRequired;
        wi.estimatedMinutes = in.estimatedMinutes;
        wi.updatedAt = OffsetDateTime.now();
        wi = instructions.save(wi);

        Map<Long, InstructionStep> existing = new HashMap<>();
        for (InstructionStep s : steps.findByInstructionIdOrderBySeq(wi.id)) if (s.active) existing.put(s.id, s);
        List<StepIn> wanted = stepIn == null ? List.of() : stepIn.stream().filter(s -> s.text() != null && !s.text().isBlank()).toList();
        Set<Long> kept = new HashSet<>();
        int seq = 1;
        for (StepIn w : wanted) {
            String text = w.text().trim();
            InstructionStep s = w.id() == null ? null : existing.get(w.id());
            if (s != null && !s.text.equals(text) && hasResults(s.id)) {
                retire(s);          // reworded after use: keep the old wording for past jobs
                s = null;
            }
            if (s == null) {
                s = new InstructionStep();
                s.instructionId = wi.id;
            } else {
                kept.add(s.id);
            }
            s.seq = seq++;
            s.text = text;
            s.requiresCheck = w.requiresCheck();
            s.active = true;
            steps.save(s);
        }
        for (InstructionStep old : existing.values()) {
            if (kept.contains(old.id)) continue;
            if (hasResults(old.id)) retire(old); else steps.delete(old);
        }

        parts.deleteByInstructionId(wi.id);
        if (partIn != null) for (PartIn p : partIn) {
            if (p.partId() == null || p.qty() == null || p.qty().signum() <= 0) continue;
            if (!partRepo.existsById(p.partId())) throw ApiException.notFound("Part", p.partId());
            InstructionPart ip = new InstructionPart();
            ip.instructionId = wi.id;
            ip.partId = p.partId();
            ip.qty = p.qty();
            parts.save(ip);
        }
        return get(wi.id);
    }

    private void retire(InstructionStep s) {
        s.active = false;
        s.seq = 1000 + s.seq;   // keep out of the way of current steps
        steps.save(s);
    }

    /** Instructions used by work orders or PM schedules can't be deleted (history and checklists depend on them). */
    @Transactional
    public void delete(Long id) {
        instructions.findById(id).orElseThrow(() -> ApiException.notFound("Work instruction", id));
        if (wos.existsByInstructionId(id)) throw ApiException.conflict("This instruction is used by work orders and can't be deleted. Edit it instead");
        Integer pm = jdbc.queryForObject("SELECT COUNT(*) FROM pm_schedules WHERE instruction_id = ?", Integer.class, id);
        if (pm != null && pm > 0) throw ApiException.conflict("This instruction is used by PM schedules. Remove it from them first");
        instructions.deleteById(id);
    }
}
