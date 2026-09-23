package com.finx.risk.repository;

import com.finx.risk.entity.RiskSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RiskSignalRepository extends JpaRepository<RiskSignal, UUID> {
    List<RiskSignal> findByRiskAssessmentId(UUID riskAssessmentId);
}
