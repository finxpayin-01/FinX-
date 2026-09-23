package com.finx.paymentorchestration.repository;

import com.finx.paymentorchestration.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByRefundReference(String refundReference);
    List<Refund> findByDealId(UUID dealId);
}
