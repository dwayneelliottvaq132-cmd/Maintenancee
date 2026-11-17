package com.cmms.repo;

import com.cmms.entity.Location;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, Long> {

}
