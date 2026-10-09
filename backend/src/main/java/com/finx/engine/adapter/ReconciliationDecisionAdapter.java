package com.finx.engine.adapter;

import com.finx.engine.model.*;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Pure read-only deterministic decision adapter for the RECONCILIATION engine.
 * <p>
 * Evaluates persisted deterministic reconciliation evidence for milestone release funding:
 * - Authoritative run version check (FINX_RECONCILIATION_MATCHER_V1)
 * - Bank statement source hash integrity
 * - Inbound funding PaymentTransaction reconciliation matching
 * - Run cleanliness (mismatchCount == 0, zero unverified exceptions)
 * - Freshness across financial effect revisions
 * - Strict bank finality (FINAL_SUCCESS required for ALLOW)
 * - Zero execution, zero mutation, zero bank/matcher invocations
 */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReconciliationDecisionAdapter implements ReadOnlyDecisionAdapter {

    public static final String RECONCILIATION_CONFIRMED = "RECONCILIATION_CONFIRMED";
    public static final String RECONCILIATION_FUNDING_NOT_PROVEN = "RECONCILIATION_FUNDING_NOT_PROVEN";
    public static final String RECONCILIATION_NO_AUTHORITATIVE_RUN = "RECONCILIATION_NO_AUTHORITATIVE_RUN";
    public static final String RECONCILIATION_LEGACY_RUN_NONAUTHORITATIVE = "RECONCILIATION_LEGACY_RUN_NONAUTHORITATIVE";
    public static final String RECONCILIATION_SOURCE_SNAPSHOT_MISMATCH = "RECONCILIATION_SOURCE_SNAPSHOT_MISMATCH";
    public static final String RECONCILIATION_STALE = "RECONCILIATION_STALE";
    public static final String RECONCILIATION_RUN_HAS_EXCEPTIONS = "RECONCILIATION_RUN_HAS_EXCEPTIONS";
    public static final String RECONCILIATION_BANK_FINALITY_PENDING = "RECONCILIATION_BANK_FINALITY_PENDING";
    public static final String RECONCILIATION_BANK_FINALITY_UNKNOWN = "RECONCILIATION_BANK_FINALITY_UNKNOWN";
    public static final String RECONCILIATION_INTERNAL_ACCOUNT_SCOPE_UNBOUND = "RECONCILIATION_INTERNAL_ACCOUNT_SCOPE_UNBOUND";
    public static final String RECONCILIATION_REFERENCE_AMBIGUOUS = "RECONCILIATION_REFERENCE_AMBIGUOUS";
    public static final String RECONCILIATION_MISSING_EVIDENCE = "RECONCILIATION_MISSING_EVIDENCE";

    // Hard block codes
    public static final String RECONCILIATION_AMOUNT_MISMATCH = "RECONCILIATION_AMOUNT_MISMATCH";
    public static final String RECONCILIATION_CURRENCY_MISMATCH = "RECONCILIATION_CURRENCY_MISMATCH";
    public static final String RECONCILIATION_DIRECTION_MISMATCH = "RECONCILIATION_DIRECTION_MISMATCH";
    public static final String RECONCILIATION_ACCOUNT_SCOPE_MISMATCH = "RECONCILIATION_ACCOUNT_SCOPE_MISMATCH";
    public static final String RECONCILIATION_INTERNAL_PROVIDER_MISMATCH = "RECONCILIATION_INTERNAL_PROVIDER_MISMATCH";
    public static final String RECONCILIATION_BANK_FINALITY_FAILED = "RECONCILIATION_BANK_FINALITY_FAILED";
    public static final String RECONCILIATION_BANK_FINALITY_REVERSED = "RECONCILIATION_BANK_FINALITY_REVERSED";
    public static final String RECONCILIATION_MATERIAL_MISMATCH = "RECONCILIATION_MATERIAL_MISMATCH";

    private final ReconciliationDecisionEvidenceReader reader;
    private final ReconciliationItemRepository itemRepository;

    public ReconciliationDecisionAdapter(
            ReconciliationDecisionEvidenceReader reader,
            ReconciliationItemRepository itemRepository
    ) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.itemRepository = Objects.requireNonNull(itemRepository, "itemRepository must not be null");
    }

    @Override
    public EngineType type() {
        return EngineType.RECONCILIATION;
    }

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

    private RecordEngineDecisionCommand evaluateSources(
            ReconciliationDecisionEvidenceReader.ReconciliationSources s,
            EvaluationContext context
    ) {
        Set<String> blocks = new LinkedHashSet<>();
        Set<String> reviews = new LinkedHashSet<>();

        // 1. Missing funding check
        if (s.paymentTransactions().isEmpty()) {
            reviews.add(RECONCILIATION_FUNDING_NOT_PROVEN);
            return s.evidence().command(
                    context,
                    EngineType.RECONCILIATION,
                    DecisionStatus.REVIEW,
                    List.copyOf(reviews),
                    "No successful inbound funding payment transactions found for milestone release."
            );
        }

        // Map PaymentInstructions by ID for quick lookup
        Map<UUID, PaymentInstruction> instructionsById = new HashMap<>();
        for (PaymentInstruction pi : s.paymentInstructions()) {
            instructionsById.put(pi.getId(), pi);
        }

        // 2. Evaluate each required payment transaction
        for (PaymentTransaction pt : s.paymentTransactions()) {
            PaymentInstruction pi = instructionsById.get(pt.getPaymentInstructionId());
            List<ReconciliationItem> items = s.itemsByTransactionId().getOrDefault(pt.getId(), Collections.emptyList());

            if (items.isEmpty()) {
                reviews.add(RECONCILIATION_NO_AUTHORITATIVE_RUN);
                continue;
            }

            boolean hasCleanAuthoritativeMatch = false;
            Set<String> txReviews = new LinkedHashSet<>();

            for (ReconciliationItem item : items) {
                ReconciliationRun run = s.runsById().get(item.getReconciliationRunId());
                BankStatementLine line = item.getBankStatementLineId() != null ? s.linesById().get(item.getBankStatementLineId()) : null;

                // Check hard block contradictions on any item
                if (item.getReasonCode() == ReconciliationReasonCode.AMOUNT_MISMATCH
                        || item.getStatus() == ReconciliationItemStatus.AMOUNT_MISMATCH) {
                    blocks.add(RECONCILIATION_AMOUNT_MISMATCH);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.CURRENCY_MISMATCH) {
                    blocks.add(RECONCILIATION_CURRENCY_MISMATCH);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.DIRECTION_MISMATCH) {
                    blocks.add(RECONCILIATION_DIRECTION_MISMATCH);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.ACCOUNT_SCOPE_MISMATCH) {
                    blocks.add(RECONCILIATION_ACCOUNT_SCOPE_MISMATCH);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.INTERNAL_PROVIDER_MISMATCH) {
                    blocks.add(RECONCILIATION_INTERNAL_PROVIDER_MISMATCH);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.BANK_FINALITY_FAILED
                        || (line != null && line.getNormalizedStatus() == BankStatementLineStatus.FAILED)) {
                    blocks.add(RECONCILIATION_BANK_FINALITY_FAILED);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.BANK_FINALITY_REVERSED
                        || (line != null && line.getNormalizedStatus() == BankStatementLineStatus.REVERSED)) {
                    blocks.add(RECONCILIATION_BANK_FINALITY_REVERSED);
                }

                // Collect candidate review reasons on item
                if (item.getReasonCode() == ReconciliationReasonCode.INTERNAL_ACCOUNT_SCOPE_UNBOUND) {
                    txReviews.add(RECONCILIATION_INTERNAL_ACCOUNT_SCOPE_UNBOUND);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.REFERENCE_AMBIGUOUS) {
                    txReviews.add(RECONCILIATION_REFERENCE_AMBIGUOUS);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.MISSING_EXTERNAL
                        || item.getReasonCode() == ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH
                        || item.getReasonCode() == ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE
                        || item.getReasonCode() == ReconciliationReasonCode.UNCLAIMED_NONFINAL_BANK_LINE) {
                    txReviews.add(RECONCILIATION_MISSING_EVIDENCE);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.BANK_FINALITY_PENDING
                        || (line != null && line.getNormalizedStatus() == BankStatementLineStatus.PENDING)) {
                    txReviews.add(RECONCILIATION_BANK_FINALITY_PENDING);
                }
                if (item.getReasonCode() == ReconciliationReasonCode.BANK_FINALITY_UNKNOWN
                        || (line != null && line.getNormalizedStatus() == BankStatementLineStatus.UNKNOWN)) {
                    txReviews.add(RECONCILIATION_BANK_FINALITY_UNKNOWN);
                }

                // Validate Authoritative Run
                if (run == null || run.getMatcherVersion() == null) {
                    txReviews.add(RECONCILIATION_LEGACY_RUN_NONAUTHORITATIVE);
                    continue;
                }

                if (!DeterministicReconciliationMatcher.MATCHER_VERSION.equals(run.getMatcherVersion())
                        || run.getStatus() != ReconciliationRunStatus.COMPLETED
                        || run.getBankStatementImportId() == null
                        || run.getStatementSourceHash() == null) {
                    txReviews.add(RECONCILIATION_LEGACY_RUN_NONAUTHORITATIVE);
                    continue;
                }

                // Verify Statement Source Hash
                BankStatementImport imp = s.importsById().get(run.getBankStatementImportId());
                if (imp == null || !run.getStatementSourceHash().equals(imp.getSourceHash())) {
                    txReviews.add(RECONCILIATION_SOURCE_SNAPSHOT_MISMATCH);
                    continue;
                }

                // Verify Freshness
                if (run.getEndTime() == null
                        || run.getEndTime().isBefore(pt.getCreatedAt())
                        || (pi != null && pi.getUpdatedAt() != null && run.getEndTime().isBefore(pi.getUpdatedAt()))) {
                    txReviews.add(RECONCILIATION_STALE);
                    continue;
                }

                // Verify Run Cleanliness: mismatchCount == 0 and all items MATCHED
                if (run.getMismatchCount() > 0) {
                    txReviews.add(RECONCILIATION_RUN_HAS_EXCEPTIONS);
                    continue;
                }

                List<ReconciliationItem> allRunItems = itemRepository.findByReconciliationRunId(run.getId());
                boolean allMatched = allRunItems.stream().allMatch(ri -> ri.getStatus() == ReconciliationItemStatus.MATCHED);
                if (!allMatched) {
                    txReviews.add(RECONCILIATION_RUN_HAS_EXCEPTIONS);
                    continue;
                }

                // Verify Matched Item & Finality
                if (item.getStatus() == ReconciliationItemStatus.MATCHED
                        && item.getReasonCode() == ReconciliationReasonCode.MATCHED_CONFIRMED
                        && line != null
                        && line.getNormalizedStatus() == BankStatementLineStatus.FINAL_SUCCESS) {
                    hasCleanAuthoritativeMatch = true;
                }
            }

            if (!hasCleanAuthoritativeMatch && blocks.isEmpty()) {
                if (txReviews.isEmpty()) {
                    reviews.add(RECONCILIATION_NO_AUTHORITATIVE_RUN);
                } else {
                    reviews.addAll(txReviews);
                }
            }
        }

        // 3. Formulate DecisionStatus and explanation
        if (!blocks.isEmpty()) {
            return s.evidence().command(
                    context,
                    EngineType.RECONCILIATION,
                    DecisionStatus.BLOCK,
                    List.copyOf(blocks),
                    "Reconciliation blocked due to material bank/internal financial contradictions: " + String.join(", ", blocks)
            );
        }

        if (!reviews.isEmpty()) {
            return s.evidence().command(
                    context,
                    EngineType.RECONCILIATION,
                    DecisionStatus.REVIEW,
                    List.copyOf(reviews),
                    "Reconciliation requires manual review or rerun: " + String.join(", ", reviews)
            );
        }

        return s.evidence().command(
                context,
                EngineType.RECONCILIATION,
                DecisionStatus.ALLOW,
                List.of(RECONCILIATION_CONFIRMED),
                "All required funding transactions have been authoritatively reconciled against bank statement evidence."
        );
    }
}
