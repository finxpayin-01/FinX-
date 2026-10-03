package com.finx.paymentorchestration.repository;

import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
