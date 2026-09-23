package com.finx.org.repository;

import com.finx.org.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findByGstin(String gstin);
    Optional<Organization> findByTaxId(String taxId);

    @Query("SELECT o FROM Organization o JOIN OrganizationMember m ON o.id = m.organizationId WHERE m.userId = :userId AND m.status = 'ACTIVE'")
    List<Organization> findByMemberUserId(@Param("userId") UUID userId);
}
