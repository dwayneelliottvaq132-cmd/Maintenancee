package com.cmms.web;

import com.cmms.entity.WorkInstruction;
import com.cmms.repo.WorkInstructionRepository;
import com.cmms.service.InstructionService;
import com.cmms.service.InstructionService.*;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/instructions")
public class InstructionController {
    public record InstructionIn(WorkInstruction instruction, List<StepIn> steps, List<PartIn> parts) {}

    private final WorkInstructionRepository repo;
    private final InstructionService service;

    public InstructionController(WorkInstructionRepository repo, InstructionService service) {
        this.repo = repo;
        this.service = service;
    }

    @GetMapping
    public List<WorkInstruction> list() { return repo.findAll(Sort.by("title")); }

    @GetMapping("/{id}")
    public InstructionDto get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    @PreAuthorize(Roles.SUPERVISOR)
    public InstructionDto create(@RequestBody InstructionIn in) {
        return service.save(null, in.instruction(), in.steps(), in.parts());
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public InstructionDto update(@PathVariable Long id, @RequestBody InstructionIn in) {
        return service.save(id, in.instruction(), in.steps(), in.parts());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public void delete(@PathVariable Long id) { service.delete(id); }
}
