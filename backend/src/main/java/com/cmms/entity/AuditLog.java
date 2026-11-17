package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String entity;
    public Long entityId;
    public String action;
    public Long userId;
    public String details;
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
