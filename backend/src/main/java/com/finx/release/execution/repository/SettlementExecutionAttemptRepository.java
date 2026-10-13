package com.finx.release.execution.repository;

import com.finx.release.execution.entity.SettlementExecutionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementExecutionAttemptRepository extends JpaRepository<SettlementExecutionAttempt, UUID> {

    List<SettlementExecutionAttempt> findByOutboxMessageIdOrderByAttemptNumberDesc(UUID outboxMessageId);

    Optional<SettlementExecutionAttempt> findFirstByOutboxMessageIdOrderByAttemptNumberDesc(UUID outboxMessageId);

    List<SettlementExecutionAttempt> findByReleaseInstructionIdOrderByAttemptNumberDesc(UUID releaseInstructionId);

    Optional<SettlementExecutionAttempt> findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(UUID releaseInstructionId);

    Optional<SettlementExecutionAttempt> findByProviderAndProviderSettlementId(String provider, String providerSettlementId);

    long countByOutboxMessageId(UUID outboxMessageId);

    @Query("SELECT COALESCE(MAX(a.attemptNumber), 0) FROM SettlementExecutionAttempt a WHERE a.outboxMessageId = :outboxMessageId")
    int maxAttemptNumberForOutbox(@Param("outboxMessageId") UUID outboxMessageId);
}
