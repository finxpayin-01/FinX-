package com.finx.banking.readiness;

import com.finx.banking.capability.BankingProviderCapability;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Diagnostic and audit model reporting the operational and security readiness of a banking provider.
 * Strictly sanitizes diagnostic output: contains zero secrets, passwords, keys, or credentials.
 */
public record BankingProviderReadiness(
        String providerName,
        boolean configured,
        boolean sandbox,
        boolean productionExecutionEnabled,
        boolean mtlsConfigured,
        boolean signingConfigured,
        Set<BankingProviderCapability> capabilities,
        boolean readyForSandbox,
        boolean readyForProduction,
        List<String> blockers
) {
    public BankingProviderReadiness {
        Objects.requireNonNull(providerName, "providerName must not be null");
        capabilities = capabilities != null ? Collections.unmodifiableSet(capabilities) : Collections.emptySet();
        blockers = blockers != null ? Collections.unmodifiableList(blockers) : Collections.emptyList();
    }
}
