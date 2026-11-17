package com.cmms.repo;

import com.cmms.entity.MaintenanceRequest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceRequestRepository extends JpaRepository<MaintenanceRequest, Long> {
    List<MaintenanceRequest> findByRequesterIdOrderByCreatedAtDesc(Long requesterId);
    List<MaintenanceRequest> findAllByOrderByCreatedAtDesc();
}
