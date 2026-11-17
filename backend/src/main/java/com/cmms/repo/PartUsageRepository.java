package com.cmms.repo;

import com.cmms.entity.PartUsage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartUsageRepository extends JpaRepository<PartUsage, Long> {
    List<PartUsage> findByWorkOrderId(Long workOrderId);
}
