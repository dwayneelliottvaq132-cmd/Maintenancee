package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "stock_transactions")
public class StockTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long partId;
    @Enumerated(EnumType.STRING) public StockTxType type;
    public BigDecimal qty;
    public BigDecimal unitCost;
    public String reference;
    public Long userId;
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
