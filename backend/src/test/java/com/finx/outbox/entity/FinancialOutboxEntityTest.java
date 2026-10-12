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
}
