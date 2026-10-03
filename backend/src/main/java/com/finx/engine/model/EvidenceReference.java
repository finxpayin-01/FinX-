package com.finx.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured, immutable reference to a persisted source evidence record.
 * References ONLY — does not clone or store bulk source payloads.
 */
public record EvidenceReference(
        @JsonProperty("sourceType") String sourceType,
        @JsonProperty("sourceId") UUID sourceId,
        @JsonProperty("observedAt") Instant observedAt,
        @JsonProperty("fingerprint") String fingerprint,
        @JsonProperty("provenance") String provenance,
        @JsonProperty("revision") String revision
) {
    @JsonCreator
    public EvidenceReference {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be null or blank");
        }
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId must not be null");
        }
    }

    public static EvidenceReference of(String sourceType, UUID sourceId, Instant observedAt) {
        return new EvidenceReference(sourceType, sourceId, observedAt, null, null, null);
    }

    public static EvidenceReference of(String sourceType, UUID sourceId, Instant observedAt, String fingerprint, String provenance, String revision) {
        return new EvidenceReference(sourceType, sourceId, observedAt, fingerprint, provenance, revision);
    }
}
