package com.finx.release.certification;

import com.finx.banking.readiness.BankingProviderReadiness;
import com.finx.banking.readiness.BankingProviderReadinessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative certification service determining whether FinX technical platform and external
 * operational/compliance requirements are satisfied for live production settlement.
 */
@Service
public class ProductionReadinessCertificationService {

    private final BankingProviderReadinessService readinessService;
    private final ProductionCertificationProperties certificationProperties;
    private final boolean masterProductionExecutionEnabled;
    private final boolean e12Certified;

    public ProductionReadinessCertificationService(
            BankingProviderReadinessService readinessService,
            ProductionCertificationProperties certificationProperties,
            @Value("${finx.release.production-execution.enabled:false}") boolean masterProductionExecutionEnabled,
            @Value("${finx.release.e12-certified:false}") boolean e12Certified
    ) {
        this.readinessService = readinessService;
        this.certificationProperties = certificationProperties;
        this.masterProductionExecutionEnabled = masterProductionExecutionEnabled;
        this.e12Certified = e12Certified;
    }

    public ProductionCertificationResult evaluateCertification() {
        BankingProviderReadiness providerReadiness = readinessService.evaluateReadiness();
        boolean providerReady = providerReadiness.readyForProduction();

        boolean technicalPlatformCertified = e12Certified && certificationProperties.isTechnicalCertified();
        boolean bankContractCertified = certificationProperties.isBankContractCertified();
        boolean bankUatCertified = certificationProperties.isBankUatCertified();
        boolean securityCertified = certificationProperties.isSecurityCertified();
        boolean complianceCertified = certificationProperties.isComplianceCertified();
        boolean executiveSignoff = certificationProperties.isExecutiveSignoff();
        boolean incidentRunbookCertified = certificationProperties.isIncidentRunbookCertified();
        boolean reconciliationCertified = certificationProperties.isReconciliationCertified();

        List<String> blockers = new ArrayList<>();

        // Emergency Kill Switch / Master Switch
        if (!masterProductionExecutionEnabled) {
            blockers.add("Master production execution switch 'finx.release.production-execution.enabled' is false (Emergency Kill Switch Active).");
        }

        // Technical Certification
        if (!e12Certified) {
            blockers.add("E12 technical certification flag 'finx.release.e12-certified' is false.");
        }
        if (!certificationProperties.isTechnicalCertified()) {
            blockers.add("Technical platform certification attestation 'finx.release.certification.technical-certified' is false.");
        }

        // Provider Readiness
        if (!providerReady) {
            blockers.add("Active banking provider is not certified ready for production payouts.");
            if (providerReadiness.blockers() != null) {
                blockers.addAll(providerReadiness.blockers());
            }
        }

        // External & Governance Prerequisite Gates
        if (!bankContractCertified) {
            blockers.add("Official partner bank API contract certification is absent. Endpoints are fail-closed.");
        }
        if (!bankUatCertified) {
            blockers.add("Partner bank User Acceptance Testing (UAT) signoff is absent.");
        }
        if (!securityCertified) {
            blockers.add("Production information security audit certification is absent.");
        }
        if (!complianceCertified) {
            blockers.add("Legal, regulatory, and nodal escrow compliance approval is absent.");
        }
        if (!executiveSignoff) {
            blockers.add("Formal executive authorization signoff for live money movement is absent.");
        }
        if (!incidentRunbookCertified) {
            blockers.add("Operational incident response and emergency runbook certification is absent.");
        }
        if (!reconciliationCertified) {
            blockers.add("Nodal statement closed-loop reconciliation operational signoff is absent.");
        }

        boolean gateOpen = blockers.isEmpty();

        return new ProductionCertificationResult(
                technicalPlatformCertified,
                providerReady,
                bankContractCertified,
                bankUatCertified,
                securityCertified,
                complianceCertified,
                executiveSignoff,
                incidentRunbookCertified,
                reconciliationCertified,
                masterProductionExecutionEnabled,
                gateOpen,
                blockers,
                Instant.now()
        );
    }

    public boolean isGateOpen() {
        return evaluateCertification().gateOpen();
    }
}
