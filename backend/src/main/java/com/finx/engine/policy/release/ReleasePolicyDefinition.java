package com.finx.engine.policy.release;

import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Authoritative, immutable code definition for the FinX Deterministic Release Policy (v1.0.0).
 * Governs the composite evaluation of all eight prerequisite component engines before milestone release recommendation.
 */
public final class ReleasePolicyDefinition {

    public static final String POLICY_KEY = "FINX_RELEASE_DETERMINISTIC_BASE";
    public static final String POLICY_VERSION = "1.0.0";
    public static final String SCHEMA_VERSION = "1.0";
    public static final EngineType ENGINE_TYPE = EngineType.RELEASE;
    public static final PolicyScope SCOPE = PolicyScope.PLATFORM;
    public static final String DESCRIPTION = "Deterministic read-only FinX release decision policy evaluating all eight prerequisite component engine gates.";

    public static final String SUBJECT_TYPE = "MILESTONE";
    public static final String OWNER_SCOPE = "BUYER_ORGANIZATION";
    public static final boolean CONSUME_NUMERIC_SCORES = false;
    public static final boolean BANK_EXECUTION = false;
    public static final boolean FINANCIAL_MUTATION = false;

    public static final List<EngineType> REQUIRED_ENGINES = List.of(
            EngineType.RISK,
            EngineType.DOCUMENT,
            EngineType.MILESTONE_RULES,
            EngineType.APPROVAL,
            EngineType.TRUST,
            EngineType.FRAUD,
            EngineType.DISPUTE,
            EngineType.RECONCILIATION
    );

    // Reason Codes
    public static final String REASON_ALL_GATES_ALLOW = "RELEASE_ALL_GATES_ALLOW";
    public static final String REASON_COMPONENT_MISSING = "RELEASE_COMPONENT_MISSING";
    public static final String REASON_COMPONENT_STALE = "RELEASE_COMPONENT_STALE";
    public static final String REASON_COMPONENT_ERROR = "RELEASE_COMPONENT_ERROR";
    public static final String REASON_POLICY_UNAVAILABLE = "RELEASE_POLICY_UNAVAILABLE";
    public static final String REASON_POLICY_INACTIVE = "RELEASE_POLICY_INACTIVE";
    public static final String REASON_POLICY_INTEGRITY_FAILURE = "RELEASE_POLICY_INTEGRITY_FAILURE";

    private static final String CANONICAL_POLICY_CONTENT = "{"
            + "\"policyKey\":\"FINX_RELEASE_DETERMINISTIC_BASE\","
            + "\"version\":\"1.0.0\","
            + "\"schemaVersion\":\"1.0\","
            + "\"engineType\":\"RELEASE\","
            + "\"scope\":\"PLATFORM\","
            + "\"subjectType\":\"MILESTONE\","
            + "\"owner\":\"BUYER_ORGANIZATION\","
            + "\"consumeNumericScores\":false,"
            + "\"bankExecution\":false,"
            + "\"financialMutation\":false,"
            + "\"requiredEngines\":["
            + "\"RISK\","
            + "\"DOCUMENT\","
            + "\"MILESTONE_RULES\","
            + "\"APPROVAL\","
            + "\"TRUST\","
            + "\"FRAUD\","
            + "\"DISPUTE\","
            + "\"RECONCILIATION\""
            + "],"
            + "\"precedence\":\"BLOCK > REVIEW > ALLOW\","
            + "\"rules\":["
            + "{\"ruleId\":\"R01\",\"name\":\"ANY_BLOCK\",\"disposition\":\"BLOCK\",\"description\":\"If any required component engine evaluates to BLOCK, Release decision evaluates to BLOCK\"},"
            + "{\"ruleId\":\"R02\",\"name\":\"NO_BLOCK_AND_ANY_REVIEW\",\"disposition\":\"REVIEW\",\"description\":\"If no component engine evaluates to BLOCK, but any evaluates to REVIEW, Release decision evaluates to REVIEW\"},"
            + "{\"ruleId\":\"R03\",\"name\":\"ALL_EIGHT_ALLOW\",\"disposition\":\"ALLOW\",\"description\":\"If all eight required component engines evaluate to ALLOW on fresh evidence, Release decision evaluates to ALLOW\"},"
            + "{\"ruleId\":\"R04\",\"name\":\"MISSING_COMPONENT\",\"disposition\":\"REVIEW\",\"description\":\"If any required component engine decision is missing, Release decision evaluates to REVIEW\"},"
            + "{\"ruleId\":\"R05\",\"name\":\"STALE_COMPONENT\",\"disposition\":\"REVIEW\",\"description\":\"If any component engine decision is stale relative to underlying evidence, Release decision evaluates to REVIEW\"},"
            + "{\"ruleId\":\"R06\",\"name\":\"COMPONENT_ERROR\",\"disposition\":\"REVIEW\",\"description\":\"If any component engine evaluation produced an error outcome, Release decision evaluates to REVIEW\"}"
            + "]"
            + "}";

    private static final String CANONICAL_CONTENT_HASH = computeContentHash(CANONICAL_POLICY_CONTENT);

    private ReleasePolicyDefinition() {
        // Private constructor for static utility class
    }

    public static String getCanonicalPolicyContent() {
        return CANONICAL_POLICY_CONTENT;
    }

    public static String getExpectedContentHash() {
        return CANONICAL_CONTENT_HASH;
    }

    public static String computeContentHash(String content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
