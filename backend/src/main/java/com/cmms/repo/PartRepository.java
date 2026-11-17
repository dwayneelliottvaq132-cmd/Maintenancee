package com.cmms.repo;

import com.cmms.entity.Part;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartRepository extends JpaRepository<Part, Long> {

}
