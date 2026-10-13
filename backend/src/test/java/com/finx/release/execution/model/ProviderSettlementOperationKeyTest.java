package com.finx.release.execution.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSettlementOperationKeyTest {

    @Test
    @DisplayName("Same release instruction ID produces exact same provider operation key")
    void sameIdProducesSameKey() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String key1 = ProviderSettlementOperationKey.release(id);
        String key2 = ProviderSettlementOperationKey.release(id);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isEqualTo("FINX-REL-550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("Different release instruction IDs produce different provider operation keys")
    void differentIdsProduceDifferentKeys() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        String key1 = ProviderSettlementOperationKey.release(id1);
        String key2 = ProviderSettlementOperationKey.release(id2);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("Provider operation key format conforms to constraints: prefix, length <= 128, no randomness")
    void keyConformsToConstraints() {
        UUID id = UUID.randomUUID();
        String key = ProviderSettlementOperationKey.release(id);

        assertThat(key).startsWith("FINX-REL-");
        assertThat(key.length()).isLessThanOrEqualTo(128);
        assertThat(key).doesNotContain(" ");
    }

    @Test
    @DisplayName("Null release instruction ID fails closed")
    void nullIdFailsClosed() {
        assertThatThrownBy(() -> ProviderSettlementOperationKey.release(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("releaseInstructionId must not be null");
    }
}
