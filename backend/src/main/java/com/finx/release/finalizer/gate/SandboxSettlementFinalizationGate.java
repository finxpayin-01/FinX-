package com.finx.release.finalizer.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gate controlling whether settlement finalization is permitted.
 * In Phase E7, finalization is strictly sandboxed:
 *   - finx.release.sandbox-finalization.enabled must be true
 *   - finx.banking.provider must be "mock"
 *   - The ReleaseInstruction.provider must be "MOCK"
 *
 * This protects real bank providers from accidental finalization prior to E10/E12 certification.
 * Contains ZERO BankingProvider dependencies.
 */
@Component
public class SandboxSettlementFinalizationGate {

    private static final Logger log = LoggerFactory.getLogger(SandboxSettlementFinalizationGate.class);
    public static final String MOCK_PROVIDER = "MOCK";

    private final boolean sandboxFinalizationEnabled;
    private final String configuredBankingProvider;

    public SandboxSettlementFinalizationGate(
            @Value("${finx.release.sandbox-finalization.enabled:false}") boolean sandboxFinalizationEnabled,
            @Value("${finx.banking.provider:mock}") String configuredBankingProvider
    ) {
        this.sandboxFinalizationEnabled = sandboxFinalizationEnabled;
        this.configuredBankingProvider = configuredBankingProvider != null ? configuredBankingProvider.trim() : "";
    }

    public boolean isFinalizationAllowed(String releaseInstructionProvider) {
        if (!sandboxFinalizationEnabled) {
            log.warn("Sandbox settlement finalization is DISABLED by configuration (finx.release.sandbox-finalization.enabled=false)");
            return false;
        }

        if (!MOCK_PROVIDER.equalsIgnoreCase(configuredBankingProvider)) {
            log.warn("Sandbox settlement finalization blocked: configured provider is '{}', expected 'mock'", configuredBankingProvider);
            return false;
        }

        if (!MOCK_PROVIDER.equalsIgnoreCase(releaseInstructionProvider)) {
            log.warn("Sandbox settlement finalization blocked: instruction provider is '{}', expected 'MOCK'", releaseInstructionProvider);
            return false;
        }

        return true;
    }

    public boolean isFinalizationAllowed(com.finx.release.entity.ReleaseInstruction instruction) {
        if (instruction == null) {
            return false;
        }
        return isFinalizationAllowed(instruction.getProvider());
    }

    public void assertFinalizationAllowed(com.finx.release.entity.ReleaseInstruction instruction) {
        if (instruction == null) {
            throw new IllegalArgumentException("ReleaseInstruction must not be null");
        }
        assertFinalizationAllowed(instruction.getProvider());
    }

    public void assertFinalizationAllowed(String releaseInstructionProvider) {
        if (!sandboxFinalizationEnabled) {
            throw new IllegalStateException("Sandbox settlement finalization is disabled (finx.release.sandbox-finalization.enabled=false)");
        }

        if (!MOCK_PROVIDER.equalsIgnoreCase(configuredBankingProvider)) {
            throw new IllegalStateException("Sandbox settlement finalization is blocked: Provider '"
                    + configuredBankingProvider + "' is not permitted for sandbox settlement finalization, expected 'mock'");
        }

        if (!MOCK_PROVIDER.equalsIgnoreCase(releaseInstructionProvider)) {
            throw new IllegalStateException("Sandbox settlement finalization is blocked: ReleaseInstruction provider '"
                    + releaseInstructionProvider + "' is not permitted for sandbox settlement finalization, expected 'MOCK'");
        }
    }

    public boolean isSandboxFinalizationEnabled() {
        return sandboxFinalizationEnabled;
    }

    public String getConfiguredBankingProvider() {
        return configuredBankingProvider;
    }
}
