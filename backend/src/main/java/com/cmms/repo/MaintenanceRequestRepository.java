package com.cmms.repo;

import com.cmms.entity.MaintenanceRequest;
import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRequestRepository extends JpaRepository<MaintenanceRequest, Long> {
    List<MaintenanceRequest> findByRequesterIdOrderByCreatedAtDesc(Long requesterId);
    List<MaintenanceRequest> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MaintenanceRequest r where r.id = :id")
    Optional<MaintenanceRequest> lockById(@Param("id") Long id);
}
