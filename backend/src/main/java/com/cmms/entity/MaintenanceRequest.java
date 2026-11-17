package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "maintenance_requests")
public class MaintenanceRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public String description;
    public Long requesterId;
    public Long assetId;
    public Long locationId;
    @Enumerated(EnumType.STRING) public Priority priority = Priority.MEDIUM;
    @Enumerated(EnumType.STRING) public RequestStatus status = RequestStatus.NEW;
    public Long reviewedBy;
    public OffsetDateTime reviewedAt;
    public String reviewNotes;
    public Long workOrderId;
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
