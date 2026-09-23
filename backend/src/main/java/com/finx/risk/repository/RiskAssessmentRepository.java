package com.finx.risk.repository;

import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.enums.RiskEnums.RiskEntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {
    Optional<RiskAssessment> findByEntityTypeAndEntityId(RiskEntityType entityType, UUID entityId);
}
