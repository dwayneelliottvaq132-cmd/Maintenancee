package com.cmms.web;

import com.cmms.entity.*;
import com.cmms.security.Current;
import com.cmms.service.WorkOrderService;
import com.cmms.service.WorkOrderService.Detail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work-orders")
@PreAuthorize(Roles.STAFF)
public class WorkOrderController {
    public record CreateIn(WorkOrder workOrder, List<Long> assigneeIds) {}
    public record AssignIn(List<Long> userIds) {}
    public record StepIn(boolean done, String note) {}
    public record LaborIn(Long userId, BigDecimal hours, LocalDate workDate, String note) {}
    public record PartIn(Long partId, BigDecimal qty) {}
    public record CompleteIn(String notes, BigDecimal meterReading) {}
    public record SignOffIn(String signatureName, String notes) {}
    public record ReasonIn(String reason) {}

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) { this.service = service; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long assetId,
                                          @RequestParam(required = false) Long assigneeId,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(defaultValue = "false") boolean mine,
                                          @RequestParam(defaultValue = "false") boolean open) {
        return service.list(status, assetId, mine ? Current.user().id() : assigneeId, type, open);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable Long id) { return service.detail(id, Current.user().isSupervisor()); }

    @PostMapping
    public WorkOrder create(@RequestBody CreateIn in) {
        return service.create(in.workOrder(), in.assigneeIds(), Current.user().id());
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public WorkOrder update(@PathVariable Long id, @RequestBody Map<String, Object> changes) { return service.update(id, changes); }

    @PostMapping("/{id}/assign")
    @PreAuthorize(Roles.SUPERVISOR)
    public WorkOrder assign(@PathVariable Long id, @RequestBody AssignIn in) { return service.assign(id, in.userIds()); }

    @PostMapping("/{id}/start")
    public WorkOrder start(@PathVariable Long id) { return service.start(id, Current.user()); }

    @PutMapping("/{id}/steps/{stepId}")
    public Detail step(@PathVariable Long id, @PathVariable Long stepId, @RequestBody StepIn in) {
        service.setStep(id, stepId, in.done(), in.note(), Current.user());
        return service.detail(id, Current.user().isSupervisor());
    }

    @PostMapping("/{id}/labor")
    public LaborEntry labor(@PathVariable Long id, @RequestBody LaborIn in) {
        return service.addLabor(id, in.userId(), in.hours(), in.workDate(), in.note(), Current.user());
    }

    @DeleteMapping("/{id}/labor/{entryId}")
    public void deleteLabor(@PathVariable Long id, @PathVariable Long entryId) { service.deleteLabor(id, entryId, Current.user()); }

    @PostMapping("/{id}/parts")
    public PartUsage part(@PathVariable Long id, @RequestBody PartIn in) {
        return service.addPart(id, in.partId(), in.qty(), Current.user());
    }

    @DeleteMapping("/{id}/parts/{usageId}")
    public void deletePart(@PathVariable Long id, @PathVariable Long usageId) { service.deletePart(id, usageId, Current.user()); }

    @PostMapping("/{id}/costs")
    public OtherCost cost(@PathVariable Long id, @RequestBody OtherCost in) { return service.addOtherCost(id, in); }

    @DeleteMapping("/{id}/costs/{costId}")
    @PreAuthorize(Roles.SUPERVISOR)
    public void deleteCost(@PathVariable Long id, @PathVariable Long costId) { service.deleteOtherCost(id, costId); }

    @PostMapping("/{id}/complete")
    public WorkOrder complete(@PathVariable Long id, @RequestBody CompleteIn in) {
        return service.complete(id, in.notes(), in.meterReading(), Current.user());
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize(Roles.SUPERVISOR)
    public WorkOrder reopen(@PathVariable Long id, @RequestBody ReasonIn in) { return service.reopen(id, in.reason()); }

    @PostMapping("/{id}/sign-off")
    @PreAuthorize(Roles.SUPERVISOR)
    public WorkOrder signOff(@PathVariable Long id, @RequestBody SignOffIn in) {
        return service.signOff(id, in.signatureName(), in.notes(), Current.user());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Roles.SUPERVISOR)
    public WorkOrder cancel(@PathVariable Long id, @RequestBody ReasonIn in) { return service.cancel(id, in.reason()); }
}
