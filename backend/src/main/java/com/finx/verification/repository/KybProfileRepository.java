package com.finx.verification.repository;

import com.finx.verification.entity.KybProfile;
import com.finx.verification.enums.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface KybProfileRepository extends JpaRepository<KybProfile, UUID> {
    Optional<KybProfile> findByOrganizationId(UUID organizationId);
    boolean existsByOrganizationIdAndStatus(UUID organizationId, VerificationStatus status);
}
