package com.finx.engine;

import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.engine.service.EnginePolicyVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnginePolicyVersionServiceTest {

    @Mock
    private EnginePolicyVersionRepository repository;

    private EnginePolicyVersionService service;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        service = new EnginePolicyVersionService(repository);
        orgId = UUID.randomUUID();
        when(repository.save(any(EnginePolicyVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Section 51 & 52: Platform and Organization policy scope registration and resolution")
    void testPolicyScopeRegistrationAndResolution() {
        EnginePolicyVersion platformPolicy = EnginePolicyVersion.platform(
                EngineType.RISK, "RISK_BASELINE", "v1.0", "sha256_platform_hash"
        );
        EnginePolicyVersion orgPolicy = EnginePolicyVersion.organization(
                orgId, EngineType.RISK, "RISK_BASELINE", "v1.0", "sha256_org_hash"
        );

        when(repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(EngineType.RISK, "RISK_BASELINE", "v1.0"))
                .thenReturn(Optional.of(platformPolicy));
        when(repository.findByOrganizationIdAndEngineTypeAndPolicyKeyAndVersion(orgId, EngineType.RISK, "RISK_BASELINE", "v1.0"))
                .thenReturn(Optional.of(orgPolicy));

        // 1. Re-registering existing platform policy with same hash returns existing
        EnginePolicyVersion replayed = service.registerPolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RISK, "RISK_BASELINE", "v1.0",
                "sha256_platform_hash", "1.0", null, null, null
        );
        assertThat(replayed).isSameAs(platformPolicy);
        verify(repository, never()).save(any());

        // 2. Re-registering existing platform policy with different hash throws conflict
        assertThatThrownBy(() -> service.registerPolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RISK, "RISK_BASELINE", "v1.0",
                "sha256_DIFFERENT_hash", "1.0", null, null, null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists with different content hash");

        // 3. Effective policy resolution: org override takes precedence
        Optional<EnginePolicyVersion> effectiveOrg = service.getEffectivePolicy(orgId, EngineType.RISK, "RISK_BASELINE", "v1.0");
        assertThat(effectiveOrg).isPresent();
        assertThat(effectiveOrg.get().getScope()).isEqualTo(PolicyScope.ORGANIZATION);
        assertThat(effectiveOrg.get().getContentHash()).isEqualTo("sha256_org_hash");

        // 4. Effective policy resolution: falls back to platform when org has no override
        UUID otherOrgId = UUID.randomUUID();
        Optional<EnginePolicyVersion> effectiveFallback = service.getEffectivePolicy(otherOrgId, EngineType.RISK, "RISK_BASELINE", "v1.0");
        assertThat(effectiveFallback).isPresent();
        assertThat(effectiveFallback.get().getScope()).isEqualTo(PolicyScope.PLATFORM);
        assertThat(effectiveFallback.get().getContentHash()).isEqualTo("sha256_platform_hash");
    }
}
