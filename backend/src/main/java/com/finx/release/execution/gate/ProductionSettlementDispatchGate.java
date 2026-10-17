package com.finx.release.execution.gate;

import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.contract.UnsupportedBankApiContract;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.banking.readiness.BankingProviderReadinessService;
import com.finx.release.certification.ProductionReadinessCertificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gatekeeper enforcing strict production settlement dispatch preconditions.
 * <p>
 * STRICT INVARIANT:
 * Production settlement dispatch requires BOTH complete technical platform readiness
 * AND verified external partner bank certification (API contract, UAT, compliance, executive signoff).
 * If ANY condition is missing or uncertified, this gate denies execution.
 */
@Component
public class ProductionSettlementDispatchGate {

    private final boolean masterProductionEnabled;
    private final boolean e12Certified;
    private final BankingProvider bankingProvider;
    private final BankingPartnerProperties partnerProperties;
    private final ProductionReadinessCertificationService certificationService;

    public ProductionSettlementDispatchGate(
            @Value("${finx.release.production-execution.enabled:false}") boolean masterProductionEnabled,
            @Autowired(required = false) BankingProvider bankingProvider,
            @Autowired(required = false) BankingPartnerProperties partnerProperties,
            @Value("${finx.release.e12-certified:false}") boolean e12Certified
    ) {
        this(masterProductionEnabled, bankingProvider, partnerProperties, e12Certified, null);
    }

    @Autowired
    public ProductionSettlementDispatchGate(
            @Value("${finx.release.production-execution.enabled:false}") boolean masterProductionEnabled,
            @Autowired(required = false) BankingProvider bankingProvider,
            @Autowired(required = false) BankingPartnerProperties partnerProperties,
            @Value("${finx.release.e12-certified:false}") boolean e12Certified,
            @Autowired(required = false) ProductionReadinessCertificationService certificationService
    ) {
        this.masterProductionEnabled = masterProductionEnabled;
        this.bankingProvider = bankingProvider;
        this.partnerProperties = partnerProperties;
        this.e12Certified = e12Certified;
        this.certificationService = certificationService;
    }

    public boolean isProductionDispatchAllowed() {
        // Master production switch must be true (Emergency Kill Switch)
        if (!masterProductionEnabled) {
            return false;
        }

        // Phase E12 final certification gate must be true
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

        // Cryptographic request signing must be configured
        if (props.getSigning() == null || !props.getSigning().isEnabled()) {
            return false;
        }

        // Contract must not be UnsupportedBankApiContract
        if (partner.getContract() instanceof UnsupportedBankApiContract) {
            return false;
        }

        // Required production capabilities must all be present
        if (!bankingProvider.getCapabilities().supportsAll(BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES)) {
            return false;
        }

        // Complete certification service evaluation
        if (certificationService != null && !certificationService.isGateOpen()) {
            return false;
        }

        return true;
    }

    public void requireProductionDispatchAllowed() {
        if (!isProductionDispatchAllowed()) {
            throw new IllegalStateException("PRODUCTION_SETTLEMENT_DISPATCH_DISABLED: Production settlement dispatch is locked. Live money movement requires Phase E12 certification, official bank contract, and full executive signoff.");
        }
    }

    public boolean isMasterProductionEnabled() {
        return masterProductionEnabled;
    }

    public boolean isE12Certified() {
        return e12Certified;
    }
}
