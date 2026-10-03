package com.finx.engine.repository;

import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.EngineType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EngineDecisionRepository extends JpaRepository<EngineDecision, UUID> {

    Optional<EngineDecision> findByOrganizationIdAndEvaluationIdAndEngineTypeAndSubjectTypeAndSubjectId(
            UUID organizationId,
            UUID evaluationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId
    );

    Optional<EngineDecision> findFirstByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
            UUID organizationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId
    );

    List<EngineDecision> findByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
            UUID organizationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId
    );

    List<EngineDecision> findByOrganizationIdAndEvaluationIdOrderByEvaluatedAtAsc(
            UUID organizationId,
            UUID evaluationId
    );

    List<EngineDecision> findByOrganizationIdAndCorrelationIdOrderByEvaluatedAtAsc(
            UUID organizationId,
            UUID correlationId
    );

    long countByOrganizationIdAndEvaluationIdAndEngineTypeAndSubjectTypeAndSubjectId(
            UUID organizationId,
            UUID evaluationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId
    );
}
