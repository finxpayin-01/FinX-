package com.finx.release.observation.service;

import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettlementObservationServiceTest {

    private SettlementObservationRepository observationRepository;
    private SettlementExecutionAttemptRepository attemptRepository;
    private ReleaseInstructionRepository instructionRepository;
    private SettlementObservationService observationService;

    private UUID instructionId;
    private UUID attemptId;
    private SettlementExecutionAttempt mockAttempt;
    private ReleaseInstruction mockInstruction;

    @BeforeEach
    void setUp() {
        observationRepository = mock(SettlementObservationRepository.class);
        attemptRepository = mock(SettlementExecutionAttemptRepository.class);
        instructionRepository = mock(ReleaseInstructionRepository.class);

        observationService = new SettlementObservationService(
                observationRepository,
                attemptRepository,
                instructionRepository
        );

        instructionId = UUID.randomUUID();
        attemptId = UUID.randomUUID();

        mockAttempt = mock(SettlementExecutionAttempt.class);
        when(mockAttempt.getId()).thenReturn(attemptId);
        when(mockAttempt.getReleaseInstructionId()).thenReturn(instructionId);
        when(mockAttempt.getProvider()).thenReturn("MOCK");
        when(mockAttempt.getProviderOperationKey()).thenReturn("FINX-REL-" + instructionId);
        when(mockAttempt.getProviderAccountId()).thenReturn("MOCK_ESCROW_ACCOUNT");
        when(mockAttempt.getAmount()).thenReturn(new BigDecimal("50000.0000"));
        when(mockAttempt.getCurrency()).thenReturn("INR");
        when(mockAttempt.getProviderSettlementId()).thenReturn("MOCK-SETTL-01");

        mockInstruction = mock(ReleaseInstruction.class);
        when(mockInstruction.getId()).thenReturn(instructionId);

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(mockAttempt));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(mockInstruction));

        when(observationRepository.save(any(SettlementObservation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Valid status poll observation records correctly with FINAL_SUCCESS")
    void validStatusPollObservation_recordsFinalSuccess() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("MOCK-SETTL-01")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-12345678")
                .observedAt(Instant.now())
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.FINAL_SUCCESS);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED);
        assertThat(saved.getSource()).isEqualTo(SettlementObservationSource.STATUS_POLL);
        assertThat(saved.getUtrNumber()).isEqualTo("UTR-12345678");

        verify(observationRepository).save(any(SettlementObservation.class));
    }

    @Test
    @DisplayName("Valid webhook observation records correctly with FINAL_SUCCESS")
    void validWebhookObservation_recordsFinalSuccess() {
        UUID webhookEventId = UUID.randomUUID();
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .webhookEventId(webhookEventId)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("evt-001")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .providerAccountId("MOCK_ESCROW_ACCOUNT")
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-98765432")
                .observedAmount(new BigDecimal("50000.0000"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .observedAt(Instant.now())
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.FINAL_SUCCESS);
        assertThat(saved.getWebhookEventId()).isEqualTo(webhookEventId);
        assertThat(saved.getSource()).isEqualTo(SettlementObservationSource.WEBHOOK);
    }

    @Test
    @DisplayName("Idempotent duplicate evidence returns existing observation without inserting duplicate")
    void duplicateEvidence_returnsExistingObservation() {
        SettlementObservation existing = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("MOCK-SETTL-01")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode(SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED)
                .evidenceHash("dummy-hash")
                .observedAt(Instant.now())
                .build();

        when(observationRepository.findByExecutionAttemptIdAndSourceAndEvidenceHash(eq(attemptId), eq(SettlementObservationSource.STATUS_POLL), anyString()))
                .thenReturn(Optional.of(existing));

        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("MOCK-SETTL-01")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-12345678")
                .build();

        SettlementObservation returned = observationService.recordObservation(cmd);

        assertThat(returned).isSameAs(existing);
        verify(observationRepository, never()).save(any(SettlementObservation.class));
    }

    @Test
    @DisplayName("Provider mismatch results in UNKNOWN with SETTLEMENT_REFERENCE_MISMATCH")
    void providerMismatch_resultsInUnknown() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("ref")
                .provider("AXIS") // Mismatch with attempt's MOCK
                .providerOperationKey("FINX-REL-" + instructionId)
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-123")
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_REFERENCE_MISMATCH);
    }

    @Test
    @DisplayName("Provider account mismatch results in UNKNOWN with SETTLEMENT_PROVIDER_ACCOUNT_MISMATCH")
    void providerAccountMismatch_resultsInUnknown() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("ref")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .providerAccountId("WRONG_ACCOUNT")
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-123")
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_PROVIDER_ACCOUNT_MISMATCH);
    }

    @Test
    @DisplayName("Amount mismatch results in UNKNOWN with SETTLEMENT_AMOUNT_MISMATCH")
    void amountMismatch_resultsInUnknown() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("ref")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-123")
                .observedAmount(new BigDecimal("99999.00")) // Mismatch
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("Currency mismatch results in UNKNOWN with SETTLEMENT_CURRENCY_MISMATCH")
    void currencyMismatch_resultsInUnknown() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("ref")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-123")
                .observedCurrency("USD") // Mismatch
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("Direction mismatch results in UNKNOWN with SETTLEMENT_DIRECTION_MISMATCH")
    void directionMismatch_resultsInUnknown() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("ref")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-123")
                .observedDirection("CREDIT") // Mismatch: settlements must be DEBIT
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_DIRECTION_MISMATCH);
    }

    @Test
    @DisplayName("Provider settlement ID mismatch results in UNKNOWN with SETTLEMENT_REFERENCE_MISMATCH")
    void settlementIdMismatch_resultsInUnknown() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("ref")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-DIFFERENT") // Mismatch with attempt's MOCK-SETTL-01
                .rawProviderStatus("SETTLED")
                .utrNumber("UTR-123")
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_REFERENCE_MISMATCH);
    }

    @Test
    @DisplayName("SETTLED status without UTR results in UNKNOWN with SETTLEMENT_FINAL_MISSING_UTR")
    void settledWithoutUtr_resultsInUnknown() {
        RecordObservationCommand cmd = RecordObservationCommand.builder()
                .releaseInstructionId(instructionId)
                .executionAttemptId(attemptId)
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("ref")
                .provider("MOCK")
                .providerOperationKey("FINX-REL-" + instructionId)
                .providerSettlementId("MOCK-SETTL-01")
                .rawProviderStatus("SETTLED")
                .utrNumber(null) // Missing UTR
                .build();

        SettlementObservation saved = observationService.recordObservation(cmd);

        assertThat(saved.getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(saved.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_MISSING_UTR);
    }
}
