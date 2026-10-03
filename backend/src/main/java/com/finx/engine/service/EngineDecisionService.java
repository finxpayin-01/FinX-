package com.finx.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineDecisionFingerprint;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.engine.repository.EngineDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for recording and querying immutable engine decisions.
 * <p>
 * Responsibilities:
 * 1. Validates invariants (e.g. evaluation failure must be REVIEW + non-blank error code).
 * 2. Persists new decisions (append-only; no updates or deletes permitted).
 * 3. Enforces logical duplicate detection and conflict detection via deterministic SHA-256 fingerprinting.
 * 4. Provides strictly tenant-scoped read queries (unscoped queries are prohibited).
 * <p>
 * SAFETY BOUNDARY:
 * This service records audit evidence and decision evaluations ONLY.
 * It does NOT execute settlements, mutate ledgers, transfer escrow funds,
 * or interact with any banking provider.
 */
@Service
@Transactional(readOnly = true)
public class EngineDecisionService {

    private static final Logger log = LoggerFactory.getLogger(EngineDecisionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final EngineDecisionRepository repository;

    public EngineDecisionService(EngineDecisionRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a new immutable engine decision.
     * <p>
     * If an identical evaluation result already exists for the logical identity
     * (organizationId, evaluationId, engineType, subjectType, subjectId) with matching fingerprint,
     * the existing record is returned (safe deterministic replay).
     * <p>
     * If a decision exists for the same logical identity with conflicting content,
     * an IllegalStateException is thrown.
     */
    @Transactional
    public EngineDecision recordDecision(RecordEngineDecisionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        String reasonCodesJson = serializeJson(command.getReasonCodes(), "[]");
        String evidenceRefsJson = serializeJson(command.getEvidenceReferences(), "[]");

        String computedFingerprint = EngineDecisionFingerprint.calculate(
                command.getOrganizationId(),
                command.getEngineType(),
                command.getSubjectType(),
                command.getSubjectId(),
                command.getDecision(),
                command.getEvaluationOutcome(),
                command.getErrorCode(),
                command.getScore(),
                command.getRuleVersion(),
                command.getPolicyContentHash(),
                command.getInputFingerprint(),
                command.getReasonCodes(),
                command.getEvidenceReferences()
        );

        // Check for existing decision for this logical identity
        Optional<EngineDecision> existing = repository.findByOrganizationIdAndEvaluationIdAndEngineTypeAndSubjectTypeAndSubjectId(
                command.getOrganizationId(),
                command.getEvaluationId(),
                command.getEngineType(),
                command.getSubjectType(),
                command.getSubjectId()
        );

        if (existing.isPresent()) {
            EngineDecision found = existing.get();
            if (Objects.equals(found.getDecisionFingerprint(), computedFingerprint)) {
                log.info("Identical engine decision replay detected for evaluationId={}, engineType={}, subjectId={}. Returning existing record.",
                        command.getEvaluationId(), command.getEngineType(), command.getSubjectId());
                return found;
            } else {
                throw new IllegalStateException(String.format(
                        "Conflicting decision detected for logical identity: evaluationId=%s, engineType=%s, subjectType=%s, subjectId=%s. Existing fingerprint=%s, new fingerprint=%s",
                        command.getEvaluationId(), command.getEngineType(), command.getSubjectType(), command.getSubjectId(),
                        found.getDecisionFingerprint(), computedFingerprint
                ));
            }
        }

        EngineDecision decision = new EngineDecision(
                command.getOrganizationId(),
                command.getEngineType(),
                command.getSubjectType(),
                command.getSubjectId(),
                command.getDecision(),
                command.getEvaluationOutcome(),
                command.getErrorCode(),
                command.getScore(),
                reasonCodesJson,
                command.getExplanation(),
                evidenceRefsJson,
                command.getRuleVersion(),
                command.getPolicyVersionId(),
                command.getPolicyContentHash(),
                command.getSourceAssessmentId(),
                command.getEvaluationId(),
                command.getCorrelationId(),
                command.getInputFingerprint(),
                command.getInputRevision(),
                command.getSchemaVersion(),
                command.getEngineImplementationVersion(),
                computedFingerprint,
                command.getValidUntil(),
                command.getEvaluatedAt()
        );

        try {
            EngineDecision saved = repository.save(decision);
            log.info("Recorded engine decision id={}, engineType={}, decision={}, outcome={}",
                    saved.getId(), saved.getEngineType(), saved.getDecision(), saved.getEvaluationOutcome());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent duplicate insert detected for evaluationId={}, engineType={}. Verifying logical identity.",
                    command.getEvaluationId(), command.getEngineType());
            Optional<EngineDecision> concurrent = repository.findByOrganizationIdAndEvaluationIdAndEngineTypeAndSubjectTypeAndSubjectId(
                    command.getOrganizationId(),
                    command.getEvaluationId(),
                    command.getEngineType(),
                    command.getSubjectType(),
                    command.getSubjectId()
            );
            if (concurrent.isPresent()) {
                if (Objects.equals(concurrent.get().getDecisionFingerprint(), computedFingerprint)) {
                    return concurrent.get();
                } else {
                    throw new IllegalStateException("Concurrent conflict for evaluationId=" + command.getEvaluationId(), ex);
                }
            }
            throw ex;
        }
    }

    /**
     * Convenience method to safely record an evaluation error in compliance with the fail-closed invariant:
     * evaluation error => DecisionStatus.REVIEW, EvaluationOutcome.ERROR, non-blank errorCode.
     */
    @Transactional
    public EngineDecision recordEvaluationError(
            UUID organizationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId,
            UUID evaluationId,
            UUID correlationId,
            String errorCode,
            String explanation,
            List<String> reasonCodes,
            Instant evaluatedAt
    ) {
        RecordEngineDecisionCommand cmd = RecordEngineDecisionCommand.builder()
                .organizationId(organizationId)
                .engineType(engineType)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .decision(DecisionStatus.REVIEW)
                .evaluationOutcome(EvaluationOutcome.ERROR)
                .errorCode(errorCode)
                .explanation(explanation)
                .reasonCodes(reasonCodes)
                .evaluationId(evaluationId)
                .correlationId(correlationId)
                .evaluatedAt(evaluatedAt != null ? evaluatedAt : Instant.now())
                .build();

        return recordDecision(cmd);
    }

    // ==========================================
    // Strictly Tenant-Scoped Read Queries
    // ==========================================

    public Optional<EngineDecision> findLatestForSubjectAndEngine(
            UUID organizationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId
    ) {
        validateTenantScope(organizationId);
        if (engineType == null) {
            throw new IllegalArgumentException("engineType must not be null");
        }
        if (subjectType == null || subjectType.isBlank()) {
            throw new IllegalArgumentException("subjectType must not be null or blank");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId must not be null");
        }
        return repository.findFirstByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
                organizationId, engineType, subjectType.trim(), subjectId
        );
    }

