package com.finx.release.execution.gate;

import com.finx.banking.provider.BankingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gatekeeper enforcing strict sandbox-only settlement dispatch preconditions.
 * Fails closed unless finx.release.sandbox-dispatch.enabled is explicitly true,
 * finx.banking.provider is 'mock', and the injected BankingProvider is MOCK.
 */
@Component
public class SandboxSettlementDispatchGate {

    private final boolean sandboxDispatchEnabled;
    private final String configuredProvider;
    private final BankingProvider bankingProvider;

    public SandboxSettlementDispatchGate(
            @Value("${finx.release.sandbox-dispatch.enabled:false}") boolean sandboxDispatchEnabled,
            @Value("${finx.banking.provider:}") String configuredProvider,
            @Autowired(required = false) BankingProvider bankingProvider
    ) {
        this.sandboxDispatchEnabled = sandboxDispatchEnabled;
        this.configuredProvider = configuredProvider != null ? configuredProvider.trim() : "";
        this.bankingProvider = bankingProvider;
    }

    public boolean isDispatchAllowed() {
        if (!sandboxDispatchEnabled) {
            return false;
        }
        if (configuredProvider.isBlank() || !"mock".equalsIgnoreCase(configuredProvider)) {
            return false;
        }
        if (bankingProvider == null) {
            return false;
        }
        return "MOCK".equalsIgnoreCase(bankingProvider.getProviderName());
    }

    public void requireDispatchAllowed() {
        if (!isDispatchAllowed()) {
            throw new IllegalStateException("SANDBOX_SETTLEMENT_DISPATCH_DISABLED: Sandbox settlement dispatch is disabled or improperly configured");
        }
    }

    public boolean isSandboxDispatchEnabled() {
        return sandboxDispatchEnabled;
    }

    public String getConfiguredProvider() {
        return configuredProvider;
    }

    public BankingProvider getBankingProvider() {
        return bankingProvider;
    }
}
