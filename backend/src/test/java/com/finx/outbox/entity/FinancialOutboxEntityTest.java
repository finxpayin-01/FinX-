package com.finx.outbox.entity;

import com.finx.outbox.model.FinancialOperationKey;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.release.entity.ReleaseInstruction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FinancialOutboxEntityTest {

    private ReleaseInstruction createDummyInstruction(UUID id) {
        return new ReleaseInstruction(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dummy-fingerprint",
                UUID.randomUUID(),
                "dummy-content-hash",
                UUID.randomUUID(),
                "MOCK",
                "MOCK-ACC-001",
                BigDecimal.valueOf(10000),
                "INR",
                "IDEM-" + UUID.randomUUID(),
                "REQ-HASH",
                UUID.randomUUID()
        );
    }

    @Test
    @DisplayName("1. Factory produces valid PENDING outbox message with all Phase E3 invariant values")
    void testCreateReleaseDispatchInvariants() {
        UUID instructionId = UUID.randomUUID();
        ReleaseInstruction instruction = createDummyInstruction(instructionId);
        Instant now = Instant.now();

        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(instruction, now);

        // 1. Status PENDING
        assertThat(message.getStatus()).isEqualTo(FinancialOutboxStatus.PENDING);

        // 2. Event type RELEASE_DISPATCH_REQUESTED
        assertThat(message.getEventType()).isEqualTo(FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED);

        // 3. Operation key equals release instruction UUID string
        assertThat(message.getOperationKey()).isEqualTo(instructionId.toString());
        assertThat(message.getOperationKey()).isEqualTo(FinancialOperationKey.releaseDispatch(instructionId));

        // 4. Payload version 1
        assertThat(message.getPayloadVersion()).isEqualTo(1);

        // 5. Attempt count 0
        assertThat(message.getAttemptCount()).isEqualTo(0);

        // 6. AvailableAt populated
        assertThat(message.getAvailableAt()).isEqualTo(now);

        // 7. Lock fields null
        assertThat(message.getLockedAt()).isNull();
        assertThat(message.getLockedBy()).isNull();

        // 8. ProcessedAt null
        assertThat(message.getProcessedAt()).isNull();

        // 9. DeadLetteredAt null
        assertThat(message.getDeadLetteredAt()).isNull();

        // 10. UUID populated
        assertThat(message.getId()).isNotNull();
        assertThat(message.getReleaseInstructionId()).isEqualTo(instructionId);
        assertThat(message.getCreatedAt()).isEqualTo(now);
        assertThat(message.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("2. FinancialOutboxMessage contains NO sensitive raw bank payload or credential fields")
    void testNoSensitivePayloadFields() {
        List<String> fieldNames = Arrays.stream(FinancialOutboxMessage.class.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .toList();

        // Assert strictly identifier-only
        assertThat(fieldNames).doesNotContain(
                "payload",
                "payloadjson",
                "rawpayload",
                "requestpayload",
                "bankpayload",
                "accountnumber",
                "beneficiaryaccount",
                "ifsc",
                "bankcredential",
                "secret",
                "webhookpayload",
                "kyc",
                "pan",
                "gstin"
        );
    }

    @Test
    @DisplayName("3. FinancialOutboxMessage has ZERO dependency on BankingProvider classes")
    void testNoBankingProviderDependency() {
        for (Field field : FinancialOutboxMessage.class.getDeclaredFields()) {
            assertThat(field.getType().getName()).doesNotContain("BankingProvider");
            assertThat(field.getType().getName()).doesNotContain("Settlement");
        }
    }

    @Test
    @DisplayName("4. markProcessed transitions LEASED -> PROCESSED, clears locks, and sets processedAt")
    void testMarkProcessedFromLeased() {
        ReleaseInstruction instruction = createDummyInstruction(UUID.randomUUID());
        Instant now = Instant.now();
        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(instruction, now);

        message.markLeased("worker-1", now);
        assertThat(message.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
        assertThat(message.getLockedBy()).isEqualTo("worker-1");

        Instant processedTime = now.plusSeconds(1);
        message.markProcessed(processedTime, null);

        assertThat(message.getStatus()).isEqualTo(FinancialOutboxStatus.PROCESSED);
        assertThat(message.getProcessedAt()).isEqualTo(processedTime);
        assertThat(message.getLockedAt()).isNull();
        assertThat(message.getLockedBy()).isNull();
        assertThat(message.getLastErrorCode()).isNull();
        assertThat(message.getUpdatedAt()).isEqualTo(processedTime);
    }

    @Test
    @DisplayName("5. markProcessed with safe error code for UNKNOWN outcome")
    void testMarkProcessedWithSafeErrorCodeForUnknown() {
        ReleaseInstruction instruction = createDummyInstruction(UUID.randomUUID());
        Instant now = Instant.now();
        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(instruction, now);

        message.markLeased("worker-1", now);
        Instant processedTime = now.plusSeconds(5);
        message.markProcessed(processedTime, "DISPATCH_OUTCOME_UNKNOWN");

        assertThat(message.getStatus()).isEqualTo(FinancialOutboxStatus.PROCESSED);
        assertThat(message.getLastErrorCode()).isEqualTo("DISPATCH_OUTCOME_UNKNOWN");
        assertThat(message.getProcessedAt()).isEqualTo(processedTime);
        assertThat(message.getLockedBy()).isNull();
    }

    @Test
    @DisplayName("6. markDeadLetter transitions LEASED -> DEAD_LETTER, clears locks, and sets deadLetteredAt")
    void testMarkDeadLetterFromLeased() {
        ReleaseInstruction instruction = createDummyInstruction(UUID.randomUUID());
        Instant now = Instant.now();
        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(instruction, now);

        message.markLeased("worker-1", now);
        Instant dlTime = now.plusSeconds(2);
        message.markDeadLetter("PRE_DISPATCH_TERMINAL_FAILURE", dlTime);

        assertThat(message.getStatus()).isEqualTo(FinancialOutboxStatus.DEAD_LETTER);
        assertThat(message.getDeadLetteredAt()).isEqualTo(dlTime);
        assertThat(message.getLockedAt()).isNull();
        assertThat(message.getLockedBy()).isNull();
        assertThat(message.getLastErrorCode()).isEqualTo("PRE_DISPATCH_TERMINAL_FAILURE");
        assertThat(message.getUpdatedAt()).isEqualTo(dlTime);
    }

    @Test
    @DisplayName("7. Non-LEASED status rejects markProcessed and markDeadLetter")
    void testNonLeasedRejectsProcessedAndDeadLetter() {
        ReleaseInstruction instruction = createDummyInstruction(UUID.randomUUID());
        Instant now = Instant.now();
        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(instruction, now);

        // PENDING -> PROCESSED rejected
        assertThatThrownBy(() -> message.markProcessed(now, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark PROCESSED when status is: PENDING");

        // PENDING -> DEAD_LETTER rejected
        assertThatThrownBy(() -> message.markDeadLetter("ERR", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark DEAD_LETTER when status is: PENDING");

        // Release lease for RETRY
        message.markLeased("worker-1", now);
        message.releaseLeaseForRetry(now.plusSeconds(10), "RETRY_DELAY", now);
        assertThat(message.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);

        // RETRY -> PROCESSED rejected
        assertThatThrownBy(() -> message.markProcessed(now, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark PROCESSED when status is: RETRY");

        // RETRY -> DEAD_LETTER rejected
        assertThatThrownBy(() -> message.markDeadLetter("ERR", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark DEAD_LETTER when status is: RETRY");
    }
}
