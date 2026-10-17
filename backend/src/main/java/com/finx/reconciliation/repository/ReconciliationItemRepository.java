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

    long countByStatus(com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus status);

    long countByReasonCode(com.finx.reconciliation.enums.ReconciliationReasonCode reasonCode);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(ri) FROM ReconciliationItem ri WHERE ri.status <> com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus.MATCHED AND ri.resolutionStatus = com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus.UNRESOLVED")
    long countBlockedReconciliationItems();

    List<ReconciliationItem> findByStatusAndResolutionStatus(
            com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus status,
            com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus resolutionStatus);

    List<ReconciliationItem> findByReasonCode(com.finx.reconciliation.enums.ReconciliationReasonCode reasonCode);
}
