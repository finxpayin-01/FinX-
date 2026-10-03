package com.finx.paymentorchestration.repository;

import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentInstructionRepository extends JpaRepository<PaymentInstruction, UUID> {
    Optional<PaymentInstruction> findByInstructionReference(String instructionReference);
    Optional<PaymentInstruction> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentInstruction> findByDealIdAndIdempotencyKey(UUID dealId, String idempotencyKey);
    Optional<PaymentInstruction> findByProviderInstructionId(String providerInstructionId);
    List<PaymentInstruction> findByDealIdOrderByCreatedAtDesc(UUID dealId);
    List<PaymentInstruction> findByStatus(InstructionStatus status);
}
