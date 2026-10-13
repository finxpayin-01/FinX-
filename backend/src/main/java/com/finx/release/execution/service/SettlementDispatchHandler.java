package com.finx.release.execution.service;

import com.finx.banking.dto.BankingDtos;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.time.FinxClock;
import com.finx.exception.ResourceNotFoundException;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.gate.SandboxSettlementDispatchGate;
import com.finx.release.execution.model.DispatchPreparedContext;
import com.finx.release.execution.model.PreparedSettlementWorkItem;
import com.finx.release.execution.model.SettlementDispatchResult;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.verification.service.PiiEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * Orchestrates sandbox settlement dispatch to MockBankingProvider with unknown-outcome safety.
 * Coordinates TX-D1 (marking SENDING), executes the external network call OUTSIDE any database transaction,
 * and coordinates TX-D2 (recording ACKNOWLEDGED or UNKNOWN).
 * <p>
 * STRICT INVARIANT:
 * This class is the ONLY E5 production service with dependencies on BankingProvider and PiiEncryptionService.
 * This class and dispatchPreparedWorkItem MUST NOT be annotated with @Transactional.
 */
@Service
public class SettlementDispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(SettlementDispatchHandler.class);

    private final SandboxSettlementDispatchGate sandboxGate;
    private final SettlementDispatchStateService stateService;
    private final BankingProvider bankingProvider;
    private final PiiEncryptionService piiEncryptionService;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final FinxClock clock;

    public SettlementDispatchHandler(
            SandboxSettlementDispatchGate sandboxGate,
            SettlementDispatchStateService stateService,
            BankingProvider bankingProvider,
            PiiEncryptionService piiEncryptionService,
            SettlementExecutionAttemptRepository attemptRepository,
            FinxClock clock
    ) {
        this.sandboxGate = Objects.requireNonNull(sandboxGate, "sandboxGate must not be null");
        this.stateService = Objects.requireNonNull(stateService, "stateService must not be null");
        this.bankingProvider = Objects.requireNonNull(bankingProvider, "bankingProvider must not be null");
        this.piiEncryptionService = Objects.requireNonNull(piiEncryptionService, "piiEncryptionService must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Dispatches a prepared settlement work item to the sandbox mock banking provider.
     * ZERO automatic retry: any failure after TX-D1 transitions to UNKNOWN.
     * No @Transactional annotation.
     */
    public SettlementDispatchResult dispatchPreparedWorkItem(PreparedSettlementWorkItem workItem) {
        Objects.requireNonNull(workItem, "workItem must not be null");

        // 1. Sandbox gate preflight check: fail closed before any mutation or network call
        sandboxGate.requireDispatchAllowed();

        // 2. Provider verification: only MOCK provider allowed
        if (!"MOCK".equalsIgnoreCase(bankingProvider.getProviderName())) {
            throw new IllegalStateException("REAL_PROVIDER_BLOCKED: Actual provider is not MOCK: " + bankingProvider.getProviderName());
        }

        // 3. Pre-check attempt status to prevent blind redispatch / duplicate calls
        SettlementExecutionAttempt currentAttempt = attemptRepository.findById(workItem.executionAttemptId())
                .orElseThrow(() -> new ResourceNotFoundException("SettlementExecutionAttempt", "id", workItem.executionAttemptId()));

        SettlementExecutionAttemptStatus currentStatus = currentAttempt.getStatus();
        if (currentStatus == SettlementExecutionAttemptStatus.ACKNOWLEDGED
                || currentStatus == SettlementExecutionAttemptStatus.UNKNOWN
                || currentStatus == SettlementExecutionAttemptStatus.SENDING) {
            throw new IllegalStateException("RECOVERY_REQUIRED: Attempt " + workItem.executionAttemptId()
                    + " is already in state " + currentStatus + ". Second blind provider call is blocked.");
        }
        if (currentStatus != SettlementExecutionAttemptStatus.PREPARED) {
            throw new IllegalStateException("Attempt " + workItem.executionAttemptId() + " is in illegal state: " + currentStatus);
        }

        // 4. TX-D1: Validate all preconditions and mark Attempt SENDING, ReleaseInstruction DISPATCHING
        DispatchPreparedContext ctx = stateService.beginDispatch(
                workItem.outboxMessageId(),
                workItem.executionAttemptId(),
                workItem.workerId()
        );

        // 5. Decrypt beneficiary account number ephemerally in memory
        String plainAccountNumber = piiEncryptionService.decrypt(ctx.encryptedAccountNumber());
        if (plainAccountNumber == null || plainAccountNumber.isBlank()) {
            throw new IllegalStateException("Beneficiary account number decryption failed");
        }

        // 6. Construct authoritative SettlementRequest
        BankingDtos.SettlementRequest request = new BankingDtos.SettlementRequest(
                ctx.providerOperationKey(),
                ctx.dealId(),
                ctx.milestoneId(),
                ctx.amount(),
                ctx.currency(),
                ctx.beneficiaryName(),
                plainAccountNumber,
                ctx.beneficiaryIfsc(),
                ctx.providerOperationKey()
        );

        // 7. NETWORK CALL OUTSIDE DATABASE TRANSACTION
        BankingDtos.SettlementResult result;
        try {
            log.info("Dispatching sandbox settlement for attempt {} with reference {}",
                    ctx.executionAttemptId(), ctx.providerOperationKey());
            result = bankingProvider.initiateSettlement(request);
        } catch (Exception ex) {
            log.error("Provider initiateSettlement threw exception for attempt {}: {}",
                    ctx.executionAttemptId(), ex.getClass().getSimpleName());
            Instant observedAt = clock.now();
            stateService.recordUnknown(
                    workItem.outboxMessageId(),
                    workItem.executionAttemptId(),
                    workItem.workerId(),
                    "PROVIDER_DISPATCH_EXCEPTION",
                    observedAt
            );
            return SettlementDispatchResult.unknown(workItem.executionAttemptId(), "PROVIDER_DISPATCH_EXCEPTION", observedAt);
        }

        // 8. Validate provider response structure (Part Q & W)
        boolean validResponse = result != null
                && result.providerSettlementId() != null
                && !result.providerSettlementId().isBlank()
                && result.providerSettlementId().length() <= 128
                && result.providerAccountId() != null
                && !result.providerAccountId().isBlank()
                && result.providerAccountId().equals(ctx.providerAccountId())
                && result.status() != null
                && !result.status().isBlank()
                && result.status().length() <= 64
                && result.initiatedAt() != null;

        if (!validResponse) {
            String errorCode;
            if (result == null) {
                errorCode = "NULL_PROVIDER_RESPONSE";
            } else if (result.providerAccountId() != null && !result.providerAccountId().equals(ctx.providerAccountId())) {
                errorCode = "PROVIDER_ACCOUNT_MISMATCH";
            } else if (result.providerSettlementId() == null || result.providerSettlementId().isBlank()) {
                errorCode = "MISSING_PROVIDER_SETTLEMENT_ID";
            } else {
                errorCode = "MALFORMED_PROVIDER_RESPONSE";
            }

            log.warn("Malformed or mismatched provider response for attempt {}: errorCode={}",
                    ctx.executionAttemptId(), errorCode);
            Instant observedAt = clock.now();
            stateService.recordUnknown(
                    workItem.outboxMessageId(),
                    workItem.executionAttemptId(),
                    workItem.workerId(),
                    errorCode,
                    observedAt
            );
            return SettlementDispatchResult.unknown(workItem.executionAttemptId(), errorCode, observedAt);
        }

        // 9. TX-D2: Record Acknowledgement
        Instant receivedAt = result.initiatedAt() != null ? result.initiatedAt() : clock.now();
        stateService.recordAcknowledged(
                workItem.outboxMessageId(),
                workItem.executionAttemptId(),
                workItem.workerId(),
                result.providerSettlementId(),
                result.status(),
                receivedAt
        );

        log.info("Sandbox settlement successfully acknowledged: attempt={}, providerSettlementId={}, providerStatus={}",
                ctx.executionAttemptId(), result.providerSettlementId(), result.status());

        return SettlementDispatchResult.acknowledged(
                workItem.executionAttemptId(),
                result.providerSettlementId(),
                result.status(),
                receivedAt
        );
    }
}
