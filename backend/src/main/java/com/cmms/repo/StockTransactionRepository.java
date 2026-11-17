package com.cmms.repo;

import com.cmms.entity.StockTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {
    List<StockTransaction> findByPartIdOrderByCreatedAtDesc(Long partId);
}
