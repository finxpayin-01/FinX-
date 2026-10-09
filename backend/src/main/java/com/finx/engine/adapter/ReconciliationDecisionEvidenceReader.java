package com.finx.engine.adapter;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EvaluationContext;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Pure read-only evidence reader for the RECONCILIATION decision adapter.
 * <p>
 * Responsibilities:
 * - Scoped resolution of Milestone, Deal, and Buyer Organization
 * - Retrieval of relevant PaymentInstructions and PaymentTransactions
 * - Retrieval of durable ReconciliationItems, ReconciliationRuns, BankStatementImports, and BankStatementLines
 * - Enforcement of tenant isolation and caller authorization
 * <p>
 * Guarantees:
 * - Zero writes or entity mutations
 * - Zero external banking or provider calls
 * - Zero trigger of matcher or legacy reconciliation
 */
@Component
public class ReconciliationDecisionEvidenceReader {

    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DealAccessService dealAccessService;
    private final PaymentInstructionRepository paymentInstructionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationItemRepository reconciliationItemRepository;
    private final BankStatementImportRepository bankStatementImportRepository;
    private final BankStatementLineRepository bankStatementLineRepository;

    public ReconciliationDecisionEvidenceReader(
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            DealAccessService dealAccessService,
            PaymentInstructionRepository paymentInstructionRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            ReconciliationRunRepository reconciliationRunRepository,
            ReconciliationItemRepository reconciliationItemRepository,
            BankStatementImportRepository bankStatementImportRepository,
            BankStatementLineRepository bankStatementLineRepository
    ) {
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.dealAccessService = Objects.requireNonNull(dealAccessService, "dealAccessService must not be null");
        this.paymentInstructionRepository = Objects.requireNonNull(paymentInstructionRepository, "paymentInstructionRepository must not be null");
        this.paymentTransactionRepository = Objects.requireNonNull(paymentTransactionRepository, "paymentTransactionRepository must not be null");
        this.reconciliationRunRepository = Objects.requireNonNull(reconciliationRunRepository, "reconciliationRunRepository must not be null");
        this.reconciliationItemRepository = Objects.requireNonNull(reconciliationItemRepository, "reconciliationItemRepository must not be null");
        this.bankStatementImportRepository = Objects.requireNonNull(bankStatementImportRepository, "bankStatementImportRepository must not be null");
        this.bankStatementLineRepository = Objects.requireNonNull(bankStatementLineRepository, "bankStatementLineRepository must not be null");
    }

    public record ReconciliationSources(
            Deal deal,
            Milestone milestone,
            List<PaymentInstruction> paymentInstructions,
            List<PaymentTransaction> paymentTransactions,
            Map<UUID, List<ReconciliationItem>> itemsByTransactionId,
            Map<UUID, ReconciliationRun> runsById,
            Map<UUID, BankStatementImport> importsById,
            Map<UUID, BankStatementLine> linesById,
            DecisionEvidence evidence
    ) {}

