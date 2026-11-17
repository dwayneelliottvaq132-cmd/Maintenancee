package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "assets")
public class Asset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String tag;
    public String name;
    public String category;
    public Long locationId;
    public Long parentAssetId;
    public String manufacturer;
    public String model;
    public String serialNumber;
    public LocalDate installDate;
    public BigDecimal purchaseCost;
    public LocalDate warrantyUntil;
    @Enumerated(EnumType.STRING) public AssetStatus status = AssetStatus.ACTIVE;
    @Enumerated(EnumType.STRING) public Level criticality = Level.MEDIUM;
    public BigDecimal meterReading = BigDecimal.ZERO;
    public String meterUnit;
    public String notes;
    @Column(insertable = false, updatable = false) public OffsetDateTime createdAt;
}
