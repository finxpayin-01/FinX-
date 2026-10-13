package com.finx.release.observation.service;

import com.finx.banking.dto.BankingDtos.SettlementLookupResult;
import com.finx.banking.dto.BankingDtos.SettlementStatusResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.exception.ResourceNotFoundException;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service for explicit settlement status polling and operation-key recovery.
 * Recovers unknown / crash-state settlement execution attempts without blind redispatch.
 * Strictly read-only towards BankingProvider (never calls initiateSettlement).
 * Strictly non-finalizing: no ledger posting, no escrow release, no reservation consumption.
 */
@Service
public class SettlementStatusRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SettlementStatusRecoveryService.class);

    private final SettlementExecutionAttemptRepository attemptRepository;
    private final ReleaseInstructionRepository instructionRepository;
    private final BankingProvider bankingProvider;
    private final SettlementObservationService observationService;

    public SettlementStatusRecoveryService(
            SettlementExecutionAttemptRepository attemptRepository,
            ReleaseInstructionRepository instructionRepository,
            BankingProvider bankingProvider,
            SettlementObservationService observationService
    ) {
        this.attemptRepository = attemptRepository;
        this.instructionRepository = instructionRepository;
        this.bankingProvider = bankingProvider;
        this.observationService = observationService;
    }

    @Transactional
    public SettlementRecoveryResult recoverAttempt(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");

        SettlementExecutionAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementExecutionAttempt not found: " + attemptId));

        // Part T: Only ACKNOWLEDGED, UNKNOWN, SENDING are eligible
        SettlementExecutionAttemptStatus status = attempt.getStatus();
        if (status != SettlementExecutionAttemptStatus.ACKNOWLEDGED
                && status != SettlementExecutionAttemptStatus.UNKNOWN
                && status != SettlementExecutionAttemptStatus.SENDING) {
            log.warn("Attempt {} is ineligible for recovery with status {}", attemptId, status);
            return new SettlementRecoveryResult(attemptId, false, "INELIGIBLE_STATUS: " + status, null);
        }

        // Part AO: Sandbox boundary check (mock only in E6)
        if (!"MOCK".equalsIgnoreCase(attempt.getProvider()) || !"MOCK".equalsIgnoreCase(bankingProvider.getProviderName())) {
            log.warn("Attempt {} has non-mock provider ({}) or configured provider is not MOCK ({}); recovery aborted",
                    attemptId, attempt.getProvider(), bankingProvider.getProviderName());
            return new SettlementRecoveryResult(attemptId, false, "PROVIDER_UNSUPPORTED", null);
        }

        ReleaseInstruction instruction = instructionRepository.findById(attempt.getReleaseInstructionId())
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction not found: " + attempt.getReleaseInstructionId()));

        String providerSettlementId = attempt.getProviderSettlementId();

        // Case 1: providerSettlementId is known (ACKNOWLEDGED or UNKNOWN with providerSettlementId)
        if (providerSettlementId != null && !providerSettlementId.isBlank()) {
            log.info("Performing read-only status poll for attempt {} with known providerSettlementId {}",
                    attemptId, providerSettlementId);

            SettlementStatusResult statusResult = bankingProvider.getSettlementStatus(providerSettlementId);

            RecordObservationCommand command = RecordObservationCommand.builder()
                    .releaseInstructionId(instruction.getId())
                    .executionAttemptId(attempt.getId())
                    .webhookEventId(null)
                    .source(SettlementObservationSource.STATUS_POLL)
                    .sourceReference(providerSettlementId)
                    .provider(attempt.getProvider())
                    .providerOperationKey(attempt.getProviderOperationKey())
                    .providerSettlementId(statusResult.providerSettlementId())
                    .providerAccountId(null) // Part O: Not fabricated
                    .rawProviderStatus(statusResult.status())
                    .utrNumber(statusResult.utrNumber())
                    .observedAmount(null) // Part O: Not fabricated
                    .observedCurrency(null) // Part O: Not fabricated
                    .observedDirection(null) // Part O: Not fabricated
                    .providerObservedAt(statusResult.settledAt())
                    .observedAt(Instant.now())
                    .build();

            SettlementObservation observation = observationService.recordObservation(command);

            // If instruction was UNKNOWN_RECON_REQUIRED and status poll proves operation
            if (instruction.getStatus() == ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED
                    && !"NOT_FOUND".equalsIgnoreCase(statusResult.status())) {
                instruction.markPendingSettlement();
                instructionRepository.save(instruction);
            }

            return new SettlementRecoveryResult(attemptId, true, "STATUS_POLL_RECORDED", observation);
        }

        // Case 2: providerSettlementId is absent (SENDING or UNKNOWN without providerSettlementId)
        log.info("Performing operation-key lookup for attempt {} with key {}",
                attemptId, attempt.getProviderOperationKey());

        SettlementLookupResult lookupResult = bankingProvider.lookupSettlementByOperationKey(attempt.getProviderOperationKey());

        if (!lookupResult.supported()) {
            log.warn("Operation-key lookup not supported for attempt {}", attemptId);
            return new SettlementRecoveryResult(attemptId, false, "OPERATION_LOOKUP_UNSUPPORTED", null);
        }

        if (!lookupResult.found() || lookupResult.settlement() == null) {
            log.info("Operation-key lookup returned NOT_FOUND for attempt {}", attemptId);

            RecordObservationCommand notFoundCommand = RecordObservationCommand.builder()
                    .releaseInstructionId(instruction.getId())
                    .executionAttemptId(attempt.getId())
                    .webhookEventId(null)
                    .source(SettlementObservationSource.OPERATION_LOOKUP)
                    .sourceReference(attempt.getProviderOperationKey())
                    .provider(attempt.getProvider())
                    .providerOperationKey(attempt.getProviderOperationKey())
                    .providerSettlementId(null)
                    .providerAccountId(null)
                    .rawProviderStatus("NOT_FOUND")
                    .utrNumber(null)
                    .observedAmount(null)
                    .observedCurrency(null)
                    .observedDirection(null)
                    .providerObservedAt(null)
                    .observedAt(Instant.now())
                    .build();

            SettlementObservation observation = observationService.recordObservation(notFoundCommand);

            // Part X / AB: No automatic retry, attempt and release remain as-is, reservation ACTIVE
            return new SettlementRecoveryResult(attemptId, true, "OPERATION_LOOKUP_NOT_FOUND", observation);
        }

        // Case 3: Operation-key lookup found an existing settlement!
        SettlementStatusResult statusResult = lookupResult.settlement();
        log.info("Operation-key lookup found settlement for attempt {}: providerSettlementId={} status={}",
                attemptId, statusResult.providerSettlementId(), statusResult.status());

        RecordObservationCommand foundCommand = RecordObservationCommand.builder()
                .releaseInstructionId(instruction.getId())
                .executionAttemptId(attempt.getId())
                .webhookEventId(null)
                .source(SettlementObservationSource.OPERATION_LOOKUP)
                .sourceReference(attempt.getProviderOperationKey())
                .provider(attempt.getProvider())
                .providerOperationKey(attempt.getProviderOperationKey())
                .providerSettlementId(statusResult.providerSettlementId())
                .providerAccountId(null)
                .rawProviderStatus(statusResult.status())
                .utrNumber(statusResult.utrNumber())
                .observedAmount(null)
                .observedCurrency(null)
                .observedDirection(null)
                .providerObservedAt(statusResult.settledAt())
                .observedAt(Instant.now())
                .build();

        SettlementObservation observation = observationService.recordObservation(foundCommand);

        // Part Z: Safely bind recovered provider acknowledgement identity to attempt
        if (statusResult.providerSettlementId() != null && !statusResult.providerSettlementId().isBlank()) {
            attempt.recordRecoveredAcknowledgementIdentity(
                    statusResult.providerSettlementId(),
                    statusResult.status(),
                    Instant.now()
            );
            attemptRepository.save(attempt);
        }

        // Part AA: Transition release to PENDING_SETTLEMENT (never SETTLED)
        if (instruction.getStatus() == ReleaseInstructionStatus.DISPATCHING
                || instruction.getStatus() == ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED) {
            instruction.markPendingSettlement();
            instructionRepository.save(instruction);
        }

        return new SettlementRecoveryResult(attemptId, true, "OPERATION_LOOKUP_BOUND", observation);
    }
}
