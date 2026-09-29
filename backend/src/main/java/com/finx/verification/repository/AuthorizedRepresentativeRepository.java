package com.finx.verification.repository;

import com.finx.verification.entity.AuthorizedRepresentative;
import com.finx.verification.enums.AuthorityStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthorizedRepresentativeRepository extends JpaRepository<AuthorizedRepresentative, UUID> {
    List<AuthorizedRepresentative> findByOrganizationId(UUID organizationId);
    Optional<AuthorizedRepresentative> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
    boolean existsByOrganizationIdAndUserIdAndAuthorityStatus(UUID organizationId, UUID userId, AuthorityStatus status);
    long countByOrganizationIdAndAuthorityStatus(UUID organizationId, AuthorityStatus status);
}
