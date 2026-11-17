package com.cmms.repo;

import com.cmms.entity.InstructionStep;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstructionStepRepository extends JpaRepository<InstructionStep, Long> {
    List<InstructionStep> findByInstructionIdOrderBySeq(Long instructionId);
    void deleteByInstructionId(Long instructionId);
}
