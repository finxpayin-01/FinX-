package com.finx.verification.repository;

import com.finx.verification.entity.KycProfile;
import com.finx.verification.enums.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycProfileRepository extends JpaRepository<KycProfile, UUID> {
    Optional<KycProfile> findByUserId(UUID userId);
    boolean existsByUserIdAndStatus(UUID userId, VerificationStatus status);
}
