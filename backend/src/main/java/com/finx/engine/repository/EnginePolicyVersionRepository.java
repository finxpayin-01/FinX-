package com.finx.engine.repository;

import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.EngineType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EnginePolicyVersionRepository extends JpaRepository<EnginePolicyVersion, UUID> {

    Optional<EnginePolicyVersion> findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
            EngineType engineType,
            String policyKey,
            String version
    );

    Optional<EnginePolicyVersion> findByOrganizationIdAndEngineTypeAndPolicyKeyAndVersion(
            UUID organizationId,
            EngineType engineType,
            String policyKey,
            String version
    );

    @Query("SELECT p FROM EnginePolicyVersion p WHERE p.engineType = :engineType AND p.policyKey = :policyKey AND p.organizationId IS NULL ORDER BY p.createdAt DESC")
    List<EnginePolicyVersion> findPlatformPolicies(
            @Param("engineType") EngineType engineType,
            @Param("policyKey") String policyKey
    );

    @Query("SELECT p FROM EnginePolicyVersion p WHERE p.organizationId = :organizationId AND p.engineType = :engineType AND p.policyKey = :policyKey ORDER BY p.createdAt DESC")
    List<EnginePolicyVersion> findOrganizationPolicies(
            @Param("organizationId") UUID organizationId,
            @Param("engineType") EngineType engineType,
            @Param("policyKey") String policyKey
    );
}
