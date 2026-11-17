package com.cmms.repo;

import com.cmms.entity.LaborEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaborEntryRepository extends JpaRepository<LaborEntry, Long> {
    List<LaborEntry> findByWorkOrderId(Long workOrderId);
}
