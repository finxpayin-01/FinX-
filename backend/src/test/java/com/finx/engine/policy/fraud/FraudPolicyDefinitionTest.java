package com.finx.engine.policy.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FraudPolicyDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("F1-01: Policy metadata matches exact specification")
    void testPolicyMetadataExact() {
        assertThat(FraudPolicyDefinition.POLICY_KEY).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE");
        assertThat(FraudPolicyDefinition.POLICY_VERSION).isEqualTo("1.0.0");
        assertThat(FraudPolicyDefinition.SCHEMA_VERSION).isEqualTo("1.0");
        assertThat(FraudPolicyDefinition.ENGINE_TYPE).isEqualTo(EngineType.FRAUD);
        assertThat(FraudPolicyDefinition.SCOPE).isEqualTo(PolicyScope.PLATFORM);
        assertThat(FraudPolicyDefinition.SUBJECT_TYPE).isEqualTo("MILESTONE");
        assertThat(FraudPolicyDefinition.OWNER_SCOPE).isEqualTo("BUYER_ORGANIZATION");
        assertThat(FraudPolicyDefinition.CONSUME_DERIVED_RISK_SCORE).isFalse();
        assertThat(FraudPolicyDefinition.DESCRIPTION).isNotBlank();
    }

    @Test
    @DisplayName("F1-02: Content hash is valid 64-char lowercase hex and matches canonical JSON content")
    void testContentHashVerification() {
        String content = FraudPolicyDefinition.getCanonicalPolicyContent();
        String expectedHash = FraudPolicyDefinition.getExpectedContentHash();

        assertThat(expectedHash).isNotNull();
        assertThat(expectedHash).hasSize(64);
        assertThat(expectedHash).matches("^[a-f0-9]{64}$");

        String computed = FraudPolicyDefinition.computeContentHash(content);
        assertThat(computed).isEqualTo(expectedHash);

        // Byte-for-byte reproducibility
        String content2 = FraudPolicyDefinition.getCanonicalPolicyContent();
        assertThat(content).isEqualTo(content2);
        assertThat(FraudPolicyDefinition.computeContentHash(content2)).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("F1-03: Canonical JSON policy parses correctly and contains all required signal definitions")
    void testCanonicalPolicyContentJsonStructure() throws Exception {
        String json = FraudPolicyDefinition.getCanonicalPolicyContent();
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("policyKey").asText()).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE");
        assertThat(root.get("version").asText()).isEqualTo("1.0.0");
        assertThat(root.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(root.get("engineType").asText()).isEqualTo("FRAUD");
        assertThat(root.get("scope").asText()).isEqualTo("PLATFORM");
        assertThat(root.get("subjectType").asText()).isEqualTo("MILESTONE");
        assertThat(root.get("owner").asText()).isEqualTo("BUYER_ORGANIZATION");
        assertThat(root.get("consumeDerivedRiskScore").asBoolean()).isFalse();

        JsonNode rules = root.get("rules");
        assertThat(rules.isArray()).isTrue();
        assertThat(rules.size()).isEqualTo(7);

        // S01: Exact Document Hash Duplicate
        JsonNode s01 = findRule(rules, "S01");
        assertThat(s01.get("name").asText()).isEqualTo("EXACT_DOCUMENT_HASH_DUPLICATE");
        assertThat(s01.get("reasonCode").asText()).isEqualTo("FRAUD_DUPLICATE_DOCUMENT_HASH");
        assertThat(s01.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s01.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s01.get("enabled").asBoolean()).isTrue();

        // S02: Duplicate Invoice Number
        JsonNode s02 = findRule(rules, "S02");
        assertThat(s02.get("name").asText()).isEqualTo("DUPLICATE_INVOICE_NUMBER");
        assertThat(s02.get("reasonCode").asText()).isEqualTo("FRAUD_DUPLICATE_INVOICE_NUMBER");
        assertThat(s02.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s02.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s02.get("enabled").asBoolean()).isTrue();

        // S03: Tax Identity Mismatch
        JsonNode s03 = findRule(rules, "S03");
        assertThat(s03.get("name").asText()).isEqualTo("VENDOR_TAX_IDENTITY_MISMATCH");
        assertThat(s03.get("reasonCode").asText()).isEqualTo("FRAUD_TAX_IDENTITY_MISMATCH");
        assertThat(s03.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s03.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s03.get("enabled").asBoolean()).isTrue();

        // S04: Beneficiary Name Mismatch
        JsonNode s04 = findRule(rules, "S04");
        assertThat(s04.get("name").asText()).isEqualTo("BENEFICIARY_NAME_MISMATCH");
        assertThat(s04.get("reasonCode").asText()).isEqualTo("FRAUD_BENEFICIARY_NAME_MISMATCH");
        assertThat(s04.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s04.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s04.get("enabled").asBoolean()).isTrue();

        // S05: Provider Credit Identity Reuse (Truthful evaluability metadata)
        JsonNode s05 = findRule(rules, "S05");
        assertThat(s05.get("name").asText()).isEqualTo("PROVIDER_CREDIT_IDENTITY_REUSE");
        assertThat(s05.get("reasonCode").asText()).isEqualTo("FRAUD_PROVIDER_REFERENCE_REUSE");
        assertThat(s05.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s05.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s05.get("enabled").asBoolean()).isTrue();
        assertThat(s05.get("evaluableFromCurrentReadModel").asBoolean()).isFalse();
        assertThat(s05.get("enforcedAtPersistenceBoundary").asBoolean()).isTrue();

        // S06: Recent Beneficiary Account Change (Product Policy Required - No Invented Threshold)
        JsonNode s06 = findRule(rules, "S06");
        assertThat(s06.get("name").asText()).isEqualTo("BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED");
        assertThat(s06.get("reasonCode").asText()).isEqualTo("FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED");
        assertThat(s06.get("disposition").asText()).isEqualTo("REVIEW");
        assertThat(s06.get("thresholdRequired").asBoolean()).isTrue();
        assertThat(s06.get("currentThreshold").asText()).isEqualTo("UNDEFINED");
        assertThat(s06.get("policyState").asText()).isEqualTo("PRODUCT_POLICY_REQUIRED");
        assertThat(s06.get("enabled").asBoolean()).isFalse();

        // S07: Invoice Date Anomaly
        JsonNode s07 = findRule(rules, "S07");
        assertThat(s07.get("name").asText()).isEqualTo("INVOICE_DATE_ANOMALY");
        assertThat(s07.get("reasonCode").asText()).isEqualTo("FRAUD_INVOICE_DATE_ANOMALY");
        assertThat(s07.get("disposition").asText()).isEqualTo("REVIEW");
        assertThat(s07.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s07.get("enabled").asBoolean()).isTrue();

        // Missing Evidence Policies
        JsonNode missingPolicies = root.get("missingEvidencePolicies");
        assertThat(missingPolicies).isNotNull();
        assertThat(missingPolicies.get("missingInvoice").get("reasonCode").asText()).isEqualTo("FRAUD_MISSING_INVOICE_EVIDENCE");
        assertThat(missingPolicies.get("missingInvoice").get("disposition").asText()).isEqualTo("REVIEW");
        assertThat(missingPolicies.get("missingBeneficiary").get("reasonCode").asText()).isEqualTo("FRAUD_BENEFICIARY_UNASSIGNED");
        assertThat(missingPolicies.get("missingBeneficiary").get("disposition").asText()).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("F1-04: Policy content contains zero sensitive data or credentials")
    void testZeroSensitiveDataInPolicyContent() {
        String content = FraudPolicyDefinition.getCanonicalPolicyContent();
        assertThat(content).doesNotContain("password");
        assertThat(content).doesNotContain("secret");
        assertThat(content).doesNotContain("Bearer");
        assertThat(content).doesNotContain("apiKey");
        assertThat(content).doesNotContain("accountNumber");
        assertThat(content).doesNotContain("ifsc");
        assertThat(content).doesNotContain("gstin");
        assertThat(content).doesNotContain("panNumber");
    }

    private JsonNode findRule(JsonNode rules, String ruleId) {
        for (JsonNode rule : rules) {
            if (ruleId.equals(rule.get("ruleId").asText())) {
                return rule;
            }
        }
        throw new AssertionError("Rule " + ruleId + " not found in policy JSON");
    }
}
