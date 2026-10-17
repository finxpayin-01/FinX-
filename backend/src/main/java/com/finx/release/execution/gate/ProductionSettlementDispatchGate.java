package com.finx.release.execution.gate;

import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.banking.readiness.BankingProviderReadinessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gatekeeper enforcing strict production settlement dispatch preconditions.
 * <p>
 * STRICT INVARIANT:
 * In Phase E10, production settlement dispatch is PERMANENTLY DISABLED.
 * Even if all technical preconditions (mTLS, signing, partner configs) are satisfied,
 * this gate denies execution because final production certification is strictly controlled by Phase E12.
 */
@Component
public class ProductionSettlementDispatchGate {

    private final boolean masterProductionEnabled;
    private final BankingProvider bankingProvider;
    private final BankingPartnerProperties partnerProperties;
    private final boolean e12Certified;

    public ProductionSettlementDispatchGate(
            @Value("${finx.release.production-execution.enabled:false}") boolean masterProductionEnabled,
            @Autowired(required = false) BankingProvider bankingProvider,
            @Autowired(required = false) BankingPartnerProperties partnerProperties,
            @Value("${finx.release.e12-certified:false}") boolean e12Certified
    ) {
        this.masterProductionEnabled = masterProductionEnabled;
        this.bankingProvider = bankingProvider;
        this.partnerProperties = partnerProperties;
        this.e12Certified = e12Certified;
    }

    public boolean isProductionDispatchAllowed() {
        // Master production switch must be true
        if (!masterProductionEnabled) {
            return false;
        }

        // Phase E12 final certification gate must be true (strictly false in E10)
        if (!e12Certified) {
            return false;
        }

        // Provider must exist and cannot be MOCK
        if (bankingProvider == null || "MOCK".equalsIgnoreCase(bankingProvider.getProviderName())) {
            return false;
        }

        // Provider must be a PartnerBankingProvider
        if (!(bankingProvider instanceof PartnerBankingProvider partner)) {
            return false;
        }

        // Partner properties must be production-enabled
        BankingPartnerProperties props = partner.getProperties();
        if (props == null || props.isSandbox() || !props.isProductionExecutionEnabled()) {
            return false;
        }

        // mTLS must be enabled and configured
        if (props.getMtls() == null || !props.getMtls().isEnabled()) {
            return false;
        }

        // Required production capabilities must all be present
        if (!bankingProvider.getCapabilities().supportsAll(BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES)) {
            return false;
        }

        return true;
    }

    public void requireProductionDispatchAllowed() {
        if (!isProductionDispatchAllowed()) {
            throw new IllegalStateException("PRODUCTION_SETTLEMENT_DISPATCH_DISABLED: Production settlement dispatch is permanently disabled in Phase E10. Live money movement requires Phase E12 certification.");
        }
    }

    public boolean isMasterProductionEnabled() {
        return masterProductionEnabled;
    }

    public boolean isE12Certified() {
        return e12Certified;
    }
}
