package com.finx.engine.policy.release;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReleasePolicyBootstrapSpringTest {

    @Autowired
    private EnginePolicyVersionRepository repository;

    @Autowired
    private ReleasePolicyRegistrar registrar;

    @MockBean
    private BankingProvider banking;

    @Test
    @DisplayName("Verify Spring boot automatically boots and registers ReleasePolicy v1.0.0")
    void testSpringContextBootstrap() {
        Optional<EnginePolicyVersion> opt = repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        );

        assertThat(opt).isPresent();
        EnginePolicyVersion policy = opt.get();
        assertThat(policy.getScope()).isEqualTo(PolicyScope.PLATFORM);
        assertThat(policy.getContentHash()).isEqualTo(ReleasePolicyDefinition.getExpectedContentHash());
        assertThat(policy.getActivatedAt()).isNotNull();
    }
}
