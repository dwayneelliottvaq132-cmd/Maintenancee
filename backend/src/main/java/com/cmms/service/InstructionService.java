package com.cmms.service;

import com.cmms.entity.*;
import com.cmms.repo.*;
import com.cmms.web.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstructionService {
    public record StepIn(String text, boolean requiresCheck) {}
    public record PartIn(Long partId, BigDecimal qty) {}
    public record InstructionDto(WorkInstruction instruction, List<InstructionStep> steps, List<InstructionPart> parts) {}

    private final WorkInstructionRepository instructions;
    private final InstructionStepRepository steps;
    private final InstructionPartRepository parts;

    public InstructionService(WorkInstructionRepository instructions, InstructionStepRepository steps, InstructionPartRepository parts) {
        this.instructions = instructions;
        this.steps = steps;
        this.parts = parts;
    }

    public InstructionDto get(Long id) {
        WorkInstruction wi = instructions.findById(id).orElseThrow(() -> ApiException.notFound("Work instruction", id));
        return new InstructionDto(wi, steps.findByInstructionIdOrderBySeq(id), parts.findByInstructionId(id));
    }

    /**
     * Saves header, steps and parts. Editing an existing instruction bumps its version.
     * Steps that already have check-offs recorded on work orders are updated in place so history is kept.
     */
    @Transactional
    public InstructionDto save(Long id, WorkInstruction in, List<StepIn> stepIn, List<PartIn> partIn) {
        if (in.title == null || in.title.isBlank()) throw ApiException.bad("Title is required");
        WorkInstruction wi = id == null ? new WorkInstruction() : instructions.findById(id)
                .orElseThrow(() -> ApiException.notFound("Work instruction", id));
        if (id != null) wi.version = wi.version + 1;
        wi.title = in.title;
        wi.description = in.description;
        wi.safetyNotes = in.safetyNotes;
        wi.requiredPpe = in.requiredPpe;
        wi.toolsRequired = in.toolsRequired;
        wi.estimatedMinutes = in.estimatedMinutes;
        wi.updatedAt = OffsetDateTime.now();
        wi = instructions.save(wi);

        List<InstructionStep> existing = steps.findByInstructionIdOrderBySeq(wi.id);
        List<StepIn> wanted = stepIn == null ? List.of() : stepIn.stream().filter(s -> s.text() != null && !s.text().isBlank()).toList();
        for (int i = 0; i < wanted.size(); i++) {
            InstructionStep s = i < existing.size() ? existing.get(i) : new InstructionStep();
            s.instructionId = wi.id;
            s.seq = i + 1;
            s.text = wanted.get(i).text().trim();
            s.requiresCheck = wanted.get(i).requiresCheck();
            steps.save(s);
        }
        for (int i = wanted.size(); i < existing.size(); i++) steps.delete(existing.get(i));

        parts.deleteByInstructionId(wi.id);
        if (partIn != null) for (PartIn p : partIn) {
            if (p.partId() == null || p.qty() == null || p.qty().signum() <= 0) continue;
            InstructionPart ip = new InstructionPart();
            ip.instructionId = wi.id;
            ip.partId = p.partId();
            ip.qty = p.qty();
            parts.save(ip);
        }
        return get(wi.id);
    }
}
