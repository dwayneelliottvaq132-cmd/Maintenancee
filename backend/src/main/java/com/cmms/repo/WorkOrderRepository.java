package com.cmms.repo;

import com.cmms.entity.WorkOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    List<WorkOrder> findByAssetIdOrderByCreatedAtDesc(Long assetId);
    boolean existsByPmScheduleIdAndStatusNotIn(Long pmScheduleId, java.util.Collection<com.cmms.entity.Enums.WoStatus> statuses);
    boolean existsByAssetId(Long assetId);
    boolean existsByLocationId(Long locationId);
    boolean existsByInstructionId(Long instructionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrder w where w.id = :id")
    Optional<WorkOrder> lockById(@Param("id") Long id);
}
