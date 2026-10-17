package com.finx.banking.provider;

import com.finx.banking.capability.BankingProviderCapabilities;
import com.finx.banking.capability.BankingProviderCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Base contract test enforcing standard behavioral invariants across all BankingProvider implementations.
 */
public abstract class BankingProviderContractTest {

    protected abstract BankingProvider createProvider();

    @Test
    @DisplayName("Provider name must be non-null and non-blank")
    void testProviderNameNonBlank() {
        BankingProvider provider = createProvider();
        assertThat(provider.getProviderName())
                .isNotNull()
                .isNotBlank();
    }

    @Test
    @DisplayName("Capabilities must be non-null and immutable")
    void testCapabilitiesImmutable() {
        BankingProvider provider = createProvider();
        BankingProviderCapabilities capabilities = provider.getCapabilities();
        assertThat(capabilities).isNotNull();

        assertThatThrownBy(() -> capabilities.asSet().add(BankingProviderCapability.PAYMENT_INSTRUCTION_CREATE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("supports() helper correctly reflects declared capabilities")
    void testSupportsHelper() {
        BankingProvider provider = createProvider();
        BankingProviderCapabilities capabilities = provider.getCapabilities();
        for (BankingProviderCapability cap : BankingProviderCapability.values()) {
            boolean declared = capabilities.supports(cap);
            assertThat(provider.supports(cap)).isEqualTo(declared);
        }
    }
}
