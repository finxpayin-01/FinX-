package com.finx.engine.adapter;

import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import com.finx.engine.policy.fraud.FraudPolicyDefinition;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceCheck;
import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.InvoiceCheckType;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Pure read-only deterministic decision adapter for the FRAUD engine.
 * <p>
 * Evaluates canonical persisted evidence against immutable Platform Fraud Policy v1.0.1:
 * - S01: EXACT_DOCUMENT_HASH_DUPLICATE (BLOCK)
 * - S02: DUPLICATE_INVOICE_NUMBER (BLOCK, excluding legitimate revision lineage)
 * - S03: VENDOR_TAX_IDENTITY_MISMATCH (BLOCK from persisted PAN/GSTIN checks)
 * - S04: BENEFICIARY_NAME_MISMATCH (BLOCK on explicit mismatch; REVIEW on unassigned/ambiguous)
 * - S05: PROVIDER_CREDIT_IDENTITY_REUSE (Not evaluable from current read model; B05 persistence boundary only)
 * - S06: BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED (Disabled in v1.0.1; threshold undefined)
 * - S07: INVOICE_DATE_ANOMALY (REVIEW from persisted invoice date check)
 * - Missing evidence policies:
 *   - Missing required invoice / empty checks / corrupt lineage -> REVIEW (FRAUD_MISSING_INVOICE_EVIDENCE)
 *   - Unassigned or ambiguous beneficiary -> REVIEW (FRAUD_BENEFICIARY_UNASSIGNED)
 * <p>
 * Guarantees:
 * - Zero writes or mutations to underlying domain entities
 * - Evaluation owner strictly bound to buyer organization scope
 * - Subject is MILESTONE with subjectId = milestone.id
 * - Zero numeric fraud score (score is null)
 * - Strictly zero banking, ledger, escrow, settlement, or payment side effects
 * - Strictly zero invocation of InvoiceMatchingEngine or Risk recalculation
 * - Missing/inactive/invalid policy strictly fails closed (DecisionStatus.REVIEW + EvaluationOutcome.ERROR)
 */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FraudDecisionAdapter implements ReadOnlyDecisionAdapter {

    public static final String ERROR_POLICY_UNAVAILABLE = "FRAUD_POLICY_UNAVAILABLE";
    public static final String ERROR_POLICY_INACTIVE = "FRAUD_POLICY_INACTIVE";
    public static final String ERROR_POLICY_INTEGRITY_FAILURE = "FRAUD_POLICY_INTEGRITY_FAILURE";

    private final FraudDecisionEvidenceReader reader;

    public FraudDecisionAdapter(FraudDecisionEvidenceReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
    }

    @Override
    public EngineType type() {
        return EngineType.FRAUD;
    }

    /**
     * Internal tenant-scoped entry point. User-facing callers must use evaluateForCaller.
     */
    @Override
    public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        return evaluateSources(reader.read(context, null, null), context);
    }

    public RecordEngineDecisionCommand evaluateForDeal(UUID dealId, EvaluationContext context) {
        return evaluateSources(reader.read(context, Objects.requireNonNull(dealId), null), context);
    }

    public RecordEngineDecisionCommand evaluateForCaller(UUID dealId, EvaluationContext context, UserPrincipal caller) {
        return evaluateSources(reader.read(context, Objects.requireNonNull(dealId), Objects.requireNonNull(caller)), context);
    }

    private RecordEngineDecisionCommand evaluateSources(FraudDecisionEvidenceReader.FraudSources s, EvaluationContext context) {
        // 1. Validate Platform Fraud Policy v1.0.1
        EnginePolicyVersion policy = s.policyVersion();
        if (policy == null) {
            return createPolicyErrorCommand(
                    context,
                    ERROR_POLICY_UNAVAILABLE,
                    "Platform fraud policy " + FraudPolicyDefinition.POLICY_KEY + " " + FraudPolicyDefinition.POLICY_VERSION + " is not registered.",
                    null,
                    null
            );
        }

        if (policy.getActivatedAt() == null) {
            return createPolicyErrorCommand(
                    context,
                    ERROR_POLICY_INACTIVE,
                    "Platform fraud policy " + FraudPolicyDefinition.POLICY_KEY + " " + FraudPolicyDefinition.POLICY_VERSION + " is inactive.",
                    policy.getId(),
                    policy.getContentHash()
            );
        }

        boolean hashMatches = Objects.equals(policy.getContentHash(), FraudPolicyDefinition.getExpectedContentHash());
        boolean contentMatches = Objects.equals(policy.getPolicyContent(), FraudPolicyDefinition.getCanonicalPolicyContent());
        if (!hashMatches || !contentMatches) {
            return createPolicyErrorCommand(
                    context,
                    ERROR_POLICY_INTEGRITY_FAILURE,
                    "Platform fraud policy " + FraudPolicyDefinition.POLICY_KEY + " " + FraudPolicyDefinition.POLICY_VERSION + " failed integrity verification.",
                    policy.getId(),
                    policy.getContentHash()
            );
        }

        // 2. Evaluate Rule Signals
        List<String> blockSignals = new ArrayList<>();
        List<String> reviewSignals = new ArrayList<>();

        Invoice invoice = s.invoice();
        List<InvoiceCheck> checks = s.invoiceChecks();

        // Check invoice requirement and presence
        if (s.milestone().isInvoiceRequired() && invoice == null) {
            reviewSignals.add(FraudPolicyDefinition.REASON_MISSING_INVOICE_EVIDENCE);
        }

        if (invoice != null) {
            if (checks.isEmpty() || s.revisionLineageCorrupted()) {
                reviewSignals.add(FraudPolicyDefinition.REASON_MISSING_INVOICE_EVIDENCE);
            }

            // S01: EXACT_DOCUMENT_HASH_DUPLICATE (BLOCK)
            boolean s01CheckFailed = checks.stream().anyMatch(c ->
                    c.getCheckType() == InvoiceCheckType.DOCUMENT_HASH_CHECK && c.getStatus() == CheckStatus.FAIL);
            boolean s01DuplicateFound = !s.s01DuplicateCandidates().isEmpty();

            if (s01CheckFailed || s01DuplicateFound) {
                blockSignals.add(FraudPolicyDefinition.REASON_DUPLICATE_DOCUMENT_HASH);
            }

            // S02: DUPLICATE_INVOICE_NUMBER (BLOCK)
            if (!s.s02DuplicateCandidates().isEmpty()) {
                blockSignals.add(FraudPolicyDefinition.REASON_DUPLICATE_INVOICE_NUMBER);
            }

            // S03: VENDOR_TAX_IDENTITY_MISMATCH (BLOCK)
            boolean s03TaxMismatch = checks.stream().anyMatch(c ->
                    (c.getCheckType() == InvoiceCheckType.GSTIN_MATCH || c.getCheckType() == InvoiceCheckType.PAN_MATCH)
                            && c.getStatus() == CheckStatus.FAIL);
            if (s03TaxMismatch) {
                blockSignals.add(FraudPolicyDefinition.REASON_TAX_IDENTITY_MISMATCH);
            }

            // S07: INVOICE_DATE_ANOMALY (REVIEW)
            boolean s07DateAnomaly = checks.stream().anyMatch(c ->
                    c.getCheckType() == InvoiceCheckType.INVOICE_DATE_VALID
                            && (c.getStatus() == CheckStatus.WARNING || c.getStatus() == CheckStatus.FAIL
                            || "INVOICE_DATE_ANOMALY".equals(c.getReasonCode())));
            if (s07DateAnomaly) {
                reviewSignals.add(FraudPolicyDefinition.REASON_INVOICE_DATE_ANOMALY);
            }
        }

        // S04 / Beneficiary Evaluation
        var beneficiaryResult = s.beneficiaryResult();
        if (beneficiaryResult == null || !beneficiaryResult.eligible()) {
            // Check for explicit name mismatch on seller accounts
            boolean explicitMismatch = s.sellerBankAccounts().stream().anyMatch(acc ->
                    "MISMATCH".equalsIgnoreCase(acc.getNameMatchStatus()));

            if (explicitMismatch) {
                blockSignals.add(FraudPolicyDefinition.REASON_BENEFICIARY_NAME_MISMATCH);
            } else {
                reviewSignals.add(FraudPolicyDefinition.REASON_BENEFICIARY_UNASSIGNED);
            }
        }

        // 3. Precedence & Deterministic Ordering
        // Order reasons deterministically according to policy definition: S01, S02, S03, S04, S07, missing evidence
        List<String> orderedReasons = new ArrayList<>();
        if (blockSignals.contains(FraudPolicyDefinition.REASON_DUPLICATE_DOCUMENT_HASH)) {
            orderedReasons.add(FraudPolicyDefinition.REASON_DUPLICATE_DOCUMENT_HASH);
        }
        if (blockSignals.contains(FraudPolicyDefinition.REASON_DUPLICATE_INVOICE_NUMBER)) {
            orderedReasons.add(FraudPolicyDefinition.REASON_DUPLICATE_INVOICE_NUMBER);
        }
        if (blockSignals.contains(FraudPolicyDefinition.REASON_TAX_IDENTITY_MISMATCH)) {
            orderedReasons.add(FraudPolicyDefinition.REASON_TAX_IDENTITY_MISMATCH);
        }
        if (blockSignals.contains(FraudPolicyDefinition.REASON_BENEFICIARY_NAME_MISMATCH)) {
            orderedReasons.add(FraudPolicyDefinition.REASON_BENEFICIARY_NAME_MISMATCH);
        }
        if (reviewSignals.contains(FraudPolicyDefinition.REASON_INVOICE_DATE_ANOMALY)) {
            orderedReasons.add(FraudPolicyDefinition.REASON_INVOICE_DATE_ANOMALY);
        }
        if (reviewSignals.contains(FraudPolicyDefinition.REASON_MISSING_INVOICE_EVIDENCE)) {
            orderedReasons.add(FraudPolicyDefinition.REASON_MISSING_INVOICE_EVIDENCE);
        }
        if (reviewSignals.contains(FraudPolicyDefinition.REASON_BENEFICIARY_UNASSIGNED)) {
            orderedReasons.add(FraudPolicyDefinition.REASON_BENEFICIARY_UNASSIGNED);
        }

        DecisionStatus decision;
        String explanation;

        if (!blockSignals.isEmpty()) {
            decision = DecisionStatus.BLOCK;
            explanation = "Deterministic fraud policy v1.0.1 detected one or more blocking persisted signals.";
        } else if (!reviewSignals.isEmpty()) {
            decision = DecisionStatus.REVIEW;
            explanation = "Fraud evaluation requires review because required evidence is incomplete or flagged for review.";
        } else {
            decision = DecisionStatus.ALLOW;
            orderedReasons = List.of(FraudPolicyDefinition.REASON_EVALUATION_PASSED);
            explanation = "Deterministic fraud policy v1.0.1 evaluated current persisted evidence with no enabled fraud indicators.";
        }

        s.evidence().input("FINAL_STATUS", decision.name(), String.join("|", orderedReasons));

        return RecordEngineDecisionCommand.builder()
                .organizationId(context.organizationId())
                .engineType(EngineType.FRAUD)
                .subjectType(DecisionSubjectType.MILESTONE)
                .subjectId(context.subjectId())
                .decision(decision)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(null)
                .reasonCodes(orderedReasons)
                .explanation(explanation)
                .evidenceReferences(s.evidence().getSortedReferences())
                .ruleVersion(FraudPolicyDefinition.POLICY_KEY + ":" + FraudPolicyDefinition.POLICY_VERSION)
                .policyVersionId(policy.getId())
                .policyContentHash(FraudPolicyDefinition.getExpectedContentHash())
                .sourceAssessmentId(null)
                .evaluationId(context.evaluationId())
                .correlationId(context.correlationId())
                .inputFingerprint(s.evidence().computeFingerprint())
                .inputRevision(FraudPolicyDefinition.POLICY_VERSION)
                .schemaVersion(FraudPolicyDefinition.SCHEMA_VERSION)
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(context.evaluationTime() != null ? context.evaluationTime() : Instant.now())
                .build();
    }

    private RecordEngineDecisionCommand createPolicyErrorCommand(
            EvaluationContext context,
            String errorCode,
            String explanation,
            UUID policyVersionId,
            String policyContentHash
    ) {
        String inputFingerprint = computeErrorFingerprint(
                context.organizationId(),
                context.subjectId(),
                errorCode,
                FraudPolicyDefinition.POLICY_VERSION
        );

        return RecordEngineDecisionCommand.builder()
                .organizationId(context.organizationId())
                .engineType(EngineType.FRAUD)
                .subjectType(DecisionSubjectType.MILESTONE)
                .subjectId(context.subjectId())
                .decision(DecisionStatus.REVIEW)
                .evaluationOutcome(EvaluationOutcome.ERROR)
                .errorCode(errorCode)
                .score(null)
                .reasonCodes(List.of(errorCode))
                .explanation(explanation)
                .evidenceReferences(List.of())
                .ruleVersion(FraudPolicyDefinition.POLICY_KEY + ":" + FraudPolicyDefinition.POLICY_VERSION)
                .policyVersionId(policyVersionId)
                .policyContentHash(policyContentHash)
                .sourceAssessmentId(null)
                .evaluationId(context.evaluationId())
                .correlationId(context.correlationId())
                .inputFingerprint(inputFingerprint)
                .inputRevision(FraudPolicyDefinition.POLICY_VERSION)
                .schemaVersion(FraudPolicyDefinition.SCHEMA_VERSION)
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(context.evaluationTime() != null ? context.evaluationTime() : Instant.now())
                .build();
    }

    private static String computeErrorFingerprint(UUID orgId, UUID subjectId, String errorCode, String version) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = "FRAUD_ERROR|" + orgId + "|" + subjectId + "|" + errorCode + "|" + version;
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
