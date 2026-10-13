package com.finx.release.execution.service;

import com.finx.banking.dto.BankingDtos.SettlementRequest;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.time.FinxClock;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.gate.SandboxSettlementDispatchGate;
import com.finx.release.execution.model.DispatchPreparedContext;
import com.finx.release.execution.model.PreparedSettlementWorkItem;
import com.finx.release.execution.model.SettlementDispatchResult;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.verification.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SettlementDispatchHandlerTest {

    private SandboxSettlementDispatchGate sandboxGate;
    private SettlementDispatchStateService stateService;
    private BankingProvider bankingProvider;
    private PiiEncryptionService piiEncryptionService;
    private SettlementExecutionAttemptRepository attemptRepository;
    private FinxClock clock;

    private SettlementDispatchHandler handler;

    @BeforeEach
    void setUp() {
        sandboxGate = Mockito.mock(SandboxSettlementDispatchGate.class);
        stateService = Mockito.mock(SettlementDispatchStateService.class);
        bankingProvider = Mockito.mock(BankingProvider.class);
        piiEncryptionService = Mockito.mock(PiiEncryptionService.class);
        attemptRepository = Mockito.mock(SettlementExecutionAttemptRepository.class);
        clock = Mockito.mock(FinxClock.class);

        when(clock.now()).thenReturn(Instant.parse("2026-10-13T12:00:00Z"));
        when(bankingProvider.getProviderName()).thenReturn("MOCK");

        handler = new SettlementDispatchHandler(
                sandboxGate,
                stateService,
                bankingProvider,
                piiEncryptionService,
                attemptRepository,
                clock
        );
    }

    @Test
    @DisplayName("Architecture check: Handler class and dispatch method MUST NOT be annotated with @Transactional")
    void testNoTransactionalAnnotationOnHandler() {
        assertThat(SettlementDispatchHandler.class.isAnnotationPresent(Transactional.class)).isFalse();
        try {
            var method = SettlementDispatchHandler.class.getMethod("dispatchPreparedWorkItem", PreparedSettlementWorkItem.class);
            assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("1. Sandbox gate disabled -> provider calls 0")
    void testGateDisabledZeroCalls() {
        doThrow(new IllegalStateException("SANDBOX_SETTLEMENT_DISPATCH_DISABLED"))
                .when(sandboxGate).requireDispatchAllowed();

        PreparedSettlementWorkItem item = createDummyWorkItem();

        assertThatThrownBy(() -> handler.dispatchPreparedWorkItem(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_SETTLEMENT_DISPATCH_DISABLED");

        verifyNoInteractions(bankingProvider);
        verifyNoInteractions(stateService);
    }

    @Test
    @DisplayName("2. Actual provider non-MOCK (e.g. AXIS) -> provider calls 0")
    void testRealProviderNameZeroCalls() {
        when(bankingProvider.getProviderName()).thenReturn("AXIS");
        PreparedSettlementWorkItem item = createDummyWorkItem();

        assertThatThrownBy(() -> handler.dispatchPreparedWorkItem(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REAL_PROVIDER_BLOCKED");

        verify(bankingProvider, never()).initiateSettlement(any());
        verifyNoInteractions(stateService);
    }

    @Test
    @DisplayName("3. Attempt not PREPARED (e.g. already SENDING or ACKNOWLEDGED) -> provider calls 0, fails closed")
    void testAttemptNotPreparedZeroCalls() {
        PreparedSettlementWorkItem item = createDummyWorkItem();
        SettlementExecutionAttempt attempt = Mockito.mock(SettlementExecutionAttempt.class);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.SENDING);
        when(attemptRepository.findById(item.executionAttemptId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> handler.dispatchPreparedWorkItem(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOVERY_REQUIRED");

        verify(bankingProvider, never()).initiateSettlement(any());
        verifyNoInteractions(stateService);
    }

    @Test
    @DisplayName("4-9. StateService pre-condition failures throw before network -> provider calls 0")
    void testStateServicePreconditionFailureZeroCalls() {
        PreparedSettlementWorkItem item = createDummyWorkItem();
        SettlementExecutionAttempt attempt = Mockito.mock(SettlementExecutionAttempt.class);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.PREPARED);
        when(attemptRepository.findById(item.executionAttemptId())).thenReturn(Optional.of(attempt));

        when(stateService.beginDispatch(any(), any(), any()))
                .thenThrow(new IllegalStateException("Canonical beneficiary has changed"));

        assertThatThrownBy(() -> handler.dispatchPreparedWorkItem(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Canonical beneficiary has changed");

        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("10-22. Valid sandbox request -> exactly 1 provider call, correct mapping, ACKNOWLEDGED, PENDING_SETTLEMENT, PROCESSED")
    void testValidSandboxDispatchHappyPath() {
        PreparedSettlementWorkItem item = createDummyWorkItem();
        SettlementExecutionAttempt attempt = Mockito.mock(SettlementExecutionAttempt.class);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.PREPARED);
        when(attemptRepository.findById(item.executionAttemptId())).thenReturn(Optional.of(attempt));

        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("75000.00");
        String providerOpKey = "FINX-REL-" + item.releaseInstructionId();

        DispatchPreparedContext ctx = new DispatchPreparedContext(
                item.outboxMessageId(),
                item.executionAttemptId(),
                item.releaseInstructionId(),
                dealId,
                milestoneId,
                amount,
                "INR",
                "MOCK",
                "MOCK-ESCROW-001",
                providerOpKey,
                UUID.randomUUID(),
                "Beneficiary Corp",
                "MOCK0001",
                "ENC_CIPHERTEXT"
        );
        when(stateService.beginDispatch(item.outboxMessageId(), item.executionAttemptId(), item.workerId()))
                .thenReturn(ctx);
        when(piiEncryptionService.decrypt("ENC_CIPHERTEXT")).thenReturn("987654321098");

        Instant initiatedAt = Instant.parse("2026-10-13T12:00:05Z");
        SettlementResult result = new SettlementResult(
                "MOCK-SETTL-88",
                "SETTLED",
                "UTR-MOCK-88",
                initiatedAt,
                "raw-response-json",
                "MOCK-ESCROW-001"
        );
        when(bankingProvider.initiateSettlement(any())).thenReturn(result);

        SettlementDispatchResult dispatchResult = handler.dispatchPreparedWorkItem(item);

        // 10. Provider called exactly once
        ArgumentCaptor<SettlementRequest> reqCaptor = ArgumentCaptor.forClass(SettlementRequest.class);
        verify(bankingProvider, times(1)).initiateSettlement(reqCaptor.capture());
        SettlementRequest req = reqCaptor.getValue();

        // 11. settlementReference == providerOperationKey
        assertThat(req.settlementReference()).isEqualTo(providerOpKey);
        // 12. idempotencyKey == providerOperationKey
        assertThat(req.idempotencyKey()).isEqualTo(providerOpKey);
        // 13. amount derived from attempt/instruction
        assertThat(req.amount()).isEqualByComparingTo(amount);
        // 14. currency derived from attempt
        assertThat(req.currency()).isEqualTo("INR");
        // 15. decrypted account used in request
        assertThat(req.beneficiaryAccountNumber()).isEqualTo("987654321098");
        assertThat(req.beneficiaryName()).isEqualTo("Beneficiary Corp");
        assertThat(req.beneficiaryIfsc()).isEqualTo("MOCK0001");

        // 18. Valid provider result -> ACKNOWLEDGED
        assertThat(dispatchResult.status()).isEqualTo(SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        assertThat(dispatchResult.providerSettlementId()).isEqualTo("MOCK-SETTL-88");
        assertThat(dispatchResult.providerStatus()).isEqualTo("SETTLED");

        // Verify TX-D2 called with acknowledgement
        verify(stateService).recordAcknowledged(
                eq(item.outboxMessageId()),
                eq(item.executionAttemptId()),
                eq(item.workerId()),
                eq("MOCK-SETTL-88"),
                eq("SETTLED"),
                eq(initiatedAt)
        );
    }

    @Test
    @DisplayName("23-26. Provider timeout/exception -> Attempt UNKNOWN, Release UNKNOWN_RECON_REQUIRED, Outbox PROCESSED, no retry")
    void testProviderExceptionLeadsToUnknownOutcome() {
        PreparedSettlementWorkItem item = createDummyWorkItem();
        SettlementExecutionAttempt attempt = Mockito.mock(SettlementExecutionAttempt.class);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.PREPARED);
        when(attemptRepository.findById(item.executionAttemptId())).thenReturn(Optional.of(attempt));

        DispatchPreparedContext ctx = createDummyContext(item);
        when(stateService.beginDispatch(any(), any(), any())).thenReturn(ctx);
        when(piiEncryptionService.decrypt(any())).thenReturn("12345678");

        when(bankingProvider.initiateSettlement(any()))
                .thenThrow(new RuntimeException("Simulated socket timeout"));

        SettlementDispatchResult dispatchResult = handler.dispatchPreparedWorkItem(item);

        assertThat(dispatchResult.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(dispatchResult.errorCode()).isEqualTo("PROVIDER_DISPATCH_EXCEPTION");

        verify(stateService).recordUnknown(
                eq(item.outboxMessageId()),
                eq(item.executionAttemptId()),
                eq(item.workerId()),
                eq("PROVIDER_DISPATCH_EXCEPTION"),
                any(Instant.class)
        );
        verify(stateService, never()).recordAcknowledged(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("27. Provider account mismatch -> UNKNOWN, no retry")
    void testProviderAccountMismatch() {
        PreparedSettlementWorkItem item = createDummyWorkItem();
        SettlementExecutionAttempt attempt = Mockito.mock(SettlementExecutionAttempt.class);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.PREPARED);
        when(attemptRepository.findById(item.executionAttemptId())).thenReturn(Optional.of(attempt));

        DispatchPreparedContext ctx = createDummyContext(item);
        when(stateService.beginDispatch(any(), any(), any())).thenReturn(ctx);
        when(piiEncryptionService.decrypt(any())).thenReturn("12345678");

        // Result has wrong providerAccountId
        SettlementResult result = new SettlementResult(
                "MOCK-SETTL-99",
                "SETTLED",
                "UTR-99",
                Instant.now(),
                "raw",
                "DIFFERENT-ESCROW-ACCOUNT"
        );
        when(bankingProvider.initiateSettlement(any())).thenReturn(result);

        SettlementDispatchResult dispatchResult = handler.dispatchPreparedWorkItem(item);

        assertThat(dispatchResult.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(dispatchResult.errorCode()).isEqualTo("PROVIDER_ACCOUNT_MISMATCH");

        verify(stateService).recordUnknown(
                eq(item.outboxMessageId()),
                eq(item.executionAttemptId()),
                eq(item.workerId()),
                eq("PROVIDER_ACCOUNT_MISMATCH"),
                any(Instant.class)
        );
    }

    @Test
    @DisplayName("28. Missing providerSettlementId -> UNKNOWN")
    void testMissingProviderSettlementId() {
        PreparedSettlementWorkItem item = createDummyWorkItem();
        SettlementExecutionAttempt attempt = Mockito.mock(SettlementExecutionAttempt.class);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.PREPARED);
        when(attemptRepository.findById(item.executionAttemptId())).thenReturn(Optional.of(attempt));

        DispatchPreparedContext ctx = createDummyContext(item);
        when(stateService.beginDispatch(any(), any(), any())).thenReturn(ctx);
        when(piiEncryptionService.decrypt(any())).thenReturn("12345678");

        // Missing providerSettlementId
        SettlementResult result = new SettlementResult(
                "  ",
                "SETTLED",
                "UTR-99",
                Instant.now(),
                "raw",
                ctx.providerAccountId()
        );
        when(bankingProvider.initiateSettlement(any())).thenReturn(result);

        SettlementDispatchResult dispatchResult = handler.dispatchPreparedWorkItem(item);

        assertThat(dispatchResult.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(dispatchResult.errorCode()).isEqualTo("MISSING_PROVIDER_SETTLEMENT_ID");

        verify(stateService).recordUnknown(
                eq(item.outboxMessageId()),
                eq(item.executionAttemptId()),
                eq(item.workerId()),
                eq("MISSING_PROVIDER_SETTLEMENT_ID"),
                any(Instant.class)
        );
    }

    @Test
    @DisplayName("29. Malformed result (null response) -> UNKNOWN")
    void testNullResultResponse() {
        PreparedSettlementWorkItem item = createDummyWorkItem();
        SettlementExecutionAttempt attempt = Mockito.mock(SettlementExecutionAttempt.class);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.PREPARED);
        when(attemptRepository.findById(item.executionAttemptId())).thenReturn(Optional.of(attempt));

        DispatchPreparedContext ctx = createDummyContext(item);
        when(stateService.beginDispatch(any(), any(), any())).thenReturn(ctx);
        when(piiEncryptionService.decrypt(any())).thenReturn("12345678");

        when(bankingProvider.initiateSettlement(any())).thenReturn(null);

        SettlementDispatchResult dispatchResult = handler.dispatchPreparedWorkItem(item);

        assertThat(dispatchResult.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(dispatchResult.errorCode()).isEqualTo("NULL_PROVIDER_RESPONSE");

        verify(stateService).recordUnknown(
                eq(item.outboxMessageId()),
                eq(item.executionAttemptId()),
                eq(item.workerId()),
                eq("NULL_PROVIDER_RESPONSE"),
                any(Instant.class)
        );
    }

    private PreparedSettlementWorkItem createDummyWorkItem() {
        UUID outboxId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        return new PreparedSettlementWorkItem(
                outboxId,
                releaseId,
                attemptId,
                releaseId.toString(),
                "FINX-REL-" + releaseId,
                "worker-node-1"
        );
    }

    private DispatchPreparedContext createDummyContext(PreparedSettlementWorkItem item) {
        return new DispatchPreparedContext(
                item.outboxMessageId(),
                item.executionAttemptId(),
                item.releaseInstructionId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.00"),
                "INR",
                "MOCK",
                "MOCK-ESCROW-001",
                item.providerOperationKey(),
                UUID.randomUUID(),
                "Acme Corp",
                "MOCK0001",
                "ENC_123"
        );
    }
}
