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

    Optional<FinancialOutboxMessage> findByReleaseInstructionId(UUID releaseInstructionId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT m FROM FinancialOutboxMessage m WHERE m.releaseInstructionId = :releaseInstructionId")
    Optional<FinancialOutboxMessage> findByReleaseInstructionIdForUpdate(@org.springframework.data.repository.query.Param("releaseInstructionId") UUID releaseInstructionId);

    long countByStatus(com.finx.outbox.model.FinancialOutboxStatus status);

    @org.springframework.data.jpa.repository.Query("SELECT MIN(m.createdAt) FROM FinancialOutboxMessage m WHERE m.status = com.finx.outbox.model.FinancialOutboxStatus.PENDING")
    Optional<java.time.Instant> findOldestPendingCreatedAt();

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(m) FROM FinancialOutboxMessage m WHERE m.status = com.finx.outbox.model.FinancialOutboxStatus.LEASED AND m.lockedAt < :staleThreshold")
    long countStaleLeasedMessages(@org.springframework.data.repository.query.Param("staleThreshold") java.time.Instant staleThreshold);

    java.util.List<FinancialOutboxMessage> findByStatusOrderByCreatedAtDesc(com.finx.outbox.model.FinancialOutboxStatus status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT m FROM FinancialOutboxMessage m WHERE m.id = :id")
    Optional<FinancialOutboxMessage> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM financial_outbox " +
            "WHERE status IN ('PENDING', 'RETRY') AND available_at <= CAST(:now AS TIMESTAMP WITH TIME ZONE) " +
            "ORDER BY available_at ASC, created_at ASC, id ASC " +
            "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    java.util.List<FinancialOutboxMessage> claimEligibleMessages(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("limit") int limit
    );

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM financial_outbox " +
            "WHERE status = 'LEASED' AND locked_at < CAST(:expiredThreshold AS TIMESTAMP WITH TIME ZONE) " +
            "ORDER BY locked_at ASC, id ASC " +
            "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    java.util.List<FinancialOutboxMessage> findExpiredLeasedMessages(
            @org.springframework.data.repository.query.Param("expiredThreshold") java.time.Instant expiredThreshold,
            @org.springframework.data.repository.query.Param("limit") int limit
    );
}
