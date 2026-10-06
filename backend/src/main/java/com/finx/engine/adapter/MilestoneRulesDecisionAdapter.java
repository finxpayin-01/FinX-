package com.finx.engine.adapter;

import com.finx.agreement.enums.AgreementStatus;
import com.finx.engine.model.*;
import com.finx.escrow.entity.*;
import com.finx.escrow.repository.*;
import com.finx.milestone.entity.*;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/**
 * Current contractual evidence only. ALLOW is not payment authorization or a safe-to-pay claim.
 * Reservations, partial release, dependency DAG, grace policy, trust, risk and fraud are deferred.
 */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MilestoneRulesDecisionAdapter implements ReadOnlyDecisionAdapter {
    private final MilestoneDecisionEvidenceReader reader;
    private final MilestoneRepository milestones;
    private final EscrowAccountRepository accounts;
    private final EscrowLedgerRepository ledger;

    public MilestoneRulesDecisionAdapter(MilestoneDecisionEvidenceReader reader, MilestoneRepository milestones,
            EscrowAccountRepository accounts, EscrowLedgerRepository ledger) {
        this.reader = reader; this.milestones = milestones; this.accounts = accounts; this.ledger = ledger;
    }

    @Override public EngineType type() { return EngineType.MILESTONE_RULES; }

    /** Internal tenant-scoped entry point. User-facing callers must use evaluateForCaller. */
    @Override public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        return evaluateSources(reader.read(context, null, null), context);
    }

    public RecordEngineDecisionCommand evaluateForDeal(UUID dealId, EvaluationContext context) {
        return evaluateSources(reader.read(context, Objects.requireNonNull(dealId), null), context);
    }

    public RecordEngineDecisionCommand evaluateForCaller(UUID dealId, EvaluationContext context, UserPrincipal caller) {
        return evaluateSources(reader.read(context, Objects.requireNonNull(dealId), Objects.requireNonNull(caller)), context);
    }

    private RecordEngineDecisionCommand evaluateSources(MilestoneDecisionEvidenceReader.Sources s, EvaluationContext context) {
        var m = s.milestone();
        var deal = s.deal();
        var evidence = s.evidence();
        List<String> blocks = new ArrayList<>();
        List<String> reviews = new ArrayList<>();
        List<Milestone> allocation = milestones.findByDealIdOrderBySequenceAsc(deal.getId()).stream()
                .sorted(Comparator.comparing(Milestone::getId)).toList();
        for (Milestone other : allocation) {
            MilestoneDecisionEvidenceReader.requireScope(deal.getId().equals(other.getDealId()));
            if (!other.getId().equals(m.getId())) MilestoneDecisionEvidenceReader.milestoneEvidence(evidence, other);
        }
        if (m.getStatus() == MilestoneStatus.REJECTED) blocks.add("MILESTONE_STATUS_REJECTED");
        if (m.getStatus() == MilestoneStatus.CANCELLED) blocks.add("MILESTONE_STATUS_CANCELLED");
        if (deal.getCurrency() == null || !deal.getCurrency().equals(m.getCurrency())) blocks.add("MILESTONE_CURRENCY_MISMATCH");
        if (!"INR".equals(deal.getCurrency()) || !"INR".equals(m.getCurrency())) reviews.add("MILESTONE_CURRENCY_UNSUPPORTED");
        boolean positive = m.getAmount() != null && m.getAmount().signum() > 0;
        if (!positive) blocks.add("MILESTONE_AMOUNT_INVALID");
        if (deal.getTotalAmount() == null || (positive && m.getAmount().compareTo(deal.getTotalAmount()) > 0)) {
            blocks.add("MILESTONE_AMOUNT_EXCEEDS_DEAL");
        }
        List<Milestone> active = allocation.stream().filter(a -> a.getStatus() != MilestoneStatus.CANCELLED).toList();
        BigDecimal sum = active.stream().map(Milestone::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (active.stream().anyMatch(a -> a.getAmount() == null || a.getAmount().signum() <= 0)
                || allocation.stream().noneMatch(a -> a.getId().equals(m.getId()))
                || deal.getTotalAmount() == null || sum.compareTo(deal.getTotalAmount()) != 0) {
            blocks.add("MILESTONE_ALLOCATION_MISMATCH");
        }
        if (s.agreement() == null) reviews.add("AGREEMENT_MISSING");
        else if (s.agreement().getStatus() != AgreementStatus.SIGNED) reviews.add("AGREEMENT_NOT_SIGNED");
        boolean allApproved = !s.deliverables().isEmpty()
                && s.deliverables().stream().allMatch(d -> d.getStatus() == DeliverableStatus.APPROVED);
        if (s.deliverables().isEmpty()) reviews.add("DELIVERABLES_MISSING");
        else if (!allApproved) reviews.add("DELIVERABLES_PENDING_APPROVAL");
        boolean overdue = m.getSubmissionDeadline() != null && m.getSubmissionDeadline().isBefore(context.evaluationTime());
        evidence.input("DEADLINE_PASSED", overdue);
        if (overdue && !allApproved) reviews.add("DEADLINE_EXCEEDED_REVIEW");
        if (m.isInvoiceRequired() && s.invoice() == null) reviews.add("INVOICE_EVIDENCE_MISSING");
        else if (!s.invoiceCurrent()) reviews.add("INVOICE_EVIDENCE_STALE");
        if (!s.disputes().isEmpty()) blocks.add("MILESTONE_ACTIVE_DISPUTE");
        EscrowAccount account = accounts.findByDealId(deal.getId()).orElse(null);
        if (account == null) {
            reviews.add("ESCROW_ACCOUNT_MISSING");
            evidence.input("ESCROW_ACCOUNT_MISSING");
        } else {
            MilestoneDecisionEvidenceReader.requireScope(deal.getId().equals(account.getDealId()));
            evidence.reference("ESCROW_ACCOUNT", account.getId(), account.getUpdatedAt(), null, "B05_B06_ESCROW_PROJECTION",
                    account.getVersion() == null ? null : account.getVersion().toString());
            evidence.input("ESCROW_ACCOUNT", account.getId(), account.getBalance(), account.getVersion(), account.getCurrency());
            if (!Objects.equals(account.getCurrency(), m.getCurrency())) reviews.add("ESCROW_CURRENCY_MISMATCH");
            if (positive && account.getAvailableBalance().compareTo(m.getAmount()) < 0) reviews.add("INSUFFICIENT_CONFIRMED_FUNDS");
        }
        EscrowLedger release = ledger.findByMilestoneIdAndTransactionType(m.getId(), TransactionType.RELEASE).orElse(null);
        if (release != null) {
            MilestoneDecisionEvidenceReader.requireScope(m.getId().equals(release.getMilestoneId())
                    && release.getTransactionType() == TransactionType.RELEASE
                    && account != null && account.getId().equals(release.getEscrowAccountId()));
            evidence.reference("ESCROW_LEDGER", release.getId(), release.getCreatedAt(), null, "ESCROW_LEDGER_REPOSITORY", null);
            evidence.input("RELEASE", release.getId(), release.getAmount(), release.getTransactionType());
            blocks.add("MILESTONE_ALREADY_RELEASED");
        } else evidence.input("NO_PRIOR_RELEASE");
        if (active.stream().anyMatch(a -> a.getSequence() < m.getSequence() && a.getStatus() != MilestoneStatus.COMPLETED)) {
            reviews.add("SEQUENCE_PREREQUISITE_UNMET");
        }
        List<String> reasons = new ArrayList<>(blocks);
        reasons.addAll(reviews);
        DecisionStatus status = !blocks.isEmpty() ? DecisionStatus.BLOCK : !reviews.isEmpty() ? DecisionStatus.REVIEW : DecisionStatus.ALLOW;
        if (reasons.isEmpty()) reasons.add("MILESTONE_CONTRACT_COMPLIANT");
        return evidence.command(context, type(), status, reasons,
                "Assessment of current implemented milestone contractual rules only. ALLOW is not payment authorization.");
    }
}
