package com.cmms.repo;

import com.cmms.entity.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    java.util.Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