    public ReconciliationSources read(EvaluationContext context, UUID expectedDealId, UserPrincipal caller) {
        Objects.requireNonNull(context, "context must not be null");

        if (!DecisionSubjectType.MILESTONE.name().equals(context.subjectType())) {
            throw new IllegalArgumentException("Expected MILESTONE subject for reconciliation decision");
        }

        Milestone milestone = milestoneRepository.findById(context.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", context.subjectId()));

        if (milestone.getDealId() == null || (expectedDealId != null && !expectedDealId.equals(milestone.getDealId()))) {
            throw new UnauthorizedException("Deal scope mismatch for milestone: " + milestone.getId());
        }

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (caller != null) {
            dealAccessService.requireDealAccess(deal, caller);
        }

        if (context.organizationId() != null && !context.organizationId().equals(deal.getBuyerOrganizationId())) {
            throw new UnauthorizedException("Evaluation context organization does not match deal buyer organization");
        }

        DecisionEvidence evidence = new DecisionEvidence();
        evidence.reference("DEAL", deal.getId(), deal.getCreatedAt(), null, "DealRepository", null);
        evidence.reference("MILESTONE", milestone.getId(), milestone.getCreatedAt(), null, "MilestoneRepository", null);

        evidence.input(
                "DEAL", deal.getId(), deal.getStatus(), deal.getBuyerOrganizationId(), deal.getSellerOrganizationId()
        );
        evidence.input(
                "MILESTONE", milestone.getId(), milestone.getStatus(), milestone.getAmount(), milestone.getCurrency()
        );

        // Discover funding payment instructions for this deal and milestone (or deal-level funding)
        List<PaymentInstruction> allDealInstructions = paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(deal.getId());
        List<PaymentInstruction> relevantInstructions = new ArrayList<>();
        List<PaymentTransaction> successfulTransactions = new ArrayList<>();
        Map<UUID, List<ReconciliationItem>> itemsByTxId = new LinkedHashMap<>();
        Map<UUID, ReconciliationRun> runsById = new LinkedHashMap<>();
        Map<UUID, BankStatementImport> importsById = new LinkedHashMap<>();
        Map<UUID, BankStatementLine> linesById = new LinkedHashMap<>();

        // Sort instructions deterministically by ID
        List<PaymentInstruction> sortedInstructions = allDealInstructions.stream()
                .sorted(Comparator.comparing(PaymentInstruction::getId))
                .toList();

        for (PaymentInstruction pi : sortedInstructions) {
            // Check relevance: belongs to buyer org and is either milestone-specific or whole-deal
            if (!deal.getBuyerOrganizationId().equals(pi.getBuyerOrganizationId())) {
                continue;
            }
            if (pi.getMilestoneId() != null && !pi.getMilestoneId().equals(milestone.getId())) {
                continue;
            }

            relevantInstructions.add(pi);
            evidence.reference("PAYMENT_INSTRUCTION", pi.getId(), pi.getCreatedAt(), null, "PaymentInstructionRepository", null);
            evidence.input(
                    "PAYMENT_INSTRUCTION", pi.getId(), pi.getStatus(), pi.getAmount(), pi.getCurrency(),
                    pi.getProvider(), pi.getProviderAccountId(), pi.getUpdatedAt()
            );

            List<PaymentTransaction> txs = paymentTransactionRepository.findByPaymentInstructionId(pi.getId());
            List<PaymentTransaction> sortedTxs = txs.stream()
                    .sorted(Comparator.comparing(PaymentTransaction::getId))
                    .toList();

            for (PaymentTransaction pt : sortedTxs) {
                if ("SUCCESS".equalsIgnoreCase(pt.getStatus())) {
                    successfulTransactions.add(pt);
                    evidence.reference("PAYMENT_TRANSACTION", pt.getId(), pt.getCreatedAt(), null, "PaymentTransactionRepository", null);
                    evidence.input(
                            "PAYMENT_TRANSACTION", pt.getId(), pt.getStatus(), pt.getAmount(), pt.getCurrency(),
                            pt.getProvider(), pt.getProviderReference(), pt.getCreatedAt()
                    );

                    // Find reconciliation items matching this transaction
                    List<ReconciliationItem> items = reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                            ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId()
                    );
                    List<ReconciliationItem> sortedItems = items.stream()
                            .sorted(Comparator.comparing(ReconciliationItem::getId))
                            .toList();
                    itemsByTxId.put(pt.getId(), sortedItems);

                    for (ReconciliationItem item : sortedItems) {
                        evidence.reference("RECONCILIATION_ITEM", item.getId(), item.getCreatedAt(), null, "ReconciliationItemRepository", null);
                        evidence.input(
                                "RECONCILIATION_ITEM", item.getId(), item.getStatus(), item.getReasonCode(),
                                item.getResolutionStatus(), item.getFinxAmount(), item.getBankAmount()
                        );

                        // Load run
                        if (item.getReconciliationRunId() != null && !runsById.containsKey(item.getReconciliationRunId())) {
                            reconciliationRunRepository.findById(item.getReconciliationRunId()).ifPresent(run -> {
                                runsById.put(run.getId(), run);
                                evidence.reference("RECONCILIATION_RUN", run.getId(), run.getCreatedAt(), null, "ReconciliationRunRepository", null);
                                evidence.input(
                                        "RECONCILIATION_RUN", run.getId(), run.getStatus(), run.getMatcherVersion(),
                                        run.getStatementSourceHash(), run.getStartTime(), run.getEndTime(),
                                        run.getMatchedCount(), run.getMismatchCount()
                                );

                                // Load BankStatementImport
                                if (run.getBankStatementImportId() != null && !importsById.containsKey(run.getBankStatementImportId())) {
                                    bankStatementImportRepository.findById(run.getBankStatementImportId()).ifPresent(imp -> {
                                        importsById.put(imp.getId(), imp);
                                        evidence.reference("BANK_STATEMENT_IMPORT", imp.getId(), imp.getCreatedAt(), null, "BankStatementImportRepository", null);
                                        evidence.input(
                                                "BANK_STATEMENT_IMPORT", imp.getId(), imp.getProvider(),
                                                imp.getProviderAccountId(), imp.getSourceHash(), imp.getStatus()
                                        );
                                    });
                                }
                            });
                        }

                        // Load BankStatementLine
                        if (item.getBankStatementLineId() != null && !linesById.containsKey(item.getBankStatementLineId())) {
                            bankStatementLineRepository.findById(item.getBankStatementLineId()).ifPresent(line -> {
                                linesById.put(line.getId(), line);
                                evidence.reference("BANK_STATEMENT_LINE", line.getId(), line.getCreatedAt(), null, "BankStatementLineRepository", null);
                                evidence.input(
                                        "BANK_STATEMENT_LINE", line.getId(), line.getProviderLineId(),
                                        line.getTransactionReference(), line.getAmount(), line.getCurrency(),
                                        line.getDirection(), line.getNormalizedStatus(), line.getTransactionTime(),
                                        line.getLineFingerprint()
                                );
                            });
                        }
                    }
                }
            }
        }

        return new ReconciliationSources(
                deal,
                milestone,
                relevantInstructions,
                successfulTransactions,
                itemsByTxId,
                runsById,
                importsById,
                linesById,
                evidence
        );
    }
}
