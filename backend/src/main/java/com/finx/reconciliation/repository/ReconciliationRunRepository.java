package com.finx.reconciliation.repository;

import com.finx.reconciliation.entity.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
    Optional<ReconciliationRun> findByRunReference(String runReference);
}
