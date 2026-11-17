package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "other_costs")
public class OtherCost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long workOrderId;
    public String description;
    public String vendor;
    public BigDecimal amount;
    public LocalDate costDate = LocalDate.now();
}
