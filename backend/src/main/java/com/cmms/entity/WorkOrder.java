package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "work_orders")
public class WorkOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String number;
    public String title;
    public String description;
    @Enumerated(EnumType.STRING) public WoType type;
    @Enumerated(EnumType.STRING) public Priority priority = Priority.MEDIUM;
    @Enumerated(EnumType.STRING) public WoStatus status = WoStatus.OPEN;
    public Long assetId;
    public Long locationId;
    public Long pmScheduleId;
    public Long requestId;
    public Long instructionId;
    public LocalDate dueDate;
    public Long createdBy;
    public OffsetDateTime createdAt = OffsetDateTime.now();
    public OffsetDateTime startedAt;
    public OffsetDateTime completedAt;
    public Long completedBy;
    public String completionNotes;
    public BigDecimal meterAtCompletion;
    public Long signedOffBy;
    public OffsetDateTime signedOffAt;
    public String signatureName;
    public String signOffNotes;
}
