package com.finx.engine.policy.fraud;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.service.EnginePolicyVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FraudPolicyBootstrapSpringTest {

    @Autowired
    private EnginePolicyVersionService policyVersionService;

    @Autowired
    private FraudPolicyRegistrar fraudPolicyRegistrar;

    @Autowired
    private EngineDecisionRepository decisionRepository;

    @MockBean
    private BankingProvider bankingProvider;

    @Test
    @DisplayName("F1.1-09: Spring application boot automatically registers deterministic FRAUD policy v1.0.1")
    void testStartupBootstrapPolicyRegistration() {
        Optional<EnginePolicyVersion> policyOpt = policyVersionService.getPlatformPolicy(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION
        );

        assertThat(policyOpt).isPresent();
        EnginePolicyVersion policy = policyOpt.get();

        assertThat(policy.getScope()).isEqualTo(PolicyScope.PLATFORM);
        assertThat(policy.getOrganizationId()).isNull();
        assertThat(policy.getEngineType()).isEqualTo(EngineType.FRAUD);
        assertThat(policy.getPolicyKey()).isEqualTo(FraudPolicyDefinition.POLICY_KEY);
        assertThat(policy.getVersion()).isEqualTo("1.0.1");
        assertThat(policy.getSchemaVersion()).isEqualTo(FraudPolicyDefinition.SCHEMA_VERSION);
        assertThat(policy.getContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());
        assertThat(policy.getPolicyContent()).isEqualTo(FraudPolicyDefinition.getCanonicalPolicyContent());
        assertThat(policy.getActivatedAt()).isNotNull();

        // Verify zero decisions recorded during policy registration
        assertThat(decisionRepository.count()).isEqualTo(0);

        // Verify zero banking calls made
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("F1.1-10: Re-executing registration service manually is idempotent and produces no duplicates for v1.0.1")
    void testManualRegistrationReplayIdempotency() {
        EnginePolicyVersion replayed = fraudPolicyRegistrar.registerDefaultFraudPolicy();
        assertThat(replayed).isNotNull();
        assertThat(replayed.getPolicyKey()).isEqualTo(FraudPolicyDefinition.POLICY_KEY);
        assertThat(replayed.getVersion()).isEqualTo("1.0.1");
        assertThat(replayed.getContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());

        List<EnginePolicyVersion> platformPolicies = policyVersionService.listPlatformPolicies(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY
        );
        // In this test context, at least 1 policy exists and none are duplicates for 1.0.1
        long v101Count = platformPolicies.stream()
                .filter(p -> "1.0.1".equals(p.getVersion()))
                .count();
        assertThat(v101Count).isEqualTo(1);
    }
}
