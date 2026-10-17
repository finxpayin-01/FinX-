package com.finx.release.certification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates production execution configuration on application startup.
 * <p>
 * CRITICAL SAFETY INVARIANT:
 * If live production execution is requested (finx.release.production-execution.enabled=true),
 * but any technical, provider, or external certification prerequisite is missing,
 * application startup FAILS CLOSED immediately to prevent ambiguous or unsafe operations.
 */
@Component
public class ProductionExecutionStartupValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionExecutionStartupValidator.class);

    private final boolean masterProductionExecutionEnabled;
    private final ProductionReadinessCertificationService certificationService;

    public ProductionExecutionStartupValidator(
            @Value("${finx.release.production-execution.enabled:false}") boolean masterProductionExecutionEnabled,
            ProductionReadinessCertificationService certificationService
    ) {
        this.masterProductionExecutionEnabled = masterProductionExecutionEnabled;
        this.certificationService = certificationService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!masterProductionExecutionEnabled) {
            log.info("FinX Production Execution Gate is CLOSED (Default safe mode: live payouts disabled)");
            return;
        }

        ProductionCertificationResult result = certificationService.evaluateCertification();
        if (!result.gateOpen()) {
            String message = "CRITICAL_STARTUP_SAFETY_FAILURE: Production execution was requested ('finx.release.production-execution.enabled=true'), "
                    + "but platform certification is INCOMPLETE. Blockers: " + String.join("; ", result.blockers());
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.warn("CRITICAL: FinX Production Execution Gate is OPEN with full certification.");
    }
}