    public List<EngineDecision> findHistoryForSubjectAndEngine(
            UUID organizationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId
    ) {
        validateTenantScope(organizationId);
        if (engineType == null) {
            throw new IllegalArgumentException("engineType must not be null");
        }
        if (subjectType == null || subjectType.isBlank()) {
            throw new IllegalArgumentException("subjectType must not be null or blank");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId must not be null");
        }
        return repository.findByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
                organizationId, engineType, subjectType.trim(), subjectId
        );
    }

    public List<EngineDecision> findByEvaluation(UUID organizationId, UUID evaluationId) {
        validateTenantScope(organizationId);
        if (evaluationId == null) {
            throw new IllegalArgumentException("evaluationId must not be null");
        }
        return repository.findByOrganizationIdAndEvaluationIdOrderByEvaluatedAtAsc(organizationId, evaluationId);
    }

    public List<EngineDecision> findByCorrelation(UUID organizationId, UUID correlationId) {
        validateTenantScope(organizationId);
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }
        return repository.findByOrganizationIdAndCorrelationIdOrderByEvaluatedAtAsc(organizationId, correlationId);
    }

    private void validateTenantScope(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId must not be null for tenant-scoped operations");
        }
    }

    private String serializeJson(Object value, String fallback) {
        if (value == null) return fallback;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize structured object to JSON, using fallback", e);
            return fallback;
        }
    }
}
