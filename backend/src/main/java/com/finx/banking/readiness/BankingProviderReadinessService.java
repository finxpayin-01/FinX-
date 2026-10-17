package com.finx.banking.readiness;

import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.contract.UnsupportedBankApiContract;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Evaluates operational readiness, capability support, and security boundaries of the active banking provider.
 * <p>
 * CRITICAL INVARIANT:
 * readyForProduction MUST remain FALSE in Phase E10.
 * Phase E10 only certifies the partner capability framework and sandbox boundary.
 * Controlled production payout gate enablement is deferred to Phase E12.
 */
@Service
public class BankingProviderReadinessService {

    public static final Set<BankingProviderCapability> MINIMUM_PRODUCTION_CAPABILITIES = Set.of(
            BankingProviderCapability.SETTLEMENT_INITIATE,
            BankingProviderCapability.SETTLEMENT_STATUS_QUERY,
            BankingProviderCapability.SETTLEMENT_OPERATION_LOOKUP,
            BankingProviderCapability.WEBHOOK_SIGNATURE_VERIFICATION,
            BankingProviderCapability.STATEMENT_PAGING
    );

    private final BankingProvider bankingProvider;
    private final BankingPartnerProperties partnerProperties;
    private final boolean masterProductionExecutionEnabled;

    public BankingProviderReadinessService(
            @Autowired(required = false) BankingProvider bankingProvider,
            @Autowired(required = false) BankingPartnerProperties partnerProperties,
            @Value("${finx.release.production-execution.enabled:false}") boolean masterProductionExecutionEnabled
    ) {
        this.bankingProvider = bankingProvider;
        this.partnerProperties = partnerProperties;
        this.masterProductionExecutionEnabled = masterProductionExecutionEnabled;
    }

    public BankingProviderReadiness evaluateReadiness() {
        if (bankingProvider == null) {
            return new BankingProviderReadiness(
                    "NONE",
                    false,
                    true,
                    false,
                    false,
                    false,
                    Collections.emptySet(),
                    false,
                    false,
                    List.of("No BankingProvider bean configured in application context")
            );
        }

        String providerName = bankingProvider.getProviderName();
        Set<BankingProviderCapability> caps = bankingProvider.getCapabilities() != null
                ? bankingProvider.getCapabilities().asSet()
                : Collections.emptySet();

        List<String> blockers = new ArrayList<>();

        if (bankingProvider instanceof MockBankingProvider) {
            blockers.add("Active provider is MOCK simulation. Real-money production payouts are disabled.");
            blockers.add("Production execution gate is disabled in Phase E10 (controlled by E12).");
            return new BankingProviderReadiness(
                    providerName,
                    true,
                    true,
                    false,
                    false,
                    false,
                    caps,
                    true,  // Mock is ready for sandbox development/testing
                    false, // Mock is NEVER ready for production
                    blockers
            );
        }

        if (bankingProvider instanceof PartnerBankingProvider partner) {
            BankingPartnerProperties props = partner.getProperties();
            boolean configured = props != null && props.isEnabled();
            boolean sandbox = props != null && props.isSandbox();
            boolean productionExecution = props != null && props.isProductionExecutionEnabled();
            boolean mtlsConfigured = props != null && props.getMtls() != null && props.getMtls().isEnabled();
            boolean signingConfigured = props != null && props.getSigning() != null && props.getSigning().isEnabled();

            // Validate critical capabilities
            for (BankingProviderCapability required : MINIMUM_PRODUCTION_CAPABILITIES) {
                if (!caps.contains(required)) {
                    blockers.add("Missing required payout capability: " + required);
                }
            }

            if (!mtlsConfigured) {
                blockers.add("Mutual TLS (mTLS) is not configured or enabled.");
            }
            if (!signingConfigured) {
                blockers.add("Cryptographic request signing is not configured.");
            }
            if (partner.getContract() instanceof UnsupportedBankApiContract) {
                blockers.add("Official partner bank API contract is not present in repository. Endpoints are fail-closed.");
            }
            if (!masterProductionExecutionEnabled) {
                blockers.add("Master production execution flag 'finx.release.production-execution.enabled' is false.");
            }
            if (!productionExecution) {
                blockers.add("Partner properties 'production-execution-enabled' is false.");
            }

            // Phase E10 strict rule: readyForProduction is ALWAYS false
            blockers.add("Phase E10 strictly disallows live money movement; Phase E12 controls final production enablement.");

            boolean readyForSandbox = configured
                    && sandbox
                    && mtlsConfigured
                    && !(partner.getContract() instanceof UnsupportedBankApiContract);

            return new BankingProviderReadiness(
                    providerName,
                    configured,
                    sandbox,
                    productionExecution,
                    mtlsConfigured,
                    signingConfigured,
                    caps,
                    readyForSandbox,
                    false, // STRICT INVARIANT: Always false in E10
                    blockers
            );
        }

        // Generic fallback provider
        blockers.add("Unknown banking provider type: " + bankingProvider.getClass().getName());
        blockers.add("Production execution gate is disabled in Phase E10.");
        return new BankingProviderReadiness(
                providerName,
                false,
                true,
                false,
                false,
                false,
                caps,
                false,
                false,
                blockers
        );
    }
}
