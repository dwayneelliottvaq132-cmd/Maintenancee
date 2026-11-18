package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String username;
    @JsonIgnore public String passwordHash;
    public String fullName;
    public String email;
    @Enumerated(EnumType.STRING) public Role role;
    public BigDecimal hourlyRate = BigDecimal.ZERO;
    public String trade;
    public boolean active = true;
    public boolean mustChangePassword;
    @JsonIgnore public int tokenVersion;
    @Column(insertable = false, updatable = false) public OffsetDateTime createdAt;
}
