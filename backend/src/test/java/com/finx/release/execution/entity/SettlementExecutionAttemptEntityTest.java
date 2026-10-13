package com.finx.release.execution.entity;

import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
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

class SettlementExecutionAttemptEntityTest {

    @Test
    @DisplayName("PREPARED factory creates valid attempt with all network and provider fields null")
    void preparedDefaultsAreCorrect() {
        UUID id = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        UUID bankAccountId = UUID.randomUUID();
        Instant now = Instant.now();
        BigDecimal amount = new BigDecimal("50000.0000");

        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                id,
                releaseId,
                outboxId,
                1,
                releaseId.toString(),
                ProviderSettlementOperationKey.release(releaseId),
                "MOCK",
                "MOCK-ACC-1",
                bankAccountId,
                amount,
                "INR",
                "a".repeat(64),
                "worker-node-1",
                now
        );

        assertThat(attempt.getId()).isEqualTo(id);
        assertThat(attempt.getReleaseInstructionId()).isEqualTo(releaseId);
        assertThat(attempt.getOutboxMessageId()).isEqualTo(outboxId);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getOperationKey()).isEqualTo(releaseId.toString());
        assertThat(attempt.getProviderOperationKey()).isEqualTo("FINX-REL-" + releaseId);
        assertThat(attempt.getProvider()).isEqualTo("MOCK");
        assertThat(attempt.getProviderAccountId()).isEqualTo("MOCK-ACC-1");
        assertThat(attempt.getBeneficiaryBankAccountId()).isEqualTo(bankAccountId);
        assertThat(attempt.getAmount()).isEqualByComparingTo(amount);
        assertThat(attempt.getCurrency()).isEqualTo("INR");
        assertThat(attempt.getRequestHash()).isEqualTo("a".repeat(64));
        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);
        assertThat(attempt.getWorkerId()).isEqualTo("worker-node-1");
        assertThat(attempt.getCreatedAt()).isEqualTo(now);
        assertThat(attempt.getUpdatedAt()).isEqualTo(now);
        assertThat(attempt.getVersion()).isNull();

        // Network fields MUST be null
        assertThat(attempt.getProviderSettlementId()).isNull();
        assertThat(attempt.getProviderStatus()).isNull();
        assertThat(attempt.getRetryable()).isNull();
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getRequestStartedAt()).isNull();
        assertThat(attempt.getResponseReceivedAt()).isNull();
    }

    @Test
    @DisplayName("Attempt number must be strictly greater than 0")
    void attemptNumberMustBePositive() {
        UUID releaseId = UUID.randomUUID();
        assertThatThrownBy(() -> new SettlementExecutionAttempt(
                UUID.randomUUID(),
                releaseId,
                UUID.randomUUID(),
                0,
                releaseId.toString(),
                ProviderSettlementOperationKey.release(releaseId),
                "MOCK",
                "MOCK-ACC-1",
                UUID.randomUUID(),
                new BigDecimal("100.0000"),
                "INR",
                "b".repeat(64),
                "worker-1",
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNumber must be > 0");
    }

    @Test
    @DisplayName("Amount must be strictly greater than 0")
    void amountMustBePositive() {
        UUID releaseId = UUID.randomUUID();
        assertThatThrownBy(() -> new SettlementExecutionAttempt(
                UUID.randomUUID(),
                releaseId,
                UUID.randomUUID(),
                1,
                releaseId.toString(),
                ProviderSettlementOperationKey.release(releaseId),
                "MOCK",
                "MOCK-ACC-1",
                UUID.randomUUID(),
                BigDecimal.ZERO,
                "INR",
                "b".repeat(64),
                "worker-1",
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be > 0");
    }

    @Test
    @DisplayName("Entity reflection check: absolutely NO sensitive bank payload or account number fields")
    void noSensitivePayloadFieldsExistOnEntity() {
        List<String> fieldNames = Arrays.stream(SettlementExecutionAttempt.class.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .toList();

        assertThat(fieldNames).doesNotContain(
                "accountnumber",
                "account_number",
                "encryptedaccountnumber",
                "ifsc",
                "rawpayload",
                "raw_payload",
                "payloadjson",
                "payload_json",
                "bankrequest",
                "bank_request",
                "credentials",
                "secret"
        );
    }
}
