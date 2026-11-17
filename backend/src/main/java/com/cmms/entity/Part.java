package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "parts")
public class Part {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String partNo;
    public String name;
    public String unit = "ea";
    public BigDecimal unitCost = BigDecimal.ZERO;
    public BigDecimal qtyOnHand = BigDecimal.ZERO;
    public BigDecimal reorderPoint = BigDecimal.ZERO;
    public String binLocation;
    public String supplier;
    public boolean active = true;
}
