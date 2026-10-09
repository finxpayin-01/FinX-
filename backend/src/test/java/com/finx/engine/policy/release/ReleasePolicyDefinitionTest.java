package com.finx.engine.policy.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleasePolicyDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("1. Policy metadata matches exact v1.0.0 specification")
    void testPolicyMetadata() {
        assertThat(ReleasePolicyDefinition.POLICY_KEY).isEqualTo("FINX_RELEASE_DETERMINISTIC_BASE");
        assertThat(ReleasePolicyDefinition.POLICY_VERSION).isEqualTo("1.0.0");
        assertThat(ReleasePolicyDefinition.SCHEMA_VERSION).isEqualTo("1.0");
        assertThat(ReleasePolicyDefinition.ENGINE_TYPE).isEqualTo(EngineType.RELEASE);
        assertThat(ReleasePolicyDefinition.SCOPE).isEqualTo(PolicyScope.PLATFORM);
        assertThat(ReleasePolicyDefinition.SUBJECT_TYPE).isEqualTo("MILESTONE");
        assertThat(ReleasePolicyDefinition.OWNER_SCOPE).isEqualTo("BUYER_ORGANIZATION");
        assertThat(ReleasePolicyDefinition.CONSUME_NUMERIC_SCORES).isFalse();
        assertThat(ReleasePolicyDefinition.BANK_EXECUTION).isFalse();
        assertThat(ReleasePolicyDefinition.FINANCIAL_MUTATION).isFalse();
        assertThat(ReleasePolicyDefinition.DESCRIPTION).isNotBlank();
    }

    @Test
    @DisplayName("2. Content hash is valid 64-char lowercase hex, matches canonical JSON")
    void testContentHashVerification() {
        String content = ReleasePolicyDefinition.getCanonicalPolicyContent();
        String expectedHash = ReleasePolicyDefinition.getExpectedContentHash();

        assertThat(expectedHash).isNotNull();
        assertThat(expectedHash).hasSize(64);
        assertThat(expectedHash).matches("^[a-f0-9]{64}$");

        String computed = ReleasePolicyDefinition.computeContentHash(content);
        assertThat(computed).isEqualTo(expectedHash);

        // Byte-for-byte reproducibility
        String content2 = ReleasePolicyDefinition.getCanonicalPolicyContent();
        assertThat(content).isEqualTo(content2);
        assertThat(ReleasePolicyDefinition.computeContentHash(content2)).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("3. Canonical JSON policy structure has exactly 8 required engines and rules R01-R06")
    void testCanonicalPolicyJsonStructure() throws Exception {
        String json = ReleasePolicyDefinition.getCanonicalPolicyContent();
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("policyKey").asText()).isEqualTo("FINX_RELEASE_DETERMINISTIC_BASE");
        assertThat(root.get("version").asText()).isEqualTo("1.0.0");
        assertThat(root.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(root.get("engineType").asText()).isEqualTo("RELEASE");
        assertThat(root.get("scope").asText()).isEqualTo("PLATFORM");
        assertThat(root.get("subjectType").asText()).isEqualTo("MILESTONE");
        assertThat(root.get("owner").asText()).isEqualTo("BUYER_ORGANIZATION");
        assertThat(root.get("consumeNumericScores").asBoolean()).isFalse();
        assertThat(root.get("bankExecution").asBoolean()).isFalse();
        assertThat(root.get("financialMutation").asBoolean()).isFalse();
        assertThat(root.get("precedence").asText()).isEqualTo("BLOCK > REVIEW > ALLOW");

        // Verify required engines (exactly 8)
        JsonNode engines = root.get("requiredEngines");
        assertThat(engines).isNotNull();
        assertThat(engines.isArray()).isTrue();
        assertThat(engines.size()).isEqualTo(8);

        List<String> engineNames = new ArrayList<>();
        engines.forEach(e -> engineNames.add(e.asText()));
        assertThat(engineNames).containsExactly(
                "RISK", "DOCUMENT", "MILESTONE_RULES", "APPROVAL", "TRUST", "FRAUD", "DISPUTE", "RECONCILIATION"
        );

        // Verify rules
        JsonNode rules = root.get("rules");
        assertThat(rules).isNotNull();
        assertThat(rules.isArray()).isTrue();
        assertThat(rules.size()).isEqualTo(6);

        assertThat(rules.get(0).get("ruleId").asText()).isEqualTo("R01");
        assertThat(rules.get(0).get("name").asText()).isEqualTo("ANY_BLOCK");
        assertThat(rules.get(0).get("disposition").asText()).isEqualTo("BLOCK");

        assertThat(rules.get(1).get("ruleId").asText()).isEqualTo("R02");
        assertThat(rules.get(1).get("name").asText()).isEqualTo("NO_BLOCK_AND_ANY_REVIEW");
        assertThat(rules.get(1).get("disposition").asText()).isEqualTo("REVIEW");

        assertThat(rules.get(2).get("ruleId").asText()).isEqualTo("R03");
        assertThat(rules.get(2).get("name").asText()).isEqualTo("ALL_EIGHT_ALLOW");
        assertThat(rules.get(2).get("disposition").asText()).isEqualTo("ALLOW");

        assertThat(rules.get(3).get("ruleId").asText()).isEqualTo("R04");
        assertThat(rules.get(3).get("name").asText()).isEqualTo("MISSING_COMPONENT");
        assertThat(rules.get(3).get("disposition").asText()).isEqualTo("REVIEW");

        assertThat(rules.get(4).get("ruleId").asText()).isEqualTo("R05");
        assertThat(rules.get(4).get("name").asText()).isEqualTo("STALE_COMPONENT");
        assertThat(rules.get(4).get("disposition").asText()).isEqualTo("REVIEW");

        assertThat(rules.get(5).get("ruleId").asText()).isEqualTo("R06");
        assertThat(rules.get(5).get("name").asText()).isEqualTo("COMPONENT_ERROR");
        assertThat(rules.get(5).get("disposition").asText()).isEqualTo("REVIEW");
    }
}
