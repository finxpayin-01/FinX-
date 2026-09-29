package com.finx.verification.repository;

import com.finx.verification.entity.BeneficialOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BeneficialOwnerRepository extends JpaRepository<BeneficialOwner, UUID> {
    List<BeneficialOwner> findByOrganizationId(UUID organizationId);
}
