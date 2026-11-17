package com.cmms.web;

import com.cmms.entity.MaintenanceRequest;
import com.cmms.security.AuthUser;
import com.cmms.security.Current;
import com.cmms.service.RequestService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/requests")
public class RequestController {
    public record ApproveIn(String notes, List<Long> assigneeIds, LocalDate dueDate, Long instructionId) {}
    public record RejectIn(String notes) {}

    private final RequestService service;

    public RequestController(RequestService service) { this.service = service; }

    @GetMapping
    public List<MaintenanceRequest> list(@RequestParam(defaultValue = "false") boolean mine) {
        return service.list(Current.user(), mine);
    }

    @GetMapping("/{id}")
    public MaintenanceRequest get(@PathVariable Long id) {
        MaintenanceRequest r = service.get(id);
        AuthUser u = Current.user();
        if (u.isRequesterOnly() && !r.requesterId.equals(u.id())) throw ApiException.forbidden("Not your request");
        return r;
    }

    /** Anyone who can log in may place a maintenance request. */
    @PostMapping
    public MaintenanceRequest create(@RequestBody MaintenanceRequest in) { return service.create(in, Current.user()); }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Roles.SUPERVISOR)
    public MaintenanceRequest approve(@PathVariable Long id, @RequestBody(required = false) ApproveIn in) {
        ApproveIn a = in == null ? new ApproveIn(null, List.of(), null, null) : in;
        return service.approve(id, a.notes(), a.assigneeIds(), a.dueDate(), a.instructionId(), Current.user());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Roles.SUPERVISOR)
    public MaintenanceRequest reject(@PathVariable Long id, @RequestBody RejectIn in) {
        return service.reject(id, in.notes(), Current.user());
    }
}
