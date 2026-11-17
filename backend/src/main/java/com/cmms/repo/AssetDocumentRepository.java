package com.cmms.repo;

import com.cmms.entity.AssetDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetDocumentRepository extends JpaRepository<AssetDocument, Long> {
    List<AssetDocument> findByAssetIdOrderByUploadedAtDesc(Long assetId);
}
