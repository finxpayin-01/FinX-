package com.finx.engine.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility to calculate deterministic SHA-256 fingerprint for engine decisions.
 */
public class EngineDecisionFingerprint {

    public static String calculate(
            UUID organizationId,
            EngineType engineType,
            String subjectType,
            UUID subjectId,
            DecisionStatus decision,
            EvaluationOutcome evaluationOutcome,
            String errorCode,
            BigDecimal score,
            String ruleVersion,
            String policyContentHash,
            String inputFingerprint,
            List<String> reasonCodes,
            List<EvidenceReference> evidenceReferences
    ) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(organizationId != null ? organizationId.toString() : "").append("|");
            sb.append(engineType != null ? engineType.name() : "").append("|");
            sb.append(subjectType != null ? subjectType : "").append("|");
            sb.append(subjectId != null ? subjectId.toString() : "").append("|");
            sb.append(decision != null ? decision.name() : "").append("|");
            sb.append(evaluationOutcome != null ? evaluationOutcome.name() : "SUCCESS").append("|");
            sb.append(errorCode != null ? errorCode : "").append("|");
            sb.append(score != null ? score.stripTrailingZeros().toPlainString() : "").append("|");
            sb.append(ruleVersion != null ? ruleVersion : "").append("|");
            sb.append(policyContentHash != null ? policyContentHash : "").append("|");
            sb.append(inputFingerprint != null ? inputFingerprint : "").append("|");

            if (reasonCodes != null) {
                sb.append(reasonCodes.stream().sorted().collect(Collectors.joining(",")));
            }
            sb.append("|");

            if (evidenceReferences != null) {
                sb.append(evidenceReferences.stream()
                        .map(ref -> ref.sourceType() + ":" + ref.sourceId() + ":" + (ref.fingerprint() != null ? ref.fingerprint() : ""))
                        .sorted()
                        .collect(Collectors.joining(";")));
            }

            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String compute(
            String organizationId,
            String engineType,
            String subjectType,
            String subjectId,
            String decision,
            String inputFingerprint,
            String ruleVersion,
            String policyContentHash,
            List<String> reasonCodes,
            List<EvidenceReference> evidenceReferences
    ) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(organizationId != null ? organizationId : "").append("|");
            sb.append(engineType != null ? engineType : "").append("|");
            sb.append(subjectType != null ? subjectType : "").append("|");
            sb.append(subjectId != null ? subjectId : "").append("|");
            sb.append(decision != null ? decision : "").append("|");
            sb.append(inputFingerprint != null ? inputFingerprint : "").append("|");
            sb.append(ruleVersion != null ? ruleVersion : "").append("|");
            sb.append(policyContentHash != null ? policyContentHash : "").append("|");

            if (reasonCodes != null) {
                sb.append(reasonCodes.stream().sorted().collect(Collectors.joining(",")));
            }
            sb.append("|");

            if (evidenceReferences != null) {
                sb.append(evidenceReferences.stream()
                        .map(ref -> ref.sourceType() + ":" + ref.sourceId() + ":" + (ref.fingerprint() != null ? ref.fingerprint() : ""))
                        .sorted()
                        .collect(Collectors.joining(";")));
            }

            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
