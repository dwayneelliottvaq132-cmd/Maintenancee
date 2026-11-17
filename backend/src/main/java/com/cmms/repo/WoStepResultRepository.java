package com.cmms.repo;

import com.cmms.entity.WoStepResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WoStepResultRepository extends JpaRepository<WoStepResult, Long> {
    List<WoStepResult> findByWorkOrderId(Long workOrderId);
    java.util.Optional<WoStepResult> findByWorkOrderIdAndStepId(Long workOrderId, Long stepId);
}
