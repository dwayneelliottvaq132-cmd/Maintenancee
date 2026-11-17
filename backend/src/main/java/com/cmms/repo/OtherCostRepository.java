package com.cmms.repo;

import com.cmms.entity.OtherCost;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtherCostRepository extends JpaRepository<OtherCost, Long> {
    List<OtherCost> findByWorkOrderId(Long workOrderId);
}
