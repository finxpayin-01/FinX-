package com.finx.engine.policy.fraud;

import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.engine.service.EnginePolicyVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudPolicyRegistrationTest {

    private static final String V1_0_0_HASH = "c27e12a196da104c8ab61cd715dc24e274d4ecf2e6f80781bcc0218353482c9a";

    @Mock
    private EnginePolicyVersionRepository repository;

    private EnginePolicyVersionService policyVersionService;
    private FraudPolicyRegistrar registrar;

    @BeforeEach
    void setUp() {
        policyVersionService = new EnginePolicyVersionService(repository);
        registrar = new FraudPolicyRegistrar(policyVersionService);
        when(repository.save(any(EnginePolicyVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("F1.1-05: First registration saves platform fraud policy version v1.0.1 with exact metadata")
    void testFirstRegistrationSuccess() {
        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.empty());

        EnginePolicyVersion registered = registrar.registerDefaultFraudPolicy();

        assertThat(registered).isNotNull();
        assertThat(registered.getScope()).isEqualTo(PolicyScope.PLATFORM);
        assertThat(registered.getOrganizationId()).isNull();
        assertThat(registered.getEngineType()).isEqualTo(EngineType.FRAUD);
        assertThat(registered.getPolicyKey()).isEqualTo(FraudPolicyDefinition.POLICY_KEY);
        assertThat(registered.getVersion()).isEqualTo("1.0.1");
        assertThat(registered.getSchemaVersion()).isEqualTo(FraudPolicyDefinition.SCHEMA_VERSION);
        assertThat(registered.getContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());
        assertThat(registered.getPolicyContent()).isEqualTo(FraudPolicyDefinition.getCanonicalPolicyContent());
        assertThat(registered.getActivatedAt()).isNotNull();
        assertThat(registered.getCreatedBy()).isNull();

        ArgumentCaptor<EnginePolicyVersion> captor = ArgumentCaptor.forClass(EnginePolicyVersion.class);
        verify(repository, times(1)).save(captor.capture());
        EnginePolicyVersion saved = captor.getValue();
        assertThat(saved.getPolicyKey()).isEqualTo(FraudPolicyDefinition.POLICY_KEY);
        assertThat(saved.getVersion()).isEqualTo("1.0.1");
        assertThat(saved.getContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());
    }

    @Test
    @DisplayName("F1.1-06: Idempotent replay of v1.0.1 returns existing policy without duplicate insertion")
    void testIdempotentReplayReturnsExisting() {
        EnginePolicyVersion existing = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION,
                FraudPolicyDefinition.getExpectedContentHash(),
                FraudPolicyDefinition.SCHEMA_VERSION,
                FraudPolicyDefinition.DESCRIPTION,
                FraudPolicyDefinition.getCanonicalPolicyContent(),
                null,
                Instant.now()
        );

        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.of(existing));

        EnginePolicyVersion result = registrar.registerDefaultFraudPolicy();

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any(EnginePolicyVersion.class));
    }

    @Test
    @DisplayName("F1.1-07: Conflicting hash for v1.0.1 throws IllegalStateException (Fail Closed)")
    void testConflictingHashThrowsIllegalStateException() {
        EnginePolicyVersion conflicting = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION,
                "different_hash_0000000000000000000000000000000000000000000000000000000",
                FraudPolicyDefinition.SCHEMA_VERSION,
                FraudPolicyDefinition.DESCRIPTION,
                "{}",
                null,
                Instant.now()
        );

        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> registrar.registerDefaultFraudPolicy())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists with different content hash");

        verify(repository, never()).save(any(EnginePolicyVersion.class));
    }

    @Test
    @DisplayName("F1.1-08: Registering v1.0.1 does NOT mutate or overwrite historical v1.0.0 record")
    void testHistoricalV100RemainsUnchanged() {
        EnginePolicyVersion historicalV100 = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                "1.0.0",
                V1_0_0_HASH,
                "1.0",
                "Historical v1.0.0 policy",
                "{\"version\":\"1.0.0\"}",
                null,
                Instant.now()
        );

        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, "1.0.0"
        )).thenReturn(Optional.of(historicalV100));

        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, "1.0.1"
        )).thenReturn(Optional.empty());

        EnginePolicyVersion v101 = registrar.registerDefaultFraudPolicy();

        assertThat(v101.getVersion()).isEqualTo("1.0.1");
        assertThat(v101.getContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());

        // Verify historical v1.0.0 is untouched
        Optional<EnginePolicyVersion> retrievedV100 = policyVersionService.getPlatformPolicy(
                EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, "1.0.0"
        );
        assertThat(retrievedV100).isPresent();
        assertThat(retrievedV100.get().getVersion()).isEqualTo("1.0.0");
        assertThat(retrievedV100.get().getContentHash()).isEqualTo(V1_0_0_HASH);
    }
}
