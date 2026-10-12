package com.finx.outbox.repository;

import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialOutboxRepository extends JpaRepository<FinancialOutboxMessage, UUID> {

    Optional<FinancialOutboxMessage> findByReleaseInstructionIdAndEventType(
            UUID releaseInstructionId,
            FinancialOutboxEventType eventType
    );

    Optional<FinancialOutboxMessage> findByEventTypeAndOperationKey(
            FinancialOutboxEventType eventType,
            String operationKey
    );

    long countByReleaseInstructionIdAndEventType(
            UUID releaseInstructionId,
            FinancialOutboxEventType eventType
    );
}
