package com.finx.engine.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal immutable evaluation context passed to future decision evaluators.
 * Contains evaluation identity and scope only; contains no banking, payment,
 * or mutable domain services.
 */
public record EvaluationContext(
        UUID evaluationId,
        UUID organizationId,
        UUID correlationId,
        String subjectType,
        UUID subjectId,
        Instant evaluationTime
) {
    public EvaluationContext {
        if (evaluationId == null) {
            throw new IllegalArgumentException("evaluationId must not be null");
        }
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId must not be null");
        }
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }
        if (subjectType == null || subjectType.isBlank()) {
            throw new IllegalArgumentException("subjectType must not be null or blank");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId must not be null");
        }
        if (evaluationTime == null) {
            throw new IllegalArgumentException("evaluationTime must not be null");
        }
    }

    public static EvaluationContext create(
            UUID organizationId,
            String subjectType,
            UUID subjectId,
            UUID correlationId
    ) {
        return new EvaluationContext(
                UUID.randomUUID(),
                organizationId,
                correlationId != null ? correlationId : UUID.randomUUID(),
                subjectType,
                subjectId,
                Instant.now()
        );
    }
}
