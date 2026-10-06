package com.finx.engine.policy.fraud;

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
 * Startup registrar for the deterministic FinX Fraud Policy version.
 * Automatically registers the immutable platform-scoped Fraud Policy (v1.0.0) upon application boot.
 * Idempotently returns the existing record if already registered with matching content hash.
 * Fails closed if a policy exists with conflicting hash.
 */
@Component
@Order(20)
public class FraudPolicyRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FraudPolicyRegistrar.class);

    private final EnginePolicyVersionService policyVersionService;

    public FraudPolicyRegistrar(EnginePolicyVersionService policyVersionService) {
        this.policyVersionService = policyVersionService;
    }

    /**
     * Registers the platform-scoped deterministic Fraud Policy v1.0.0.
     *
     * @return the saved or existing EnginePolicyVersion entity
     */
    @Transactional
    public EnginePolicyVersion registerDefaultFraudPolicy() {
        log.info("Ensuring registration of deterministic FRAUD policy: key={}, version={}",
                FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION);

        EnginePolicyVersion policy = policyVersionService.registerPolicyVersion(
                null,
                FraudPolicyDefinition.SCOPE,
                FraudPolicyDefinition.ENGINE_TYPE,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION,
                FraudPolicyDefinition.getExpectedContentHash(),
                FraudPolicyDefinition.SCHEMA_VERSION,
                FraudPolicyDefinition.DESCRIPTION,
                FraudPolicyDefinition.getCanonicalPolicyContent(),
                null
        );

        log.info("Deterministic FRAUD policy active: id={}, hash={}, activatedAt={}",
                policy.getId(), policy.getContentHash(), policy.getActivatedAt());

        return policy;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            registerDefaultFraudPolicy();
        } catch (Exception ex) {
            log.error("[STARTUP ERROR] Failed to register deterministic FRAUD policy: {}", ex.getMessage(), ex);
            throw ex;
        }
    }
}
