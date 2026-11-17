package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "work_instructions")
public class WorkInstruction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public int version = 1;
    public String description;
    public String safetyNotes;
    public String requiredPpe;
    public String toolsRequired;
    public Integer estimatedMinutes;
    public OffsetDateTime updatedAt = OffsetDateTime.now();
}
