package com.finx.paymentorchestration.repository;

import com.finx.paymentorchestration.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);
    Optional<PaymentTransaction> findByUtrNumber(String utrNumber);
    List<PaymentTransaction> findByPaymentInstructionId(UUID paymentInstructionId);
}
