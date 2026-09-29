package com.finx.verification.repository;

import com.finx.verification.entity.VerificationConsent;
import com.finx.verification.enums.ConsentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationConsentRepository extends JpaRepository<VerificationConsent, UUID> {
    Optional<VerificationConsent> findTopByUserIdAndVerificationTypeOrderByConsentedAtDesc(UUID userId, ConsentType verificationType);
    Optional<VerificationConsent> findTopByUserIdAndOrganizationIdAndVerificationTypeOrderByConsentedAtDesc(UUID userId, UUID organizationId, ConsentType verificationType);
    boolean existsByUserIdAndVerificationType(UUID userId, ConsentType verificationType);
    boolean existsByUserIdAndOrganizationIdAndVerificationType(UUID userId, UUID organizationId, ConsentType verificationType);
}
