package com.finx.org.repository;

import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.OrgRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {
    List<OrganizationMember> findByOrganizationId(UUID organizationId);
    List<OrganizationMember> findByUserId(UUID userId);
    Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
    boolean existsByOrganizationIdAndUserIdAndRole(UUID organizationId, UUID userId, OrgRole role);
}
