package com.finx.reconciliation.repository;

import com.finx.reconciliation.entity.ReconciliationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, UUID> {
    List<ReconciliationItem> findByReconciliationRunId(UUID reconciliationRunId);
}
