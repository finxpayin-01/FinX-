package com.finx.engine.service;

import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for registering and querying immutable policy versions.
 * Policy versions are append-only; mutations are prohibited.
 */
@Service
@Transactional(readOnly = true)
public class EnginePolicyVersionService {

    private static final Logger log = LoggerFactory.getLogger(EnginePolicyVersionService.class);

    private final EnginePolicyVersionRepository repository;

    public EnginePolicyVersionService(EnginePolicyVersionRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers an immutable policy version.
     * If an identical policy version already exists, it is returned safely.
     * If a policy version exists with different content hash or configuration, an IllegalStateException is thrown.
     */
    @Transactional
    public EnginePolicyVersion registerPolicyVersion(
            UUID organizationId,
            PolicyScope scope,
            EngineType engineType,
            String policyKey,
            String version,
            String contentHash,
            String schemaVersion,
            String description,
            String policyContent,
            UUID createdBy
    ) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope == PolicyScope.PLATFORM && organizationId != null) {
            throw new IllegalArgumentException("Platform-scoped policy must have null organizationId");
        }
        if (scope == PolicyScope.ORGANIZATION && organizationId == null) {
            throw new IllegalArgumentException("Organization-scoped policy must have non-null organizationId");
        }
        if (engineType == null) {
            throw new IllegalArgumentException("engineType must not be null");
        }
        if (policyKey == null || policyKey.isBlank()) {
            throw new IllegalArgumentException("policyKey must not be null or blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be null or blank");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be null or blank");
        }

        // Check for existing policy version
        Optional<EnginePolicyVersion> existing = (scope == PolicyScope.PLATFORM)
                ? repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(engineType, policyKey.trim(), version.trim())
                : repository.findByOrganizationIdAndEngineTypeAndPolicyKeyAndVersion(organizationId, engineType, policyKey.trim(), version.trim());

        if (existing.isPresent()) {
            EnginePolicyVersion found = existing.get();
            if (!Objects.equals(found.getContentHash(), contentHash.trim())) {
                throw new IllegalStateException(String.format(
                        "Policy version %s for key %s already exists with different content hash (%s vs %s)",
                        version, policyKey, found.getContentHash(), contentHash
                ));
            }
            log.info("Policy version already exists and matches content hash: key={}, version={}", policyKey, version);
            return found;
        }

        EnginePolicyVersion newPolicy = new EnginePolicyVersion(
                organizationId,
                scope,
                engineType,
                policyKey,
                version,
                contentHash,
                schemaVersion != null ? schemaVersion : "1.0",
                description,
                policyContent,
                createdBy,
                Instant.now()
        );

        try {
            return repository.save(newPolicy);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent insert of policy version key={}, version={}. Re-querying.", policyKey, version);
            Optional<EnginePolicyVersion> concurrent = (scope == PolicyScope.PLATFORM)
                    ? repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(engineType, policyKey.trim(), version.trim())
                    : repository.findByOrganizationIdAndEngineTypeAndPolicyKeyAndVersion(organizationId, engineType, policyKey.trim(), version.trim());
            if (concurrent.isPresent()) {
                if (!Objects.equals(concurrent.get().getContentHash(), contentHash.trim())) {
                    throw new IllegalStateException("Concurrent conflict for policy version " + version);
                }
                return concurrent.get();
            }
            throw ex;
        }
    }

    public Optional<EnginePolicyVersion> getPlatformPolicy(EngineType engineType, String policyKey, String version) {
        if (engineType == null || policyKey == null || version == null) {
            return Optional.empty();
        }
        return repository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(engineType, policyKey.trim(), version.trim());
    }

    public Optional<EnginePolicyVersion> getOrganizationPolicy(UUID organizationId, EngineType engineType, String policyKey, String version) {
        if (organizationId == null || engineType == null || policyKey == null || version == null) {
            return Optional.empty();
        }
        return repository.findByOrganizationIdAndEngineTypeAndPolicyKeyAndVersion(organizationId, engineType, policyKey.trim(), version.trim());
    }

    /**
     * Resolves effective policy version: checks organization-specific policy first,
     * falling back to platform-scoped policy if no organization override is found.
     */
    public Optional<EnginePolicyVersion> getEffectivePolicy(UUID organizationId, EngineType engineType, String policyKey, String version) {
        if (organizationId != null) {
            Optional<EnginePolicyVersion> orgPolicy = getOrganizationPolicy(organizationId, engineType, policyKey, version);
            if (orgPolicy.isPresent()) {
                return orgPolicy;
            }
        }
        return getPlatformPolicy(engineType, policyKey, version);
    }

    public List<EnginePolicyVersion> listPlatformPolicies(EngineType engineType, String policyKey) {
        return repository.findPlatformPolicies(engineType, policyKey);
    }

    public List<EnginePolicyVersion> listOrganizationPolicies(UUID organizationId, EngineType engineType, String policyKey) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId must not be null");
        }
        return repository.findOrganizationPolicies(organizationId, engineType, policyKey);
    }
}
