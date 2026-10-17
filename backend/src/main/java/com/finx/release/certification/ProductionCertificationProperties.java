package com.finx.release.certification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Type-safe configuration properties representing technical and organizational
 * production certification prerequisites for FinX release settlement.
 * <p>
 * In accordance with financial safety rules, every certification flag defaults to false.
 */
@Component
@ConfigurationProperties(prefix = "finx.release.certification")
public class ProductionCertificationProperties {

    private boolean technicalCertified = false;
    private boolean bankContractCertified = false;
    private boolean bankUatCertified = false;
    private boolean securityCertified = false;
    private boolean complianceCertified = false;
    private boolean executiveSignoff = false;
    private boolean incidentRunbookCertified = false;
    private boolean reconciliationCertified = false;

    public boolean isTechnicalCertified() {
        return technicalCertified;
    }

    public void setTechnicalCertified(boolean technicalCertified) {
        this.technicalCertified = technicalCertified;
    }

    public boolean isBankContractCertified() {
        return bankContractCertified;
    }

    public void setBankContractCertified(boolean bankContractCertified) {
        this.bankContractCertified = bankContractCertified;
    }

    public boolean isBankUatCertified() {
        return bankUatCertified;
    }

    public void setBankUatCertified(boolean bankUatCertified) {
        this.bankUatCertified = bankUatCertified;
    }

    public boolean isSecurityCertified() {
        return securityCertified;
    }

    public void setSecurityCertified(boolean securityCertified) {
        this.securityCertified = securityCertified;
    }

    public boolean isComplianceCertified() {
        return complianceCertified;
    }

    public void setComplianceCertified(boolean complianceCertified) {
        this.complianceCertified = complianceCertified;
    }

    public boolean isExecutiveSignoff() {
        return executiveSignoff;
    }

    public void setExecutiveSignoff(boolean executiveSignoff) {
        this.executiveSignoff = executiveSignoff;
    }

    public boolean isIncidentRunbookCertified() {
        return incidentRunbookCertified;
    }

    public void setIncidentRunbookCertified(boolean incidentRunbookCertified) {
        this.incidentRunbookCertified = incidentRunbookCertified;
    }

    public boolean isReconciliationCertified() {
        return reconciliationCertified;
    }

    public void setReconciliationCertified(boolean reconciliationCertified) {
        this.reconciliationCertified = reconciliationCertified;
    }
}
