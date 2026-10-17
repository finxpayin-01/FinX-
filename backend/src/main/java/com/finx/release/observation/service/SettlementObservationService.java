package com.finx.release.observation.service;

import com.finx.exception.ResourceNotFoundException;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationFingerprint;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Service responsible for recording immutable trusted settlement observations.
 * Validates binding to real SettlementExecutionAttempt and ReleaseInstruction.
 * Computes deterministic evidence hash and provides idempotent persistence.
 * Strict boundary: ZERO ledger writes, ZERO escrow mutations, ZERO reservation mutation.
 */
@Service
public class SettlementObservationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementObservationService.class);

    private final SettlementObservationRepository observationRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final ReleaseInstructionRepository instructionRepository;

    public SettlementObservationService(
            SettlementObservationRepository observationRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            ReleaseInstructionRepository instructionRepository
    ) {
        this.observationRepository = observationRepository;
        this.attemptRepository = attemptRepository;
        this.instructionRepository = instructionRepository;
    }

    @Transactional
    public SettlementObservation recordObservation(RecordObservationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.executionAttemptId(), "executionAttemptId must not be null");
        Objects.requireNonNull(command.source(), "source must not be null");
        Objects.requireNonNull(command.sourceReference(), "sourceReference must not be null");

        SettlementExecutionAttempt attempt = attemptRepository.findById(command.executionAttemptId())
                .orElseThrow(() -> new ResourceNotFoundException("SettlementExecutionAttempt not found: " + command.executionAttemptId()));

        ReleaseInstruction instruction = instructionRepository.findById(attempt.getReleaseInstructionId())
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction not found: " + attempt.getReleaseInstructionId()));

        if (command.releaseInstructionId() != null && !command.releaseInstructionId().equals(instruction.getId())) {
            throw new IllegalArgumentException("Command releaseInstructionId does not match attempt's releaseInstructionId");
        }

        // Normalize raw status
        SettlementStatusNormalizer.NormalizedStatus normalized = SettlementStatusNormalizer.normalize(
                command.rawProviderStatus(),
                command.providerSettlementId(),
                command.utrNumber()
        );

        SettlementObservationFinality finality = normalized.finality();
        String reasonCode = normalized.reasonCode();

        // Validate provider binding
        boolean providerMismatch = command.provider() != null
                && !command.provider().trim().equalsIgnoreCase(attempt.getProvider());

        boolean opKeyMismatch = command.providerOperationKey() != null
                && !command.providerOperationKey().trim().equals(attempt.getProviderOperationKey());

        // Identity and semantic checks against attempt snapshot
        if (providerMismatch || opKeyMismatch) {
            finality = SettlementObservationFinality.UNKNOWN;
            reasonCode = SettlementObservationReasonCodes.SETTLEMENT_REFERENCE_MISMATCH;
        } else if (attempt.getProviderSettlementId() != null
                && command.providerSettlementId() != null
                && !command.providerSettlementId().isBlank()
                && !command.providerSettlementId().trim().equals(attempt.getProviderSettlementId())) {
            finality = SettlementObservationFinality.UNKNOWN;
            reasonCode = SettlementObservationReasonCodes.SETTLEMENT_REFERENCE_MISMATCH;
        } else if (command.providerAccountId() != null
                && !command.providerAccountId().isBlank()
                && !command.providerAccountId().trim().equalsIgnoreCase(attempt.getProviderAccountId())) {
            finality = SettlementObservationFinality.UNKNOWN;
            reasonCode = SettlementObservationReasonCodes.SETTLEMENT_PROVIDER_ACCOUNT_MISMATCH;
        } else if (command.observedAmount() != null
                && command.observedAmount().compareTo(attempt.getAmount()) != 0) {
            finality = SettlementObservationFinality.UNKNOWN;
            reasonCode = SettlementObservationReasonCodes.SETTLEMENT_AMOUNT_MISMATCH;
        } else if (command.observedCurrency() != null
                && !command.observedCurrency().trim().equalsIgnoreCase(attempt.getCurrency())) {
            finality = SettlementObservationFinality.UNKNOWN;
            reasonCode = SettlementObservationReasonCodes.SETTLEMENT_CURRENCY_MISMATCH;
        } else if (command.observedDirection() != null
                && !command.observedDirection().trim().equalsIgnoreCase("DEBIT")) {
            finality = SettlementObservationFinality.UNKNOWN;
            reasonCode = SettlementObservationReasonCodes.SETTLEMENT_DIRECTION_MISMATCH;
        }

        // Calculate deterministic canonical evidence hash
        String evidenceHash = SettlementObservationFingerprint.calculate(
                instruction.getId(),
                attempt.getId(),
                attempt.getProvider(),
                command.source(),
                command.sourceReference(),
                attempt.getProviderOperationKey(),
                command.providerSettlementId(),
                command.providerAccountId(),
                command.rawProviderStatus(),
                finality,
                reasonCode,
                command.utrNumber(),
                command.observedAmount(),
                command.observedCurrency() != null ? command.observedCurrency().trim().toUpperCase(Locale.ROOT) : null,
                command.observedDirection() != null ? command.observedDirection().trim().toUpperCase(Locale.ROOT) : null,
                command.providerObservedAt()
        );

        // Deduplication check
        Optional<SettlementObservation> existing = observationRepository.findByExecutionAttemptIdAndSourceAndEvidenceHash(
                attempt.getId(),
                command.source(),
                evidenceHash
        );
        if (existing.isPresent()) {
            log.info("Duplicate settlement observation ignored for attempt {} source {} hash {}",
                    attempt.getId(), command.source(), evidenceHash);
            return existing.get();
        }

        Instant observedAt = command.observedAt() != null ? command.observedAt() : Instant.now();

        SettlementObservation observation = SettlementObservation.builder()
                .releaseInstructionId(instruction.getId())
                .executionAttemptId(attempt.getId())
                .webhookEventId(command.webhookEventId())
                .provider(attempt.getProvider())
                .source(command.source())
                .sourceReference(command.sourceReference().trim())
                .providerOperationKey(attempt.getProviderOperationKey())
                .providerSettlementId(command.providerSettlementId() != null && !command.providerSettlementId().isBlank() ? command.providerSettlementId().trim() : null)
                .providerAccountId(command.providerAccountId() != null && !command.providerAccountId().isBlank() ? command.providerAccountId().trim() : null)
                .providerStatus(command.rawProviderStatus() != null ? command.rawProviderStatus().trim() : "UNKNOWN")
                .finality(finality)
                .reasonCode(reasonCode)
                .utrNumber(command.utrNumber() != null && !command.utrNumber().isBlank() ? command.utrNumber().trim() : null)
                .observedAmount(command.observedAmount())
                .observedCurrency(command.observedCurrency() != null ? command.observedCurrency().trim().toUpperCase(Locale.ROOT) : null)
                .observedDirection(command.observedDirection() != null ? command.observedDirection().trim().toUpperCase(Locale.ROOT) : null)
                .providerObservedAt(command.providerObservedAt())
                .evidenceHash(evidenceHash)
                .observedAt(observedAt)
                .createdAt(Instant.now())
                .build();

        try {
            SettlementObservation saved = observationRepository.save(observation);
            try {
                observationRepository.flush();
            } catch (Exception ignored) {
            }
            log.info("Persisted trusted settlement observation: id={} attempt={} finality={} reason={} hash={}",
                    saved.getId(), attempt.getId(), saved.getFinality(), saved.getReasonCode(), evidenceHash);
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            existing = observationRepository.findByExecutionAttemptIdAndSourceAndEvidenceHash(
                    attempt.getId(),
                    command.source(),
                    evidenceHash
            );
            if (existing.isPresent()) {
                log.info("Concurrent duplicate settlement observation handled for attempt {} source {} hash {}",
                        attempt.getId(), command.source(), evidenceHash);
                return existing.get();
            }
            throw dive;
        }
    }
}
