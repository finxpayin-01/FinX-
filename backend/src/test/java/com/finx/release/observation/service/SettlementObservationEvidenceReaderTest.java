package com.finx.release.observation.service;

import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementObservationEvidenceReaderTest {

    private SettlementObservationRepository repository;
    private SettlementObservationEvidenceReader reader;

    private UUID releaseInstructionId;
    private UUID attemptId;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementObservationRepository.class);
        reader = new SettlementObservationEvidenceReader(repository);
        releaseInstructionId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
    }

    private SettlementObservation buildObservation(
            SettlementObservationFinality finality,
            String reasonCode,
            String utr,
            Instant providerObservedAt,
            Instant observedAt
    ) {
        return SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("ref-" + UUID.randomUUID())
                .providerOperationKey("FINX-REL-" + releaseInstructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .providerStatus(finality.name())
                .finality(finality)
                .reasonCode(reasonCode)
                .utrNumber(utr)
                .providerObservedAt(providerObservedAt)
                .evidenceHash("hash-" + UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000")
                .observedAt(observedAt)
                .build();
    }

    @Test
    @DisplayName("Unambiguous FINAL_SUCCESS returns AuthoritativeStatus.FINAL_SUCCESS")
    void unambiguousFinalSuccess_returnsFinalSuccess() {
        Instant t1 = Instant.parse("2026-10-14T01:00:00Z");
        SettlementObservation obs = buildObservation(
                SettlementObservationFinality.FINAL_SUCCESS,
                SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED,
                "UTR-VALID",
                t1,
                t1
        );

        when(repository.findByReleaseInstructionIdOrderByObservedAtDesc(releaseInstructionId))
                .thenReturn(new ArrayList<>(List.of(obs)));

        SettlementObservationEvidenceReader.AuthoritativeObservationResult result =
                reader.getLatestAuthoritativeFinalObservation(releaseInstructionId);

        assertThat(result.status()).isEqualTo(SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS);
        assertThat(result.latestObservation()).isNotNull();
        assertThat(result.latestObservation().getUtrNumber()).isEqualTo("UTR-VALID");
    }

    @Test
    @DisplayName("Conflicting observations: FINAL_SUCCESS followed by REVERSED surfaces CONFLICT and never claims final success")
    void conflictingObservations_surfacesConflict_blocksFinalSuccess() {
        Instant t1 = Instant.parse("2026-10-14T01:00:00Z");
        Instant t2 = Instant.parse("2026-10-14T02:00:00Z");

        SettlementObservation obs1 = buildObservation(
                SettlementObservationFinality.FINAL_SUCCESS,
                SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED,
                "UTR-VALID",
                t1,
                t1
        );

        SettlementObservation obs2 = buildObservation(
                SettlementObservationFinality.REVERSED,
                SettlementObservationReasonCodes.SETTLEMENT_REVERSED,
                "UTR-VALID",
                t2,
                t2
        );

        when(repository.findByReleaseInstructionIdOrderByObservedAtDesc(releaseInstructionId))
                .thenReturn(new ArrayList<>(List.of(obs2, obs1)));

        SettlementObservationEvidenceReader.AuthoritativeObservationResult result =
                reader.getLatestAuthoritativeFinalObservation(releaseInstructionId);

        assertThat(result.status()).isEqualTo(SettlementObservationEvidenceReader.AuthoritativeStatus.CONFLICT);
        assertThat(result.details()).contains("Conflicting observations detected");
        assertThat(result.latestObservation()).isSameAs(obs2);
    }

    @Test
    @DisplayName("getLatestObservation orders by providerObservedAt then observedAt")
    void getLatestObservation_ordersByProviderObservedAt() {
        Instant t1 = Instant.parse("2026-10-14T01:00:00Z");
        Instant t2 = Instant.parse("2026-10-14T02:00:00Z");

        SettlementObservation older = buildObservation(SettlementObservationFinality.PENDING, "PENDING", null, t1, t1);
        SettlementObservation newer = buildObservation(SettlementObservationFinality.FINAL_SUCCESS, "CONFIRMED", "UTR-1", t2, t2);

        when(repository.findByExecutionAttemptIdOrderByObservedAtDesc(attemptId))
                .thenReturn(new ArrayList<>(List.of(older, newer)));

        Optional<SettlementObservation> latest = reader.getLatestObservation(attemptId);

        assertThat(latest).isPresent();
        assertThat(latest.get()).isSameAs(newer);
    }
}
