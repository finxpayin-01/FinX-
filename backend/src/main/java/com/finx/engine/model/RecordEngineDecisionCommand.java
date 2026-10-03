package com.finx.engine.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Immutable command to record a new EngineDecision.
 * Enforces all invariant validation rules prior to persistence.
 */
public record RecordEngineDecisionCommand(
        UUID organizationId,
        EngineType engineType,
        String subjectType,
        UUID subjectId,
        DecisionStatus decision,
        EvaluationOutcome evaluationOutcome,
        String errorCode,
        BigDecimal score,
        List<String> reasonCodes,
        String explanation,
        List<EvidenceReference> evidenceReferences,
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
        Instant validUntil,
        Instant evaluatedAt
) {
    public RecordEngineDecisionCommand {
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
        if (evaluationOutcome == null) {
            evaluationOutcome = EvaluationOutcome.SUCCESS;
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

        // Error invariant: evaluation failure MUST result in REVIEW with an explicit errorCode
        if (evaluationOutcome == EvaluationOutcome.ERROR) {
            if (decision != DecisionStatus.REVIEW) {
                throw new IllegalArgumentException("Evaluation outcome ERROR requires DecisionStatus.REVIEW, but was " + decision);
            }
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("Evaluation outcome ERROR requires a non-blank errorCode");
            }
        } else {
            errorCode = null; // Clear error code on success
        }

        // Validity invariant: validUntil cannot expire before evaluation time
        if (validUntil != null && validUntil.isBefore(evaluatedAt)) {
            throw new IllegalArgumentException("validUntil must not be before evaluatedAt");
        }

        // Explanation bounds check
        if (explanation != null && explanation.length() > 1000) {
            throw new IllegalArgumentException("explanation must not exceed 1000 characters");
        }

        // Safe defensive copies
        reasonCodes = reasonCodes != null ? List.copyOf(reasonCodes) : List.of();
        evidenceReferences = evidenceReferences != null ? List.copyOf(evidenceReferences) : List.of();
        schemaVersion = schemaVersion != null ? schemaVersion : "1.0";
        engineImplementationVersion = engineImplementationVersion != null ? engineImplementationVersion : "1.0.0";
    }

    // JavaBean getters for compatibility
    public UUID getOrganizationId() { return organizationId; }
    public EngineType getEngineType() { return engineType; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public DecisionStatus getDecision() { return decision; }
    public EvaluationOutcome getEvaluationOutcome() { return evaluationOutcome; }
    public String getErrorCode() { return errorCode; }
    public BigDecimal getScore() { return score; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public String getExplanation() { return explanation; }
    public List<EvidenceReference> getEvidenceReferences() { return evidenceReferences; }
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
    public Instant getValidUntil() { return validUntil; }
    public Instant getEvaluatedAt() { return evaluatedAt; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID organizationId;
        private EngineType engineType;
        private String subjectType;
        private UUID subjectId;
        private DecisionStatus decision;
        private EvaluationOutcome evaluationOutcome = EvaluationOutcome.SUCCESS;
        private String errorCode;
        private BigDecimal score;
        private List<String> reasonCodes = new ArrayList<>();
        private String explanation;
        private List<EvidenceReference> evidenceReferences = new ArrayList<>();
        private String ruleVersion;
        private UUID policyVersionId;
        private String policyContentHash;
        private UUID sourceAssessmentId;
        private UUID evaluationId;
        private UUID correlationId;
        private String inputFingerprint;
        private String inputRevision;
        private String schemaVersion = "1.0";
        private String engineImplementationVersion = "1.0.0";
        private Instant validUntil;
        private Instant evaluatedAt = Instant.now();

        public Builder organizationId(UUID organizationId) { this.organizationId = organizationId; return this; }
        public Builder engineType(EngineType engineType) { this.engineType = engineType; return this; }
        public Builder subjectType(String subjectType) { this.subjectType = subjectType; return this; }
        public Builder subjectType(DecisionSubjectType subjectType) { this.subjectType = subjectType != null ? subjectType.name() : null; return this; }
        public Builder subjectId(UUID subjectId) { this.subjectId = subjectId; return this; }
        public Builder decision(DecisionStatus decision) { this.decision = decision; return this; }
        public Builder evaluationOutcome(EvaluationOutcome evaluationOutcome) { this.evaluationOutcome = evaluationOutcome; return this; }
        public Builder errorCode(String errorCode) { this.errorCode = errorCode; return this; }
        public Builder score(BigDecimal score) { this.score = score; return this; }
        public Builder score(int score) { this.score = BigDecimal.valueOf(score); return this; }
        public Builder reasonCodes(List<String> reasonCodes) { this.reasonCodes = reasonCodes != null ? new ArrayList<>(reasonCodes) : new ArrayList<>(); return this; }
        public Builder addReasonCode(String reasonCode) { if (reasonCode != null) this.reasonCodes.add(reasonCode); return this; }
        public Builder explanation(String explanation) { this.explanation = explanation; return this; }
        public Builder evidenceReferences(List<EvidenceReference> refs) { this.evidenceReferences = refs != null ? new ArrayList<>(refs) : new ArrayList<>(); return this; }
        public Builder addEvidenceReference(EvidenceReference ref) { if (ref != null) this.evidenceReferences.add(ref); return this; }
        public Builder ruleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; return this; }
        public Builder policyVersionId(UUID policyVersionId) { this.policyVersionId = policyVersionId; return this; }
        public Builder policyContentHash(String policyContentHash) { this.policyContentHash = policyContentHash; return this; }
        public Builder sourceAssessmentId(UUID sourceAssessmentId) { this.sourceAssessmentId = sourceAssessmentId; return this; }
        public Builder evaluationId(UUID evaluationId) { this.evaluationId = evaluationId; return this; }
        public Builder correlationId(UUID correlationId) { this.correlationId = correlationId; return this; }
        public Builder inputFingerprint(String inputFingerprint) { this.inputFingerprint = inputFingerprint; return this; }
        public Builder inputRevision(String inputRevision) { this.inputRevision = inputRevision; return this; }
        public Builder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder engineImplementationVersion(String engineImplementationVersion) { this.engineImplementationVersion = engineImplementationVersion; return this; }
        public Builder validUntil(Instant validUntil) { this.validUntil = validUntil; return this; }
        public Builder evaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; return this; }

        public RecordEngineDecisionCommand build() {
            return new RecordEngineDecisionCommand(
                    organizationId, engineType, subjectType, subjectId, decision,
                    evaluationOutcome, errorCode, score, reasonCodes, explanation,
                    evidenceReferences, ruleVersion, policyVersionId, policyContentHash,
                    sourceAssessmentId, evaluationId, correlationId, inputFingerprint,
                    inputRevision, schemaVersion, engineImplementationVersion, validUntil, evaluatedAt
            );
        }
    }
}
