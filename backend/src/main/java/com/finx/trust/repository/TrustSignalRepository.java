package com.finx.trust.repository;

import com.finx.trust.entity.TrustSignal;
import com.finx.trust.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.*;
import java.util.*;
import java.time.Instant;

public interface TrustSignalRepository extends JpaRepository<TrustSignal, UUID> {
    List<TrustSignal> findByOrganizationIdAndCalculationIdAndRoleOrderByImpactAsc(UUID id, UUID calculationId, TrustRole role, Pageable page);
}
