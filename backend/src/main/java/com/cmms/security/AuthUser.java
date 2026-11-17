package com.cmms.security;

import com.cmms.entity.Enums.Role;

/** Principal stored in the security context for each authenticated request. */
public record AuthUser(Long id, String username, Role role) {
    public boolean isSupervisor() { return role == Role.ADMIN || role == Role.SUPERVISOR; }
    public boolean isAdmin() { return role == Role.ADMIN; }
    public boolean isRequesterOnly() { return role == Role.REQUESTER; }
}
