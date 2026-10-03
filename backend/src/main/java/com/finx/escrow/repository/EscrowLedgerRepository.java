package com.finx.escrow.repository;

import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EscrowLedgerRepository extends JpaRepository<EscrowLedger, UUID> {
    List<EscrowLedger> findByEscrowAccountIdOrderByCreatedAtDesc(UUID escrowAccountId);
    Optional<EscrowLedger> findByMilestoneIdAndTransactionType(UUID milestoneId, TransactionType transactionType);
    Optional<EscrowLedger> findByPaymentInstructionId(UUID paymentInstructionId);
    Optional<EscrowLedger> findByPaymentId(UUID paymentId);
}
