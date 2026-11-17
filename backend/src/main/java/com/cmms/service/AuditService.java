package com.cmms.service;

import com.cmms.entity.AuditLog;
import com.cmms.repo.AuditLogRepository;
import com.cmms.security.AuthUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) { this.repo = repo; }

    public void log(String entity, Long id, String action, String details) {
        AuditLog a = new AuditLog();
        a.entity = entity;
        a.entityId = id;
        a.action = action;
        a.details = details;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser u) a.userId = u.id();
        repo.save(a);
    }
}
