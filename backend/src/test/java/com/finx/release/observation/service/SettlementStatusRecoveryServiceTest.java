package com.finx.release.observation.service;

import com.finx.banking.dto.BankingDtos.SettlementLookupResult;
import com.finx.banking.dto.BankingDtos.SettlementStatusResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettlementStatusRecoveryServiceTest {

    private SettlementExecutionAttemptRepository attemptRepository;
    private ReleaseInstructionRepository instructionRepository;
    private BankingProvider bankingProvider;
    private SettlementObservationService observationService;
    private SettlementStatusRecoveryService recoveryService;

    private UUID attemptId;
    private UUID instructionId;
    private SettlementExecutionAttempt attempt;
    private ReleaseInstruction instruction;

    @BeforeEach
    void setUp() {
        attemptRepository = mock(SettlementExecutionAttemptRepository.class);
        instructionRepository = mock(ReleaseInstructionRepository.class);
        bankingProvider = mock(BankingProvider.class);
        observationService = mock(SettlementObservationService.class);

        recoveryService = new SettlementStatusRecoveryService(
                attemptRepository,
                instructionRepository,
                bankingProvider,
                observationService
        );

        attemptId = UUID.randomUUID();
        instructionId = UUID.randomUUID();

        when(bankingProvider.getProviderName()).thenReturn("MOCK");
    }

    private SettlementObservation stubObservation(SettlementObservationFinality finality, String reasonCode) {
        return SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("ref")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerStatus("SETTLED")
                .finality(finality)
                .reasonCode(reasonCode)
                .evidenceHash("abc123hash")
                .observedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("ACKNOWLEDGED attempt with known providerSettlementId performs status poll")
    void acknowledgedWithProviderId_performsStatusPoll() {
        attempt = mock(SettlementExecutionAttempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getReleaseInstructionId()).thenReturn(instructionId);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        when(attempt.getProvider()).thenReturn("MOCK");
        when(attempt.getProviderSettlementId()).thenReturn("MOCK-SETTL-01");
        when(attempt.getProviderOperationKey()).thenReturn("FINX-REL-" + instructionId);

        instruction = mock(ReleaseInstruction.class);
        when(instruction.getId()).thenReturn(instructionId);
        when(instruction.getStatus()).thenReturn(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));

        when(bankingProvider.getSettlementStatus("MOCK-SETTL-01"))
                .thenReturn(new SettlementStatusResult("MOCK-SETTL-01", "SETTLED", "UTR-1234", Instant.now(), null));

        SettlementObservation expectedObs = stubObservation(SettlementObservationFinality.FINAL_SUCCESS, SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED);
        when(observationService.recordObservation(any())).thenReturn(expectedObs);

        SettlementRecoveryResult result = recoveryService.recoverAttempt(attemptId);

        assertThat(result.recovered()).isTrue();
        assertThat(result.recoveryAction()).isEqualTo("STATUS_POLL_RECORDED");
        verify(bankingProvider).getSettlementStatus("MOCK-SETTL-01");
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("UNKNOWN attempt with known providerSettlementId performs status poll")
    void unknownWithProviderId_performsStatusPoll() {
        attempt = mock(SettlementExecutionAttempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getReleaseInstructionId()).thenReturn(instructionId);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.UNKNOWN);
        when(attempt.getProvider()).thenReturn("MOCK");
        when(attempt.getProviderSettlementId()).thenReturn("MOCK-SETTL-02");
        when(attempt.getProviderOperationKey()).thenReturn("FINX-REL-" + instructionId);

        instruction = mock(ReleaseInstruction.class);
        when(instruction.getId()).thenReturn(instructionId);
        when(instruction.getStatus()).thenReturn(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));

        when(bankingProvider.getSettlementStatus("MOCK-SETTL-02"))
                .thenReturn(new SettlementStatusResult("MOCK-SETTL-02", "SETTLED", "UTR-5678", Instant.now(), null));

        when(observationService.recordObservation(any()))
                .thenReturn(stubObservation(SettlementObservationFinality.FINAL_SUCCESS, SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED));

        SettlementRecoveryResult result = recoveryService.recoverAttempt(attemptId);

        assertThat(result.recovered()).isTrue();
        assertThat(result.recoveryAction()).isEqualTo("STATUS_POLL_RECORDED");
        verify(instruction).markPendingSettlement();
        verify(instructionRepository).save(instruction);
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("UNKNOWN attempt with no providerSettlementId performs operation lookup")
    void unknownWithoutProviderId_performsOperationLookup() {
        attempt = mock(SettlementExecutionAttempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getReleaseInstructionId()).thenReturn(instructionId);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.UNKNOWN);
        when(attempt.getProvider()).thenReturn("MOCK");
        when(attempt.getProviderSettlementId()).thenReturn(null);
        when(attempt.getProviderOperationKey()).thenReturn("FINX-REL-" + instructionId);

        instruction = mock(ReleaseInstruction.class);
        when(instruction.getId()).thenReturn(instructionId);
        when(instruction.getStatus()).thenReturn(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));

        SettlementStatusResult statusResult = new SettlementStatusResult("MOCK-SETTL-RECOVERED", "SETTLED", "UTR-REC", Instant.now(), null);
        when(bankingProvider.lookupSettlementByOperationKey("FINX-REL-" + instructionId))
                .thenReturn(SettlementLookupResult.found(statusResult));

        when(observationService.recordObservation(any()))
                .thenReturn(stubObservation(SettlementObservationFinality.FINAL_SUCCESS, SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED));

        SettlementRecoveryResult result = recoveryService.recoverAttempt(attemptId);

        assertThat(result.recovered()).isTrue();
        assertThat(result.recoveryAction()).isEqualTo("OPERATION_LOOKUP_BOUND");
        verify(attempt).recordRecoveredAcknowledgementIdentity(eq("MOCK-SETTL-RECOVERED"), eq("SETTLED"), any());
        verify(attemptRepository).save(attempt);
        verify(instruction).markPendingSettlement();
        verify(instructionRepository).save(instruction);
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("SENDING attempt with no providerSettlementId performs operation lookup")
    void sendingWithoutProviderId_performsOperationLookup() {
        attempt = mock(SettlementExecutionAttempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getReleaseInstructionId()).thenReturn(instructionId);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.SENDING);
        when(attempt.getProvider()).thenReturn("MOCK");
        when(attempt.getProviderSettlementId()).thenReturn(null);
        when(attempt.getProviderOperationKey()).thenReturn("FINX-REL-" + instructionId);

        instruction = mock(ReleaseInstruction.class);
        when(instruction.getId()).thenReturn(instructionId);
        when(instruction.getStatus()).thenReturn(ReleaseInstructionStatus.DISPATCHING);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));

        SettlementStatusResult statusResult = new SettlementStatusResult("MOCK-SETTL-RECOVERED-2", "SETTLED", "UTR-REC2", Instant.now(), null);
        when(bankingProvider.lookupSettlementByOperationKey("FINX-REL-" + instructionId))
                .thenReturn(SettlementLookupResult.found(statusResult));

        when(observationService.recordObservation(any()))
                .thenReturn(stubObservation(SettlementObservationFinality.FINAL_SUCCESS, SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED));

        SettlementRecoveryResult result = recoveryService.recoverAttempt(attemptId);

        assertThat(result.recovered()).isTrue();
        assertThat(result.recoveryAction()).isEqualTo("OPERATION_LOOKUP_BOUND");
        verify(attempt).recordRecoveredAcknowledgementIdentity(eq("MOCK-SETTL-RECOVERED-2"), eq("SETTLED"), any());
        verify(attemptRepository).save(attempt);
        verify(instruction).markPendingSettlement();
        verify(instructionRepository).save(instruction);
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("Operation lookup NOT_FOUND persists NOT_FOUND observation with NO retry")
    void operationLookupNotFound_persistsNotFoundObservation_noRetry() {
        attempt = mock(SettlementExecutionAttempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getReleaseInstructionId()).thenReturn(instructionId);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.SENDING);
        when(attempt.getProvider()).thenReturn("MOCK");
        when(attempt.getProviderSettlementId()).thenReturn(null);
        when(attempt.getProviderOperationKey()).thenReturn("FINX-REL-" + instructionId);

        instruction = mock(ReleaseInstruction.class);
        when(instruction.getId()).thenReturn(instructionId);
        when(instruction.getStatus()).thenReturn(ReleaseInstructionStatus.DISPATCHING);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));

        when(bankingProvider.lookupSettlementByOperationKey("FINX-REL-" + instructionId))
                .thenReturn(SettlementLookupResult.notFound());

        when(observationService.recordObservation(any()))
                .thenReturn(stubObservation(SettlementObservationFinality.NOT_FOUND, SettlementObservationReasonCodes.SETTLEMENT_NOT_FOUND));

        SettlementRecoveryResult result = recoveryService.recoverAttempt(attemptId);

        assertThat(result.recovered()).isTrue();
        assertThat(result.recoveryAction()).isEqualTo("OPERATION_LOOKUP_NOT_FOUND");
        verify(attempt, never()).recordRecoveredAcknowledgementIdentity(any(), any(), any());
        verify(instruction, never()).markPendingSettlement();
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("Operation lookup unsupported returns OPERATION_LOOKUP_UNSUPPORTED without retry")
    void operationLookupUnsupported_returnsUnsupported_noRetry() {
        attempt = mock(SettlementExecutionAttempt.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getReleaseInstructionId()).thenReturn(instructionId);
        when(attempt.getStatus()).thenReturn(SettlementExecutionAttemptStatus.SENDING);
        when(attempt.getProvider()).thenReturn("MOCK");
        when(attempt.getProviderSettlementId()).thenReturn(null);
        when(attempt.getProviderOperationKey()).thenReturn("FINX-REL-" + instructionId);

        instruction = mock(ReleaseInstruction.class);
        when(instruction.getId()).thenReturn(instructionId);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));

        when(bankingProvider.lookupSettlementByOperationKey("FINX-REL-" + instructionId))
                .thenReturn(SettlementLookupResult.unsupported());

        SettlementRecoveryResult result = recoveryService.recoverAttempt(attemptId);

        assertThat(result.recovered()).isFalse();
        assertThat(result.recoveryAction()).isEqualTo("OPERATION_LOOKUP_UNSUPPORTED");
        verify(observationService, never()).recordObservation(any());
        verify(bankingProvider, never()).initiateSettlement(any());
    }
}
