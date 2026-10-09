package com.finx.engine.policy.release;

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
class ReleasePolicyRegistrationTest {

    @Mock
    private EnginePolicyVersionRepository repository;

    private EnginePolicyVersionService policyVersionService;
    private ReleasePolicyRegistrar registrar;

    @BeforeEach
    void setUp() {
        policyVersionService = new EnginePolicyVersionService(repository);
        registrar = new ReleasePolicyRegistrar(policyVersionService);
        when(repository.save(any(EnginePolicyVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("1. First registration saves platform release policy version v1.0.0 with exact metadata")
    void testFirstRegistrationSuccess() {
        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.empty());

        EnginePolicyVersion registered = registrar.registerDefaultReleasePolicy();

        assertThat(registered).isNotNull();
        assertThat(registered.getScope()).isEqualTo(PolicyScope.PLATFORM);
        assertThat(registered.getOrganizationId()).isNull();
        assertThat(registered.getEngineType()).isEqualTo(EngineType.RELEASE);
        assertThat(registered.getPolicyKey()).isEqualTo(ReleasePolicyDefinition.POLICY_KEY);
        assertThat(registered.getVersion()).isEqualTo("1.0.0");
        assertThat(registered.getSchemaVersion()).isEqualTo(ReleasePolicyDefinition.SCHEMA_VERSION);
        assertThat(registered.getContentHash()).isEqualTo(ReleasePolicyDefinition.getExpectedContentHash());
        assertThat(registered.getPolicyContent()).isEqualTo(ReleasePolicyDefinition.getCanonicalPolicyContent());
        assertThat(registered.getActivatedAt()).isNotNull();
        assertThat(registered.getCreatedBy()).isNull();

        ArgumentCaptor<EnginePolicyVersion> captor = ArgumentCaptor.forClass(EnginePolicyVersion.class);
        verify(repository, times(1)).save(captor.capture());
        EnginePolicyVersion saved = captor.getValue();
        assertThat(saved.getPolicyKey()).isEqualTo(ReleasePolicyDefinition.POLICY_KEY);
        assertThat(saved.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("2. Re-registration with matching hash is idempotent without second write")
    void testIdempotentRegistration() {
        EnginePolicyVersion existing = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.RELEASE,
                ReleasePolicyDefinition.POLICY_KEY,
                ReleasePolicyDefinition.POLICY_VERSION,
                ReleasePolicyDefinition.getExpectedContentHash(),
                ReleasePolicyDefinition.SCHEMA_VERSION,
                ReleasePolicyDefinition.DESCRIPTION,
                ReleasePolicyDefinition.getCanonicalPolicyContent(),
                null,
                Instant.now().minusSeconds(100)
        );

        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.of(existing));

        EnginePolicyVersion registered = registrar.registerDefaultReleasePolicy();

        assertThat(registered).isSameAs(existing);
        verify(repository, never()).save(any(EnginePolicyVersion.class));
    }

    @Test
    @DisplayName("3. Re-registration with hash mismatch throws IllegalStateException")
    void testHashMismatchFailsClosed() {
        EnginePolicyVersion corrupted = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.RELEASE,
                ReleasePolicyDefinition.POLICY_KEY,
                ReleasePolicyDefinition.POLICY_VERSION,
                "corrupted_hash_value_1234567890abcdef1234567890abcdef1234567890abcdef",
                ReleasePolicyDefinition.SCHEMA_VERSION,
                ReleasePolicyDefinition.DESCRIPTION,
                "{}",
                null,
                Instant.now().minusSeconds(100)
        );

        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.of(corrupted));

        assertThatThrownBy(() -> registrar.registerDefaultReleasePolicy())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists with different content hash");

        verify(repository, never()).save(any(EnginePolicyVersion.class));
    }
}
