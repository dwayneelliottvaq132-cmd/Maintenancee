package com.cmms.service;

import com.cmms.entity.*;
import com.cmms.entity.Enums.*;
import com.cmms.repo.*;
import com.cmms.security.AuthUser;
import com.cmms.web.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestService {
    private final MaintenanceRequestRepository requests;
    private final WorkOrderService woService;
    private final AuditService audit;

    public RequestService(MaintenanceRequestRepository requests, WorkOrderService woService, AuditService audit) {
        this.requests = requests;
        this.woService = woService;
        this.audit = audit;
    }

    public MaintenanceRequest get(Long id) {
        return requests.findById(id).orElseThrow(() -> ApiException.notFound("Request", id));
    }

    public List<MaintenanceRequest> list(AuthUser user, boolean mineOnly) {
        if (user.isRequesterOnly() || mineOnly) return requests.findByRequesterIdOrderByCreatedAtDesc(user.id());
        return requests.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public MaintenanceRequest create(MaintenanceRequest in, AuthUser by) {
        if (in.title == null || in.title.isBlank()) throw ApiException.bad("Please describe the problem in the title");
        MaintenanceRequest r = new MaintenanceRequest();
        r.title = in.title;
        r.description = in.description;
        r.assetId = in.assetId;
        r.locationId = in.locationId;
        r.priority = in.priority == null ? Priority.MEDIUM : in.priority;
        r.requesterId = by.id();
        r.status = RequestStatus.NEW;
        r = requests.save(r);
        audit.log("request", r.id, "CREATE", r.title);
        return r;
    }

    /** Approve a request: creates a REQUEST work order linked back to it. */
    @Transactional
    public MaintenanceRequest approve(Long id, String notes, List<Long> assigneeIds, java.time.LocalDate dueDate,
                                      Long instructionId, AuthUser by) {
        MaintenanceRequest r = get(id);
        if (r.status != RequestStatus.NEW) throw ApiException.conflict("Request has already been " + r.status.name().toLowerCase());
        WorkOrder in = new WorkOrder();
        in.title = r.title;
        in.description = r.description;
        in.type = WoType.REQUEST;
        in.priority = r.priority;
        in.assetId = r.assetId;
        in.locationId = r.locationId;
        in.requestId = r.id;
        in.dueDate = dueDate;
        in.instructionId = instructionId;
        WorkOrder wo = woService.create(in, assigneeIds, by.id());
        r.status = RequestStatus.APPROVED;
        r.reviewedBy = by.id();
        r.reviewedAt = OffsetDateTime.now();
        r.reviewNotes = notes;
        r.workOrderId = wo.id;
        audit.log("request", r.id, "APPROVE", wo.number);
        return requests.save(r);
    }

    @Transactional
    public MaintenanceRequest reject(Long id, String notes, AuthUser by) {
        MaintenanceRequest r = get(id);
        if (r.status != RequestStatus.NEW) throw ApiException.conflict("Request has already been " + r.status.name().toLowerCase());
        if (notes == null || notes.isBlank()) throw ApiException.bad("Give a reason for rejecting the request");
        r.status = RequestStatus.REJECTED;
        r.reviewedBy = by.id();
        r.reviewedAt = OffsetDateTime.now();
        r.reviewNotes = notes;
        audit.log("request", r.id, "REJECT", notes);
        return requests.save(r);
    }
}
