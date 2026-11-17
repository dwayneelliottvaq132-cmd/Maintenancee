package com.cmms.repo;

import com.cmms.entity.WorkInstruction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkInstructionRepository extends JpaRepository<WorkInstruction, Long> {

}
