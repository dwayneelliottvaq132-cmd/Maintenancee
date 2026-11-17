package com.cmms.entity;

import com.cmms.entity.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "asset_documents")
public class AssetDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long assetId;
    public String fileName;
    @JsonIgnore public String storedPath;
    public String contentType;
    public Long sizeBytes;
    public Long uploadedBy;
    public OffsetDateTime uploadedAt = OffsetDateTime.now();
}
