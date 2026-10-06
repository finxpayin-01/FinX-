package com.finx.engine.policy.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FraudPolicyDefinitionTest {

    private static final String V1_0_0_HISTORICAL_HASH = "c27e12a196da104c8ab61cd715dc24e274d4ecf2e6f80781bcc0218353482c9a";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("F1.1-01: Policy metadata matches exact v1.0.1 specification")
    void testPolicyMetadataExact() {
        assertThat(FraudPolicyDefinition.POLICY_KEY).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE");
        assertThat(FraudPolicyDefinition.POLICY_VERSION).isEqualTo("1.0.1");
        assertThat(FraudPolicyDefinition.SCHEMA_VERSION).isEqualTo("1.0");
        assertThat(FraudPolicyDefinition.ENGINE_TYPE).isEqualTo(EngineType.FRAUD);
        assertThat(FraudPolicyDefinition.SCOPE).isEqualTo(PolicyScope.PLATFORM);
        assertThat(FraudPolicyDefinition.SUBJECT_TYPE).isEqualTo("MILESTONE");
        assertThat(FraudPolicyDefinition.OWNER_SCOPE).isEqualTo("BUYER_ORGANIZATION");
        assertThat(FraudPolicyDefinition.CONSUME_DERIVED_RISK_SCORE).isFalse();
        assertThat(FraudPolicyDefinition.DESCRIPTION).isNotBlank();
    }

    @Test
    @DisplayName("F1.1-02: Content hash is valid 64-char lowercase hex, matches canonical JSON, and differs from v1.0.0")
    void testContentHashVerification() {
        String content = FraudPolicyDefinition.getCanonicalPolicyContent();
        String expectedHash = FraudPolicyDefinition.getExpectedContentHash();

        assertThat(expectedHash).isNotNull();
        assertThat(expectedHash).hasSize(64);
        assertThat(expectedHash).matches("^[a-f0-9]{64}$");
        assertThat(expectedHash).isNotEqualTo(V1_0_0_HISTORICAL_HASH);

        String computed = FraudPolicyDefinition.computeContentHash(content);
        assertThat(computed).isEqualTo(expectedHash);

        // Byte-for-byte reproducibility
        String content2 = FraudPolicyDefinition.getCanonicalPolicyContent();
        assertThat(content).isEqualTo(content2);
        assertThat(FraudPolicyDefinition.computeContentHash(content2)).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("F1.1-03: Canonical JSON policy parses correctly and contains corrected S01, S05, S06 source metadata")
    void testCanonicalPolicyContentJsonStructure() throws Exception {
        String json = FraudPolicyDefinition.getCanonicalPolicyContent();
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("policyKey").asText()).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE");
        assertThat(root.get("version").asText()).isEqualTo("1.0.1");
        assertThat(root.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(root.get("engineType").asText()).isEqualTo("FRAUD");
        assertThat(root.get("scope").asText()).isEqualTo("PLATFORM");
        assertThat(root.get("subjectType").asText()).isEqualTo("MILESTONE");
        assertThat(root.get("owner").asText()).isEqualTo("BUYER_ORGANIZATION");
        assertThat(root.get("consumeDerivedRiskScore").asBoolean()).isFalse();

        // Must not contain nonexistent invoice_documents.content_hash
        assertThat(json).doesNotContain("invoice_documents.content_hash");

        JsonNode rules = root.get("rules");
        assertThat(rules.isArray()).isTrue();
        assertThat(rules.size()).isEqualTo(7);

        // S01: Exact Document Hash Duplicate (Corrected source: invoices.document_hash)
        JsonNode s01 = findRule(rules, "S01");
        assertThat(s01.get("name").asText()).isEqualTo("EXACT_DOCUMENT_HASH_DUPLICATE");
        assertThat(s01.get("reasonCode").asText()).isEqualTo("FRAUD_DUPLICATE_DOCUMENT_HASH");
        assertThat(s01.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s01.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s01.get("enabled").asBoolean()).isTrue();
        assertThat(s01.get("source").asText()).isEqualTo("invoices.document_hash");

        // S02: Duplicate Invoice Number
        JsonNode s02 = findRule(rules, "S02");
        assertThat(s02.get("name").asText()).isEqualTo("DUPLICATE_INVOICE_NUMBER");
        assertThat(s02.get("reasonCode").asText()).isEqualTo("FRAUD_DUPLICATE_INVOICE_NUMBER");
        assertThat(s02.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s02.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s02.get("enabled").asBoolean()).isTrue();
        assertThat(s02.get("source").asText()).contains("invoices.vendor_organization_id");

        // S03: Tax Identity Mismatch
        JsonNode s03 = findRule(rules, "S03");
        assertThat(s03.get("name").asText()).isEqualTo("VENDOR_TAX_IDENTITY_MISMATCH");
        assertThat(s03.get("reasonCode").asText()).isEqualTo("FRAUD_TAX_IDENTITY_MISMATCH");
        assertThat(s03.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s03.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s03.get("enabled").asBoolean()).isTrue();
        assertThat(s03.get("source").asText()).isEqualTo("invoice_checks.status");

        // S04: Beneficiary Name Mismatch
        JsonNode s04 = findRule(rules, "S04");
        assertThat(s04.get("name").asText()).isEqualTo("BENEFICIARY_NAME_MISMATCH");
        assertThat(s04.get("reasonCode").asText()).isEqualTo("FRAUD_BENEFICIARY_NAME_MISMATCH");
        assertThat(s04.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s04.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s04.get("enabled").asBoolean()).isTrue();
        assertThat(s04.get("source").asText()).isEqualTo("organization_bank_accounts.name_match_status");

        // S05: Provider Credit Identity Reuse (Truthful evaluability & scoped namespaces)
        JsonNode s05 = findRule(rules, "S05");
        assertThat(s05.get("name").asText()).isEqualTo("PROVIDER_CREDIT_IDENTITY_REUSE");
        assertThat(s05.get("reasonCode").asText()).isEqualTo("FRAUD_PROVIDER_REFERENCE_REUSE");
        assertThat(s05.get("disposition").asText()).isEqualTo("BLOCK");
        assertThat(s05.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s05.get("enabled").asBoolean()).isTrue();
        assertThat(s05.get("evaluableFromCurrentReadModel").asBoolean()).isFalse();
        assertThat(s05.get("enforcedAtPersistenceBoundary").asBoolean()).isTrue();
        assertThat(s05.get("source").asText()).contains("payment_transactions(provider,utr_number)");
        assertThat(s05.get("source").asText()).contains("settlement_instructions(provider,utr_number)");

        // S06: Recent Beneficiary Account Change (Product Policy Required - No Invented Threshold)
        JsonNode s06 = findRule(rules, "S06");
        assertThat(s06.get("name").asText()).isEqualTo("BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED");
        assertThat(s06.get("reasonCode").asText()).isEqualTo("FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED");
        assertThat(s06.get("disposition").asText()).isEqualTo("REVIEW");
        assertThat(s06.get("thresholdRequired").asBoolean()).isTrue();
        assertThat(s06.get("currentThreshold").asText()).isEqualTo("UNDEFINED");
        assertThat(s06.get("policyState").asText()).isEqualTo("PRODUCT_POLICY_REQUIRED");
        assertThat(s06.get("enabled").asBoolean()).isFalse();
        assertThat(s06.get("source").asText()).contains("organization_bank_accounts.created_at");
        assertThat(s06.get("source").asText()).contains("organization_bank_accounts.updated_at");

        // S07: Invoice Date Anomaly
        JsonNode s07 = findRule(rules, "S07");
        assertThat(s07.get("name").asText()).isEqualTo("INVOICE_DATE_ANOMALY");
        assertThat(s07.get("reasonCode").asText()).isEqualTo("FRAUD_INVOICE_DATE_ANOMALY");
        assertThat(s07.get("disposition").asText()).isEqualTo("REVIEW");
        assertThat(s07.get("thresholdRequired").asBoolean()).isFalse();
        assertThat(s07.get("enabled").asBoolean()).isTrue();
        assertThat(s07.get("source").asText()).isEqualTo("invoice_checks.status");

        // Missing Evidence Policies
        JsonNode missingPolicies = root.get("missingEvidencePolicies");
        assertThat(missingPolicies).isNotNull();
        assertThat(missingPolicies.get("missingInvoice").get("reasonCode").asText()).isEqualTo("FRAUD_MISSING_INVOICE_EVIDENCE");
        assertThat(missingPolicies.get("missingInvoice").get("disposition").asText()).isEqualTo("REVIEW");
        assertThat(missingPolicies.get("missingBeneficiary").get("reasonCode").asText()).isEqualTo("FRAUD_BENEFICIARY_UNASSIGNED");
        assertThat(missingPolicies.get("missingBeneficiary").get("disposition").asText()).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("F1.1-04: Policy content contains zero sensitive data or credentials")
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
