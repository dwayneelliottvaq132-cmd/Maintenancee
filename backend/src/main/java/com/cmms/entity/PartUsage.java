package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "part_usages")
public class PartUsage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long workOrderId;
    public Long partId;
    public BigDecimal qty;
    public BigDecimal unitCost;
    public Long usedBy;
    public OffsetDateTime usedAt = OffsetDateTime.now();
}
