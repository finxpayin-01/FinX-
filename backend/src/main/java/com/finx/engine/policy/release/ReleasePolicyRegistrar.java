package com.finx.engine.policy.release;

import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.service.EnginePolicyVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup registrar for the deterministic FinX Release Policy version.
 * Automatically registers the immutable platform-scoped Release Policy (v1.0.0) upon application boot.
 * Idempotently returns the existing record if already registered with matching content hash.
 * Fails closed if a policy exists with conflicting hash.
 */
@Component
@Order(25)
public class ReleasePolicyRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReleasePolicyRegistrar.class);

    private final EnginePolicyVersionService policyVersionService;

    public ReleasePolicyRegistrar(EnginePolicyVersionService policyVersionService) {
        this.policyVersionService = policyVersionService;
    }

    /**
     * Registers the platform-scoped deterministic Release Policy v1.0.0.
     *
     * @return the saved or existing EnginePolicyVersion entity
     */
    @Transactional
    public EnginePolicyVersion registerDefaultReleasePolicy() {
        log.info("Ensuring registration of deterministic RELEASE policy: key={}, version={}",
                ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION);

        EnginePolicyVersion policy = policyVersionService.registerPolicyVersion(
                null,
                ReleasePolicyDefinition.SCOPE,
                ReleasePolicyDefinition.ENGINE_TYPE,
                ReleasePolicyDefinition.POLICY_KEY,
                ReleasePolicyDefinition.POLICY_VERSION,
                ReleasePolicyDefinition.getExpectedContentHash(),
                ReleasePolicyDefinition.SCHEMA_VERSION,
                ReleasePolicyDefinition.DESCRIPTION,
                ReleasePolicyDefinition.getCanonicalPolicyContent(),
                null
        );

        log.info("Deterministic RELEASE policy active: id={}, hash={}, activatedAt={}",
                policy.getId(), policy.getContentHash(), policy.getActivatedAt());

        return policy;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            registerDefaultReleasePolicy();
        } catch (Exception ex) {
            log.error("[STARTUP ERROR] Failed to register deterministic RELEASE policy: {}", ex.getMessage(), ex);
            throw ex;
        }
    }
}
