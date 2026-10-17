package com.finx.banking.capability;

import com.finx.banking.provider.BankingProvider;

import java.util.Objects;

/**
 * Defensive guard that validates that a banking provider supports the required capability
 * before any operation is initiated. Fails closed with BankingCapabilityNotSupportedException.
 */
public final class BankingProviderCapabilityGuard {

    private BankingProviderCapabilityGuard() {}

    public static void requireCapability(BankingProvider provider, BankingProviderCapability capability) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        if (!provider.supports(capability)) {
            throw new BankingCapabilityNotSupportedException(provider.getProviderName(), capability);
        }
    }
}
