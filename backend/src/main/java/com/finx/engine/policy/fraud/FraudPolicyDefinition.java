package com.finx.engine.policy.fraud;

import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Authoritative, immutable code definition for the FinX Deterministic Fraud Policy (v1.0.1).
 * Corrects S01 source to invoices.document_hash, specifies scoped provider identity for S05,
 * and clarifies timestamp sources for S06 while preserving immutability and fail-closed security.
 */
public final class FraudPolicyDefinition {

    public static final String POLICY_KEY = "FINX_FRAUD_DETERMINISTIC_BASE";
    public static final String POLICY_VERSION = "1.0.1";
    public static final String SCHEMA_VERSION = "1.0";
    public static final EngineType ENGINE_TYPE = EngineType.FRAUD;
    public static final PolicyScope SCOPE = PolicyScope.PLATFORM;
    public static final String DESCRIPTION = "Deterministic read-only FinX fraud decision policy evaluated against canonical persisted evidence.";

    public static final String SUBJECT_TYPE = "MILESTONE";
    public static final String OWNER_SCOPE = "BUYER_ORGANIZATION";
    public static final boolean CONSUME_DERIVED_RISK_SCORE = false;

    // Reason Codes
    public static final String REASON_DUPLICATE_DOCUMENT_HASH = "FRAUD_DUPLICATE_DOCUMENT_HASH";
    public static final String REASON_DUPLICATE_INVOICE_NUMBER = "FRAUD_DUPLICATE_INVOICE_NUMBER";
    public static final String REASON_TAX_IDENTITY_MISMATCH = "FRAUD_TAX_IDENTITY_MISMATCH";
    public static final String REASON_BENEFICIARY_NAME_MISMATCH = "FRAUD_BENEFICIARY_NAME_MISMATCH";
    public static final String REASON_PROVIDER_REFERENCE_REUSE = "FRAUD_PROVIDER_REFERENCE_REUSE";
    public static final String REASON_BENEFICIARY_RECENTLY_MODIFIED = "FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED";
    public static final String REASON_INVOICE_DATE_ANOMALY = "FRAUD_INVOICE_DATE_ANOMALY";
    public static final String REASON_MISSING_INVOICE_EVIDENCE = "FRAUD_MISSING_INVOICE_EVIDENCE";
    public static final String REASON_BENEFICIARY_UNASSIGNED = "FRAUD_BENEFICIARY_UNASSIGNED";
    public static final String REASON_EVALUATION_PASSED = "FRAUD_EVALUATION_PASSED";

    private static final String CANONICAL_POLICY_CONTENT = "{"
            + "\"policyKey\":\"FINX_FRAUD_DETERMINISTIC_BASE\","
            + "\"version\":\"1.0.1\","
            + "\"schemaVersion\":\"1.0\","
            + "\"engineType\":\"FRAUD\","
            + "\"scope\":\"PLATFORM\","
            + "\"subjectType\":\"MILESTONE\","
            + "\"owner\":\"BUYER_ORGANIZATION\","
            + "\"consumeDerivedRiskScore\":false,"
            + "\"rules\":["
            + "{\"ruleId\":\"S01\",\"name\":\"EXACT_DOCUMENT_HASH_DUPLICATE\",\"reasonCode\":\"FRAUD_DUPLICATE_DOCUMENT_HASH\",\"disposition\":\"BLOCK\",\"thresholdRequired\":false,\"enabled\":true,\"source\":\"invoices.document_hash\",\"description\":\"Cryptographic SHA-256 document hash collision with a previously submitted invoice in invoices table\"},"
            + "{\"ruleId\":\"S02\",\"name\":\"DUPLICATE_INVOICE_NUMBER\",\"reasonCode\":\"FRAUD_DUPLICATE_INVOICE_NUMBER\",\"disposition\":\"BLOCK\",\"thresholdRequired\":false,\"enabled\":true,\"source\":\"invoices.vendor_organization_id,invoices.normalized_invoice_number\",\"description\":\"Normalized invoice number collision for the same vendor organization on non-revision invoices\"},"
            + "{\"ruleId\":\"S03\",\"name\":\"VENDOR_TAX_IDENTITY_MISMATCH\",\"reasonCode\":\"FRAUD_TAX_IDENTITY_MISMATCH\",\"disposition\":\"BLOCK\",\"thresholdRequired\":false,\"enabled\":true,\"source\":\"invoice_checks.status\",\"description\":\"Tax identifier PAN or GSTIN mismatch between billed entity and verified KYB registration from persisted invoice checks\"},"
            + "{\"ruleId\":\"S04\",\"name\":\"BENEFICIARY_NAME_MISMATCH\",\"reasonCode\":\"FRAUD_BENEFICIARY_NAME_MISMATCH\",\"disposition\":\"BLOCK\",\"thresholdRequired\":false,\"enabled\":true,\"source\":\"organization_bank_accounts.name_match_status\",\"description\":\"Payout destination bank account verification reported explicit name mismatch\"},"
            + "{\"ruleId\":\"S05\",\"name\":\"PROVIDER_CREDIT_IDENTITY_REUSE\",\"reasonCode\":\"FRAUD_PROVIDER_REFERENCE_REUSE\",\"disposition\":\"BLOCK\",\"thresholdRequired\":false,\"enabled\":true,\"evaluableFromCurrentReadModel\":false,\"enforcedAtPersistenceBoundary\":true,\"source\":\"payment_transactions(provider,utr_number),settlement_instructions(provider,utr_number)\",\"description\":\"External provider UTR or transaction reference collision; enforced by B05 database unique constraints uk_payment_tx_provider_utr and uk_settlement_provider_utr across payment and settlement namespaces\"},"
            + "{\"ruleId\":\"S06\",\"name\":\"BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED\",\"reasonCode\":\"FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED\",\"disposition\":\"REVIEW\",\"thresholdRequired\":true,\"currentThreshold\":\"UNDEFINED\",\"policyState\":\"PRODUCT_POLICY_REQUIRED\",\"enabled\":false,\"source\":\"organization_bank_accounts.created_at,organization_bank_accounts.updated_at\",\"description\":\"Payout bank account was registered or modified within policy observation window; disabled pending product policy threshold definition\"},"
            + "{\"ruleId\":\"S07\",\"name\":\"INVOICE_DATE_ANOMALY\",\"reasonCode\":\"FRAUD_INVOICE_DATE_ANOMALY\",\"disposition\":\"REVIEW\",\"thresholdRequired\":false,\"enabled\":true,\"source\":\"invoice_checks.status\",\"description\":\"Invoice date anomalies detected by canonical invoice matching checks of type INVOICE_DATE_VALID\"}"
            + "],"
            + "\"missingEvidencePolicies\":{"
            + "\"missingInvoice\":{\"reasonCode\":\"FRAUD_MISSING_INVOICE_EVIDENCE\",\"disposition\":\"REVIEW\"},"
            + "\"missingBeneficiary\":{\"reasonCode\":\"FRAUD_BENEFICIARY_UNASSIGNED\",\"disposition\":\"REVIEW\"}"
            + "}"
            + "}";

    private static final String CANONICAL_CONTENT_HASH = computeContentHash(CANONICAL_POLICY_CONTENT);

    private FraudPolicyDefinition() {
        // Private constructor for static utility/definition class
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
