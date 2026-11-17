package com.cmms.repo;

import com.cmms.entity.PmSchedule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PmScheduleRepository extends JpaRepository<PmSchedule, Long> {
    List<PmSchedule> findByAssetId(Long assetId);
    List<PmSchedule> findByActiveTrue();
}
