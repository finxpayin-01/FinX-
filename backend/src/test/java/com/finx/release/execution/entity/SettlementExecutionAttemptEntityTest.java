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
    @DisplayName("Entity reflection check: absolutely NO sensitive bank payload, raw response, or account number fields")
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
                "secret",
                "rawresponse",
                "raw_response",
                "utr",
                "utrnumber"
        );
    }

    @Test
    @DisplayName("PREPARED -> SENDING: sets requestStartedAt, status SENDING, and updatedAt")
    void testPreparedToSending() {
        UUID releaseId = UUID.randomUUID();
        SettlementExecutionAttempt attempt = createTestAttempt(releaseId);
        Instant startTime = Instant.now();

        attempt.markSending(startTime);

        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.SENDING);
        assertThat(attempt.getRequestStartedAt()).isEqualTo(startTime);
        assertThat(attempt.getUpdatedAt()).isEqualTo(startTime);
    }

    @Test
    @DisplayName("SENDING -> ACKNOWLEDGED: stores providerSettlementId, providerStatus, responseReceivedAt, retryable=false")
    void testSendingToAcknowledged() {
        UUID releaseId = UUID.randomUUID();
        SettlementExecutionAttempt attempt = createTestAttempt(releaseId);
        Instant startTime = Instant.now();
        attempt.markSending(startTime);

        Instant responseTime = startTime.plusMillis(200);
        attempt.markAcknowledged("MOCK-STL-99", "SETTLED", responseTime);

        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        assertThat(attempt.getProviderSettlementId()).isEqualTo("MOCK-STL-99");
        assertThat(attempt.getProviderStatus()).isEqualTo("SETTLED");
        assertThat(attempt.getResponseReceivedAt()).isEqualTo(responseTime);
        assertThat(attempt.getRetryable()).isFalse();
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getUpdatedAt()).isEqualTo(responseTime);
    }

    @Test
    @DisplayName("SENDING -> UNKNOWN: stores safeErrorCode, responseReceivedAt, retryable=false")
    void testSendingToUnknown() {
        UUID releaseId = UUID.randomUUID();
        SettlementExecutionAttempt attempt = createTestAttempt(releaseId);
        Instant startTime = Instant.now();
        attempt.markSending(startTime);

        Instant observedTime = startTime.plusSeconds(5);
        attempt.markUnknown("PROVIDER_DISPATCH_TIMEOUT", observedTime);

        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(attempt.getErrorCode()).isEqualTo("PROVIDER_DISPATCH_TIMEOUT");
        assertThat(attempt.getResponseReceivedAt()).isEqualTo(observedTime);
        assertThat(attempt.getRetryable()).isFalse();
        assertThat(attempt.getProviderSettlementId()).isNull();
        assertThat(attempt.getUpdatedAt()).isEqualTo(observedTime);
    }

    @Test
    @DisplayName("PREPARED -> TERMINAL_FAILED: fails closed before dispatch without touching network fields")
    void testPreparedToTerminalFailed() {
        UUID releaseId = UUID.randomUUID();
        SettlementExecutionAttempt attempt = createTestAttempt(releaseId);
        Instant failTime = Instant.now();

        attempt.markTerminalFailedBeforeDispatch("PRE_DISPATCH_DATA_MISMATCH", failTime);

        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.TERMINAL_FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("PRE_DISPATCH_DATA_MISMATCH");
        assertThat(attempt.getRetryable()).isFalse();
        assertThat(attempt.getRequestStartedAt()).isNull();
        assertThat(attempt.getProviderSettlementId()).isNull();
        assertThat(attempt.getResponseReceivedAt()).isNull();
        assertThat(attempt.getUpdatedAt()).isEqualTo(failTime);
    }

    @Test
    @DisplayName("Illegal transitions are strictly rejected")
    void testIllegalTransitions() {
        UUID releaseId = UUID.randomUUID();
        SettlementExecutionAttempt attempt = createTestAttempt(releaseId);

        // Cannot go directly PREPARED -> ACKNOWLEDGED
        assertThatThrownBy(() -> attempt.markAcknowledged("STL-1", "SETTLED", Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        // Cannot go directly PREPARED -> UNKNOWN
        assertThatThrownBy(() -> attempt.markUnknown("ERR", Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        // Mark SENDING
        attempt.markSending(Instant.now());

        // Cannot mark SENDING again
        assertThatThrownBy(() -> attempt.markSending(Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        // Cannot mark TERMINAL_FAILED from SENDING
        assertThatThrownBy(() -> attempt.markTerminalFailedBeforeDispatch("ERR", Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        // Acknowledge
        attempt.markAcknowledged("STL-1", "SETTLED", Instant.now());

        // Cannot go ACKNOWLEDGED -> SENDING
        assertThatThrownBy(() -> attempt.markSending(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    private SettlementExecutionAttempt createTestAttempt(UUID releaseId) {
        return new SettlementExecutionAttempt(
                UUID.randomUUID(),
                releaseId,
                UUID.randomUUID(),
                1,
                releaseId.toString(),
                ProviderSettlementOperationKey.release(releaseId),
                "MOCK",
                "MOCK-ACC-1",
                UUID.randomUUID(),
                new BigDecimal("50000.0000"),
                "INR",
                "a".repeat(64),
                "worker-node-1",
                Instant.now()
        );
    }
}
