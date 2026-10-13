package com.finx.release.observation.service;

import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.repository.SettlementObservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Read-only service for evaluating authoritative settlement observation evidence.
 * Provides strict ordering by external provider observation time with deterministic tie-breaks.
 * Explicitly surfaces conflicts (e.g. FINAL_SUCCESS followed by REVERSED) and blocks unsafe finality assumptions.
 * Strictly read-only: does NOT finalize ledger, escrow, reservations, or instructions.
 */
@Service
@Transactional(readOnly = true)
public class SettlementObservationEvidenceReader {

    public enum AuthoritativeStatus {
        FINAL_SUCCESS,
        FAILED,
        REVERSED,
        PENDING,
        NOT_FOUND,
        UNKNOWN,
        CONFLICT,
        NO_EVIDENCE
    }

    public record AuthoritativeObservationResult(
            AuthoritativeStatus status,
            SettlementObservation latestObservation,
            String details
    ) {}

    private static final Comparator<SettlementObservation> OBSERVATION_ORDER = (o1, o2) -> {
        Instant t1 = o1.getProviderObservedAt() != null ? o1.getProviderObservedAt() : o1.getObservedAt();
        Instant t2 = o2.getProviderObservedAt() != null ? o2.getProviderObservedAt() : o2.getObservedAt();
        int cmp = t2.compareTo(t1); // descending
        if (cmp != 0) return cmp;

        cmp = o2.getObservedAt().compareTo(o1.getObservedAt());
        if (cmp != 0) return cmp;

        cmp = o2.getCreatedAt().compareTo(o1.getCreatedAt());
        if (cmp != 0) return cmp;

        return o2.getId().compareTo(o1.getId());
    };

    private final SettlementObservationRepository observationRepository;

    public SettlementObservationEvidenceReader(SettlementObservationRepository observationRepository) {
        this.observationRepository = observationRepository;
    }

    public Optional<SettlementObservation> getLatestObservation(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attemptId);
        if (observations.isEmpty()) {
            return Optional.empty();
        }
        observations.sort(OBSERVATION_ORDER);
        return Optional.of(observations.get(0));
    }

    public AuthoritativeObservationResult getLatestAuthoritativeFinalObservation(UUID releaseInstructionId) {
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        List<SettlementObservation> observations = observationRepository.findByReleaseInstructionIdOrderByObservedAtDesc(releaseInstructionId);
        if (observations.isEmpty()) {
            return new AuthoritativeObservationResult(AuthoritativeStatus.NO_EVIDENCE, null, "No observations found");
        }

        observations.sort(OBSERVATION_ORDER);
        SettlementObservation latest = observations.get(0);

        boolean hasFinalSuccess = false;
        boolean hasReversed = false;
        boolean hasFailed = false;

        for (SettlementObservation obs : observations) {
            if (obs.getFinality() == SettlementObservationFinality.FINAL_SUCCESS) {
                hasFinalSuccess = true;
            } else if (obs.getFinality() == SettlementObservationFinality.REVERSED) {
                hasReversed = true;
            } else if (obs.getFinality() == SettlementObservationFinality.FAILED) {
                hasFailed = true;
            }
        }

        // Conflict detection: If FINAL_SUCCESS exists alongside REVERSED or FAILED
        if (hasFinalSuccess && (hasReversed || hasFailed)) {
            return new AuthoritativeObservationResult(
                    AuthoritativeStatus.CONFLICT,
                    latest,
                    "Conflicting observations detected for release instruction: FINAL_SUCCESS exists alongside "
                            + (hasReversed ? "REVERSED" : "FAILED") + "; manual review required"
            );
        }

        switch (latest.getFinality()) {
            case FINAL_SUCCESS:
                return new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, latest, "Authoritative FINAL_SUCCESS confirmed with UTR " + latest.getUtrNumber());
            case FAILED:
                return new AuthoritativeObservationResult(AuthoritativeStatus.FAILED, latest, "Latest observation indicates FAILED: " + latest.getReasonCode());
            case REVERSED:
                return new AuthoritativeObservationResult(AuthoritativeStatus.REVERSED, latest, "Latest observation indicates REVERSED: " + latest.getReasonCode());
            case PENDING:
                return new AuthoritativeObservationResult(AuthoritativeStatus.PENDING, latest, "Settlement is still PENDING");
            case NOT_FOUND:
                return new AuthoritativeObservationResult(AuthoritativeStatus.NOT_FOUND, latest, "Settlement is NOT_FOUND at provider");
            default:
                return new AuthoritativeObservationResult(AuthoritativeStatus.UNKNOWN, latest, "Latest observation status is UNKNOWN: " + latest.getReasonCode());
        }
    }
}
