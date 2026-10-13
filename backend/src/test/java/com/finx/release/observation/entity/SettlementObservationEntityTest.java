package com.finx.release.observation.entity;

import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationFingerprint;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementObservationEntityTest {

    @Test
    @DisplayName("SettlementObservation entity is immutable and has no setter methods")
    void entityHasNoSetters() {
        Method[] methods = SettlementObservation.class.getDeclaredMethods();
        for (Method m : methods) {
            assertThat(m.getName())
                    .as("SettlementObservation must have no setter methods: " + m.getName())
                    .doesNotStartWith("set");
        }
    }

    @Test
    @DisplayName("SettlementObservation does not contain raw payload, accounts, or IFSC fields")
    void entityContainsNoSensitiveOrRawPayloadFields() {
        Field[] fields = SettlementObservation.class.getDeclaredFields();
        for (Field f : fields) {
            String name = f.getName().toLowerCase();
            assertThat(name)
                    .as("Field must not store sensitive bank account data or raw payloads: " + f.getName())
                    .doesNotContain("rawpayload")
                    .doesNotContain("payload")
                    .doesNotContain("accountnumber")
                    .doesNotContain("encryptedaccount")
                    .doesNotContain("ifsc");
        }
    }

    @Test
    @DisplayName("Fingerprint is deterministic and unaffected by local timestamps or ID")
    void fingerprintIsDeterministic() {
        UUID instructionId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant providerTime = Instant.parse("2026-10-14T00:00:00Z");

        String hash1 = SettlementObservationFingerprint.calculate(
                instructionId, attemptId, "MOCK", SettlementObservationSource.STATUS_POLL,
                "REF-1", "FINX-REL-1", "MOCK-SETTL-1", "MOCK-ACC-1",
                "SETTLED", SettlementObservationFinality.FINAL_SUCCESS,
                SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED,
                "UTR-12345", new BigDecimal("50000.0000"), "INR", "DEBIT", providerTime
        );

        String hash2 = SettlementObservationFingerprint.calculate(
                instructionId, attemptId, "MOCK", SettlementObservationSource.STATUS_POLL,
                "REF-1", "FINX-REL-1", "MOCK-SETTL-1", "MOCK-ACC-1",
                "SETTLED", SettlementObservationFinality.FINAL_SUCCESS,
                SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED,
                "UTR-12345", new BigDecimal("50000.00"), "INR", "DEBIT", providerTime
        );

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("Builder validates required fields")
    void builderValidatesRequiredFields() {
        assertThatThrownBy(() -> SettlementObservation.builder().build())
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> SettlementObservation.builder()
                .releaseInstructionId(UUID.randomUUID())
                .build())
                .isInstanceOf(NullPointerException.class);
    }
}
