package com.finx.reconciliation.repository;

import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, UUID> {
    List<ReconciliationItem> findByReconciliationRunId(UUID reconciliationRunId);

    List<ReconciliationItem> findByReconciliationRunIdAndResolutionStatus(
            UUID reconciliationRunId, com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus resolutionStatus);

    List<ReconciliationItem> findByBankStatementLineId(UUID bankStatementLineId);

    Optional<ReconciliationItem> findByReconciliationRunIdAndBankStatementLineId(
            UUID reconciliationRunId, UUID bankStatementLineId);

    Optional<ReconciliationItem> findByReconciliationRunIdAndInternalEffectTypeAndInternalEffectId(
            UUID reconciliationRunId, ReconciliationEffectType internalEffectType, UUID internalEffectId);

    List<ReconciliationItem> findByInternalEffectTypeAndInternalEffectId(
            ReconciliationEffectType internalEffectType, UUID internalEffectId);
}
