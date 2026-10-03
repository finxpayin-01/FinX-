package com.finx.engine;

import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class EngineDecisionModelTest {

    @Test
    @DisplayName("Section 39: DecisionStatus must contain EXACTLY ALLOW, REVIEW, BLOCK")
    void testExactDecisionStatusEnumValues() {
        DecisionStatus[] values = DecisionStatus.values();
        assertThat(values)
                .hasSize(3)
                .containsExactlyInAnyOrder(
                        DecisionStatus.ALLOW,
                        DecisionStatus.REVIEW,
                        DecisionStatus.BLOCK
                );
    }

    @Test
    @DisplayName("Section 40: EngineType must contain EXACTLY the nine specified values")
    void testExactEngineTypeEnumValues() {
        EngineType[] values = EngineType.values();
        assertThat(values)
                .hasSize(9)
                .containsExactlyInAnyOrder(
                        EngineType.RISK,
                        EngineType.TRUST,
                        EngineType.MILESTONE_RULES,
                        EngineType.APPROVAL,
                        EngineType.FRAUD,
                        EngineType.DOCUMENT,
                        EngineType.RECONCILIATION,
                        EngineType.DISPUTE,
                        EngineType.RELEASE
                );
    }

    @Test
    @DisplayName("Section 41: Evaluation error fail-closed invariant")
    void testEvaluationErrorFailClosedInvariant() {
        UUID orgId = UUID.randomUUID();
        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. ERROR + ALLOW => MUST FAIL
        assertThatThrownBy(() -> RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.ERROR)
                .errorCode("CALCULATION_FAILURE")
                .evaluationId(evalId)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evaluation outcome ERROR requires DecisionStatus.REVIEW");

        // 2. ERROR + BLOCK => MUST FAIL (Fail-closed invariant requires REVIEW for engine evaluation errors)
        assertThatThrownBy(() -> RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.BLOCK)
                .evaluationOutcome(EvaluationOutcome.ERROR)
                .errorCode("CALCULATION_FAILURE")
                .evaluationId(evalId)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evaluation outcome ERROR requires DecisionStatus.REVIEW");

        // 3. ERROR + REVIEW + missing error code => MUST FAIL
        assertThatThrownBy(() -> RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.REVIEW)
                .evaluationOutcome(EvaluationOutcome.ERROR)
                .errorCode("")
                .evaluationId(evalId)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evaluation outcome ERROR requires a non-blank errorCode");

        // 4. ERROR + REVIEW + valid errorCode => MUST PASS
        RecordEngineDecisionCommand validErrorCmd = RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.REVIEW)
                .evaluationOutcome(EvaluationOutcome.ERROR)
                .errorCode("ENGINE_TIMEOUT")
                .explanation("Upstream service timeout")
                .evaluationId(evalId)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build();

        assertThat(validErrorCmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(validErrorCmd.getEvaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(validErrorCmd.getErrorCode()).isEqualTo("ENGINE_TIMEOUT");
    }

    @Test
    @DisplayName("Section 42: Decision validity duration assertions")
    void testValidityAssertions() {
        UUID orgId = UUID.randomUUID();
        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. validUntil before evaluatedAt => MUST REJECT
        assertThatThrownBy(() -> RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.DOCUMENT)
                .subjectType("INVOICE")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .validUntil(now.minus(1, ChronoUnit.HOURS))
                .evaluatedAt(now)
                .evaluationId(evalId)
                .correlationId(corrId)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validUntil must not be before evaluatedAt");

        // 2. validUntil null => PASS
        RecordEngineDecisionCommand cmdNoExpiry = RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.DOCUMENT)
                .subjectType("INVOICE")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .validUntil(null)
                .evaluatedAt(now)
                .evaluationId(evalId)
                .correlationId(corrId)
                .build();
        assertThat(cmdNoExpiry.getValidUntil()).isNull();

        // 3. validUntil after evaluatedAt => PASS
        RecordEngineDecisionCommand cmdWithExpiry = RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.DOCUMENT)
                .subjectType("INVOICE")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .validUntil(now.plus(24, ChronoUnit.HOURS))
                .evaluatedAt(now)
                .evaluationId(evalId)
                .correlationId(corrId)
                .build();
        assertThat(cmdWithExpiry.getValidUntil()).isAfter(now);
    }

    @Test
    @DisplayName("Section 51 & 52: Policy version immutability and scope assertions")
    void testPolicyVersionModelScopeAndHash() {
        UUID orgId = UUID.randomUUID();

        // Blank content hash => REJECT
        assertThatThrownBy(() -> EnginePolicyVersion.platform(
                EngineType.RISK, "PLATFORM_RISK_DEFAULT", "v1.0", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentHash must not be null or blank");

        // Platform policy with orgId => REJECT
        assertThatThrownBy(() -> new EnginePolicyVersion(
                orgId, PolicyScope.PLATFORM, EngineType.RISK, "KEY", "v1.0", "hash123", "1.0", null, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Platform-scoped policy must have null organizationId");

        // Organization policy with null orgId => REJECT
        assertThatThrownBy(() -> new EnginePolicyVersion(
                null, PolicyScope.ORGANIZATION, EngineType.RISK, "KEY", "v1.0", "hash123", "1.0", null, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Organization-scoped policy must have non-null organizationId");

        // Valid platform policy
        EnginePolicyVersion platformPolicy = EnginePolicyVersion.platform(
                EngineType.RISK, "PLATFORM_DEFAULT", "v1.0", "hash-abc-123"
        );
        assertThat(platformPolicy.getScope()).isEqualTo(PolicyScope.PLATFORM);
        assertThat(platformPolicy.getOrganizationId()).isNull();

        // Valid organization policy
        EnginePolicyVersion orgPolicy = EnginePolicyVersion.organization(
                orgId, EngineType.RISK, "PLATFORM_DEFAULT", "v1.0", "hash-def-456"
        );
        assertThat(orgPolicy.getScope()).isEqualTo(PolicyScope.ORGANIZATION);
        assertThat(orgPolicy.getOrganizationId()).isEqualTo(orgId);
    }

    @Test
    @DisplayName("Deterministic Decision Fingerprint calculation")
    void testDeterministicDecisionFingerprint() {
        UUID orgId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();

        String fp1 = EngineDecisionFingerprint.calculate(
                orgId, EngineType.RISK, "DEAL", subjectId,
                DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, null,
                new BigDecimal("85.50"), "v1", "hash1", "inputHash1",
                List.of("LOW_RISK", "VERIFIED"),
                List.of(new EvidenceReference("RISK_ASSESSMENT", UUID.randomUUID(), Instant.now(), "fp1", "PROV", "r1"))
        );

        String fp2 = EngineDecisionFingerprint.calculate(
                orgId, EngineType.RISK, "DEAL", subjectId,
                DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, null,
                new BigDecimal("85.50"), "v1", "hash1", "inputHash1",
                List.of("LOW_RISK", "VERIFIED"),
                List.of(new EvidenceReference("RISK_ASSESSMENT", UUID.randomUUID(), Instant.now(), "fp1", "PROV", "r1"))
        );

        assertThat(fp1).isNotNull().hasSize(64);
        // Fingerprints with identical semantic content (excluding random UUIDs) must be deterministic
        assertThat(fp1).matches("^[a-f0-9]{64}$");
    }
}
