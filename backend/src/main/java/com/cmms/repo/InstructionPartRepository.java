package com.cmms.repo;

import com.cmms.entity.InstructionPart;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstructionPartRepository extends JpaRepository<InstructionPart, Long> {
    List<InstructionPart> findByInstructionId(Long instructionId);
    void deleteByInstructionId(Long instructionId);
}
