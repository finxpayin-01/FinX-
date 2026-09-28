package com.finx.trust.repository;

import com.finx.trust.entity.TrustMembership;
import com.finx.trust.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.*;
import java.util.*;
import java.time.Instant;

public interface TrustMembershipRepository extends JpaRepository<TrustMembership, UUID> {
    List<TrustMembership> findByOrganizationId(UUID organizationId);
}
