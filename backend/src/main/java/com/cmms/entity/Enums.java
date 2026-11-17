package com.cmms.entity;

public final class Enums {
    private Enums() {}
    public enum Role { ADMIN, SUPERVISOR, TECHNICIAN, REQUESTER }
    public enum AssetStatus { ACTIVE, DOWN, RETIRED }
    public enum Level { LOW, MEDIUM, HIGH }
    public enum Priority { LOW, MEDIUM, HIGH, URGENT }
    public enum FrequencyType { DAYS, WEEKS, MONTHS, METER }
    public enum RequestStatus { NEW, APPROVED, REJECTED }
    public enum WoType { PM, CORRECTIVE, REQUEST }
    public enum WoStatus { OPEN, ASSIGNED, IN_PROGRESS, COMPLETED, SIGNED_OFF, CANCELLED }
    public enum StockTxType { RECEIVE, ISSUE, ADJUST, RETURN }
}
