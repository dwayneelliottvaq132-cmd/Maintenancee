package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "instruction_parts")
public class InstructionPart {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long instructionId;
    public Long partId;
    public BigDecimal qty;
}
