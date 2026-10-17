package com.finx.release.certification;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Immutable report representing the complete technical and operational certification status
 * of the FinX post-engine release execution platform.
 */
public record ProductionCertificationResult(
        boolean technicalPlatformCertified,
        boolean providerReady,
        boolean bankContractCertified,
        boolean bankUatCertified,
        boolean securityCertified,
        boolean complianceCertified,
        boolean executiveSignoff,
        boolean incidentRunbookCertified,
        boolean reconciliationCertified,
        boolean productionExecutionRequested,
        boolean gateOpen,
        List<String> blockers,
        Instant evaluatedAt
) {
    public ProductionCertificationResult {
        blockers = blockers != null ? Collections.unmodifiableList(blockers) : Collections.emptyList();
    }
}
