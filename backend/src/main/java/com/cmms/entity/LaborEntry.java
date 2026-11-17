package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "labor_entries")
public class LaborEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long workOrderId;
    public Long userId;
    public LocalDate workDate = LocalDate.now();
    public BigDecimal hours;
    public BigDecimal hourlyRate;
    public String note;
}
