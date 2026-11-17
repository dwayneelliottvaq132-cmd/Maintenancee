package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "pm_schedules")
public class PmSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public Long assetId;
    public Long instructionId;
    @Enumerated(EnumType.STRING) public FrequencyType frequencyType;
    public int intervalValue;
    public LocalDate nextDue;
    public BigDecimal nextMeter;
    public int leadDays;
    @Enumerated(EnumType.STRING) public Priority priority = Priority.MEDIUM;
    public Long defaultAssigneeId;
    public boolean active = true;
    public OffsetDateTime lastGeneratedAt;
    public OffsetDateTime lastCompletedAt;
}
