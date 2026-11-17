package com.cmms.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class Current {
    private Current() {}
    public static AuthUser user() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser u)) {
            throw new com.cmms.web.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        return u;
    }
}
