package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "instruction_steps")
public class InstructionStep {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long instructionId;
    public int seq;
    public String text;
    public boolean requiresCheck = true;
    public boolean active = true;
}
