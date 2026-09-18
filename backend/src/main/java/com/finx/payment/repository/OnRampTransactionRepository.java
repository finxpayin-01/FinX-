package com.finx.payment.repository;

import com.finx.payment.entity.OnRampTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import com.finx.payment.entity.OnRampStatus;

@Repository
public interface OnRampTransactionRepository extends JpaRepository<OnRampTransaction, UUID> {
    Optional<OnRampTransaction> findByProviderTransactionId(String providerTransactionId);
    Optional<OnRampTransaction> findByQuoteId(String quoteId);
    Optional<OnRampTransaction> findByIdempotencyKey(String idempotencyKey);
    List<OnRampTransaction> findByStatusIn(List<OnRampStatus> statuses);
}
