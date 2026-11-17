package com.cmms.repo;

import com.cmms.entity.WorkOrder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    List<WorkOrder> findByAssetIdOrderByCreatedAtDesc(Long assetId);
    boolean existsByPmScheduleIdAndStatusNotIn(Long pmScheduleId, java.util.Collection<com.cmms.entity.Enums.WoStatus> statuses);
}
