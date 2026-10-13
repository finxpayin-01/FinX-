package com.finx.release.observation.repository;

import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementObservationRepository extends JpaRepository<SettlementObservation, UUID> {

    List<SettlementObservation> findByExecutionAttemptIdOrderByObservedAtDesc(UUID executionAttemptId);

    Optional<SettlementObservation> findFirstByExecutionAttemptIdOrderByObservedAtDesc(UUID executionAttemptId);

    List<SettlementObservation> findByReleaseInstructionIdOrderByObservedAtDesc(UUID releaseInstructionId);

    Optional<SettlementObservation> findFirstByReleaseInstructionIdOrderByObservedAtDesc(UUID releaseInstructionId);

    Optional<SettlementObservation> findByWebhookEventId(UUID webhookEventId);

    List<SettlementObservation> findByProviderAndProviderSettlementIdOrderByObservedAtDesc(String provider, String providerSettlementId);

    List<SettlementObservation> findByProviderOperationKeyOrderByObservedAtDesc(String providerOperationKey);

    Optional<SettlementObservation> findByExecutionAttemptIdAndSourceAndEvidenceHash(
            UUID executionAttemptId,
            SettlementObservationSource source,
            String evidenceHash
    );
}
