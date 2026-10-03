package com.finx.engine.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.engine.model.EvidenceReference;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable, append-only evaluation envelope recording an engine decision.
 * All mutations and deletes are strictly prohibited.
 */
@Entity
@Immutable
@Table(name = "engine_decisions")
public class EngineDecision {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "engine_type", nullable = false, updatable = false, length = 50)
    private EngineType engineType;

    @Column(name = "subject_type", nullable = false, updatable = false, length = 50)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 20)
    private DecisionStatus decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_outcome", nullable = false, updatable = false, length = 20)
    private EvaluationOutcome evaluationOutcome = EvaluationOutcome.SUCCESS;

    @Column(name = "error_code", updatable = false, length = 100)
    private String errorCode;

    @Column(name = "score", updatable = false, precision = 10, scale = 4)
    private BigDecimal score;

    @Column(name = "reason_codes_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String reasonCodesJson = "[]";

    @Column(name = "explanation", updatable = false, length = 1000)
    private String explanation;

    @Column(name = "evidence_references_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String evidenceReferencesJson = "[]";

    @Column(name = "rule_version", updatable = false, length = 100)
    private String ruleVersion;

    @Column(name = "policy_version_id", updatable = false)
    private UUID policyVersionId;

    @Column(name = "policy_content_hash", updatable = false, length = 64)
    private String policyContentHash;

    @Column(name = "source_assessment_id", updatable = false)
    private UUID sourceAssessmentId;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "input_fingerprint", updatable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "input_revision", updatable = false, length = 100)
    private String inputRevision;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 20)
    private String schemaVersion = "1.0";

    @Column(name = "engine_implementation_version", nullable = false, updatable = false, length = 50)
    private String engineImplementationVersion = "1.0.0";

    @Column(name = "decision_fingerprint", updatable = false, length = 64)
    private String decisionFingerprint;

    @Column(name = "valid_until", updatable = false)
    private Instant validUntil;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public EngineDecision() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.schemaVersion = "1.0";
        this.engineImplementationVersion = "1.0.0";
    }

    public EngineDecision(
            UUID organizationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId,
            DecisionStatus decision,
            EvaluationOutcome evaluationOutcome,
            String errorCode,
            BigDecimal score,
            String reasonCodesJson,
            String explanation,
            String evidenceReferencesJson,
            String ruleVersion,
            UUID policyVersionId,
            String policyContentHash,
            UUID sourceAssessmentId,
            UUID evaluationId,
            UUID correlationId,
            String inputFingerprint,
            String inputRevision,
            String schemaVersion,
            String engineImplementationVersion,
            String decisionFingerprint,
            Instant validUntil,
            Instant evaluatedAt
    ) {
        this();
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId must not be null");
        }
        if (engineType == null) {
            throw new IllegalArgumentException("engineType must not be null");
        }
        if (subjectType == null || subjectType.isBlank()) {
            throw new IllegalArgumentException("subjectType must not be null or blank");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId must not be null");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (evaluationId == null) {
            throw new IllegalArgumentException("evaluationId must not be null");
        }
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("evaluatedAt must not be null");
        }

        this.organizationId = organizationId;
        this.engineType = engineType;
        this.subjectType = subjectType.trim();
        this.subjectId = subjectId;
        this.decision = decision;
        this.evaluationOutcome = evaluationOutcome != null ? evaluationOutcome : EvaluationOutcome.SUCCESS;
        this.errorCode = errorCode;
        this.score = score;
        this.reasonCodesJson = (reasonCodesJson != null && !reasonCodesJson.isBlank()) ? reasonCodesJson : "[]";
        this.explanation = explanation;
        this.evidenceReferencesJson = (evidenceReferencesJson != null && !evidenceReferencesJson.isBlank()) ? evidenceReferencesJson : "[]";
        this.ruleVersion = ruleVersion;
        this.policyVersionId = policyVersionId;
        this.policyContentHash = policyContentHash;
        this.sourceAssessmentId = sourceAssessmentId;
        this.evaluationId = evaluationId;
        this.correlationId = correlationId;
        this.inputFingerprint = inputFingerprint;
        this.inputRevision = inputRevision;
        this.schemaVersion = (schemaVersion != null && !schemaVersion.isBlank()) ? schemaVersion : "1.0";
        this.engineImplementationVersion = (engineImplementationVersion != null && !engineImplementationVersion.isBlank()) ? engineImplementationVersion : "1.0.0";
        this.decisionFingerprint = decisionFingerprint;
        this.validUntil = validUntil;
        this.evaluatedAt = evaluatedAt;
    }

    // Helper deserializers
    public List<String> getReasonCodes() {
        if (reasonCodesJson == null || reasonCodesJson.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(reasonCodesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<EvidenceReference> getEvidenceReferences() {
        if (evidenceReferencesJson == null || evidenceReferencesJson.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(evidenceReferencesJson, new TypeReference<List<EvidenceReference>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public boolean isExpired(Instant asOf) {
        return validUntil != null && asOf.isAfter(validUntil);
    }

    public boolean isError() {
        return evaluationOutcome == EvaluationOutcome.ERROR;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public EngineType getEngineType() { return engineType; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public DecisionStatus getDecision() { return decision; }
    public EvaluationOutcome getEvaluationOutcome() { return evaluationOutcome; }
    public String getErrorCode() { return errorCode; }
    public BigDecimal getScore() { return score; }
    public String getReasonCodesJson() { return reasonCodesJson; }
    public String getExplanation() { return explanation; }
    public String getEvidenceReferencesJson() { return evidenceReferencesJson; }
    public String getRuleVersion() { return ruleVersion; }
    public UUID getPolicyVersionId() { return policyVersionId; }
    public String getPolicyContentHash() { return policyContentHash; }
    public UUID getSourceAssessmentId() { return sourceAssessmentId; }
    public UUID getEvaluationId() { return evaluationId; }
    public UUID getCorrelationId() { return correlationId; }
    public String getInputFingerprint() { return inputFingerprint; }
    public String getInputRevision() { return inputRevision; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getEngineImplementationVersion() { return engineImplementationVersion; }
    public String getDecisionFingerprint() { return decisionFingerprint; }
    public Instant getValidUntil() { return validUntil; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
