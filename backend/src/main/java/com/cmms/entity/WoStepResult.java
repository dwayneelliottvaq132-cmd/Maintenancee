package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "wo_step_results")
public class WoStepResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long workOrderId;
    public Long stepId;
    public boolean done = true;
    public String note;
    public Long doneBy;
    public OffsetDateTime doneAt = OffsetDateTime.now();
}
