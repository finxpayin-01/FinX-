package com.finx.paymentorchestration.repository;

import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, UUID> {
    Optional<SettlementInstruction> findBySettlementReference(String settlementReference);
    Optional<SettlementInstruction> findByIdempotencyKey(String idempotencyKey);
    Optional<SettlementInstruction> findByDealIdAndIdempotencyKey(UUID dealId, String idempotencyKey);
    Optional<SettlementInstruction> findByMilestoneIdAndIdempotencyKey(UUID milestoneId, String idempotencyKey);
    Optional<SettlementInstruction> findFirstByMilestoneIdAndStatusIn(UUID milestoneId, List<SettlementStatus> statuses);
    Optional<SettlementInstruction> findByMilestoneIdAndStatus(UUID milestoneId, SettlementStatus status);
    List<SettlementInstruction> findByMilestoneIdOrderByCreatedAtDesc(UUID milestoneId);
    List<SettlementInstruction> findByDealIdOrderByCreatedAtDesc(UUID dealId);
    List<SettlementInstruction> findByStatus(SettlementStatus status);

    @Query("SELECT si FROM SettlementInstruction si " +
           "WHERE si.status = com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus.SETTLED " +
           "AND COALESCE(si.settledAt, si.createdAt) >= :windowStart " +
           "AND COALESCE(si.settledAt, si.createdAt) <= :windowEnd " +
           "AND si.provider = :provider " +
           "AND (si.providerAccountId = :providerAccountId OR si.providerAccountId IS NULL) " +
           "ORDER BY COALESCE(si.settledAt, si.createdAt) ASC, si.id ASC")
    List<SettlementInstruction> findOutboundReconciliationCandidates(
            @Param("provider") String provider,
            @Param("providerAccountId") String providerAccountId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);
}
