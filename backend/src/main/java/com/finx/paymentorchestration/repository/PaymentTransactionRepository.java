package com.finx.paymentorchestration.repository;

import com.finx.paymentorchestration.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);
    Optional<PaymentTransaction> findByUtrNumber(String utrNumber);
    Optional<PaymentTransaction> findByProviderAndUtrNumber(String provider, String utrNumber);
    List<PaymentTransaction> findByPaymentInstructionId(UUID paymentInstructionId);

    @Query("SELECT pt FROM PaymentTransaction pt, PaymentInstruction pi " +
           "WHERE pt.paymentInstructionId = pi.id " +
           "AND pt.status = 'SUCCESS' " +
           "AND pt.createdAt >= :windowStart AND pt.createdAt <= :windowEnd " +
           "AND pi.provider = :provider " +
           "AND (pi.providerAccountId = :providerAccountId OR pi.providerAccountId IS NULL) " +
           "ORDER BY pt.createdAt ASC, pt.id ASC")
    List<PaymentTransaction> findInboundReconciliationCandidates(
            @Param("provider") String provider,
            @Param("providerAccountId") String providerAccountId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);
}
