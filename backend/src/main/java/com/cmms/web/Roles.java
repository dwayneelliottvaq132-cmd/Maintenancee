package com.cmms.web;

public final class Roles {
    private Roles() {}
    public static final String ADMIN = "hasRole('ADMIN')";
    public static final String SUPERVISOR = "hasAnyRole('ADMIN','SUPERVISOR')";
    public static final String STAFF = "hasAnyRole('ADMIN','SUPERVISOR','TECHNICIAN')";
}
