package com.finx.engine.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finx.engine.model.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Canonical scalar inputs and durable references, never entity serialization. */
final class DecisionEvidence {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<List<Object>> material = new ArrayList<>();
    private final List<EvidenceReference> references = new ArrayList<>();

    void input(Object... values) {
        List<Object> row = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !(value instanceof String || value instanceof UUID || value instanceof Instant
                    || value instanceof Enum<?> || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("Only explicit scalar evidence is supported");
            }
            row.add(value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : value);
        }
        material.add(row);
    }

    void reference(String type, UUID id, Instant observedAt, String hash, String provenance, String revision) {
        references.add(new EvidenceReference(type, id, observedAt, hash, provenance, revision));
    }

    RecordEngineDecisionCommand command(EvaluationContext context, EngineType type, DecisionStatus status,
                                        List<String> reasons, String explanation) {
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(material)));
            DecisionSubjectType subjectType;
            try {
                subjectType = DecisionSubjectType.valueOf(context.subjectType());
            } catch (IllegalArgumentException | NullPointerException ignored) {
                subjectType = DecisionSubjectType.MILESTONE;
            }
            return RecordEngineDecisionCommand.builder()
                    .organizationId(context.organizationId()).engineType(type)
                    .subjectType(subjectType).subjectId(context.subjectId())
                    .evaluationId(context.evaluationId()).correlationId(context.correlationId())
                    .evaluatedAt(context.evaluationTime()).decision(status).reasonCodes(reasons)
                    .explanation(explanation).inputFingerprint(fingerprint).ruleVersion(type.name() + "_V1")
                    .evidenceReferences(references.stream().distinct()
                            .sorted(Comparator.comparing(EvidenceReference::sourceType)
                                .thenComparing(EvidenceReference::sourceId)).toList()).build();
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot fingerprint decision evidence", ex);
        }
    }
}
