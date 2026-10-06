package com.finx.engine.adapter;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Deterministic, read-only decision adapter for DISPUTE evaluation.
 * <p>
 * Evaluates active and historical dispute cases on deals and milestones.
 * <p>
 * INVARIANTS:
 * - Read-only inspection of persisted dispute entities (zero mutations)
 * - AI and adapters NEVER resolve, reject, or modify disputes or deal status
 * - Human dispute authority is strictly preserved
 * - Open disputes BLOCK decision evaluation
 * - Historically resolved disputes require REVIEW (never automatically ALLOW release)
 * - Absence of active disputes yields ALLOW (DISPUTE_NONE_ACTIVE) — this is NOT financial release authorization
 * - Tenant isolation enforced via DealAccessService and organization binding
 * - Strictly zero banking, ledger, escrow, settlement, or payment side effects
 */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DisputeDecisionAdapter implements ReadOnlyDecisionAdapter {

    private final DisputeRepository disputeRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DealAccessService dealAccessService;

    public DisputeDecisionAdapter(
            DisputeRepository disputeRepository,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            DealAccessService dealAccessService
    ) {
        this.disputeRepository = Objects.requireNonNull(disputeRepository, "disputeRepository must not be null");
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.dealAccessService = Objects.requireNonNull(dealAccessService, "dealAccessService must not be null");
    }

    @Override
    public EngineType type() {
        return EngineType.DISPUTE;
    }

    @Override
    public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        DecisionSubjectType subjectType;
        try {
            subjectType = DecisionSubjectType.valueOf(context.subjectType());
        } catch (IllegalArgumentException | NullPointerException e) {
            subjectType = DecisionSubjectType.MILESTONE;
        }

        if (subjectType == DecisionSubjectType.DEAL) {
            return evaluateForDeal(context.subjectId(), context, null);
        } else if (subjectType == DecisionSubjectType.MILESTONE) {
            Milestone milestone = milestoneRepository.findById(context.subjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", context.subjectId()));
            return evaluateForMilestone(milestone.getDealId(), milestone.getId(), context, null);
        } else {
            throw new IllegalArgumentException("Unsupported dispute subject type: " + context.subjectType());
        }
    }

    public RecordEngineDecisionCommand evaluateForDeal(UUID dealId, EvaluationContext context) {
        return evaluateForDeal(dealId, context, null);
    }

    public RecordEngineDecisionCommand evaluateForDeal(UUID dealId, EvaluationContext context, UserPrincipal caller) {
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        if (caller != null) {
            dealAccessService.requireDealAccess(deal, caller);
        } else {
            validateTenantAccess(deal, context.organizationId());
        }

        return evaluateDisputeState(deal, null, context);
    }

    public RecordEngineDecisionCommand evaluateForMilestone(UUID dealId, UUID milestoneId, EvaluationContext context) {
        return evaluateForMilestone(dealId, milestoneId, context, null);
    }

    public RecordEngineDecisionCommand evaluateForMilestone(UUID dealId, UUID milestoneId, EvaluationContext context, UserPrincipal caller) {
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(milestoneId, "milestoneId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        if (!deal.getId().equals(milestone.getDealId())) {
            throw new IllegalArgumentException("Milestone " + milestoneId + " does not belong to Deal " + dealId);
        }

        if (caller != null) {
            dealAccessService.requireDealAccess(deal, caller);
        } else {
            validateTenantAccess(deal, context.organizationId());
        }

        return evaluateDisputeState(deal, milestone, context);
    }

    private void validateTenantAccess(Deal deal, UUID organizationId) {
        if (organizationId == null) {
            throw new UnauthorizedException("Evaluation context organizationId is required");
        }
        boolean isBuyerOrg = organizationId.equals(deal.getBuyerOrganizationId());
        boolean isSellerOrg = organizationId.equals(deal.getSellerOrganizationId());
        if (!isBuyerOrg && !isSellerOrg) {
            throw new UnauthorizedException("Organization " + organizationId + " is not a participant in deal " + deal.getId());
        }
    }

    private RecordEngineDecisionCommand evaluateDisputeState(Deal deal, Milestone milestone, EvaluationContext context) {
        DecisionEvidence evidence = new DecisionEvidence();
        List<String> blocks = new ArrayList<>();
        Set<String> reviews = new LinkedHashSet<>();

        // 1. Evidence for Deal
        evidence.reference("DEAL", deal.getId(), deal.getUpdatedAt(), null, "DEAL_REPOSITORY", null);
        evidence.input("DEAL", deal.getId(), deal.getStatus(), deal.getBuyerOrganizationId(),
                deal.getSellerOrganizationId(), deal.getUpdatedAt());

        // 2. Evidence for Milestone (if milestone-scoped)
        if (milestone != null) {
            evidence.reference("MILESTONE", milestone.getId(), milestone.getUpdatedAt(), null, "MILESTONE_REPOSITORY", null);
            evidence.input("MILESTONE", milestone.getId(), milestone.getStatus(), milestone.getAmount(), milestone.getUpdatedAt());
        }

        // 3. Query all disputes for the deal
        List<Dispute> disputes = disputeRepository.findByDealIdOrderByCreatedAtDesc(deal.getId()).stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((Dispute d) -> d.getId() != null ? d.getId().toString() : "")
                        .thenComparing(d -> d.getCreatedAt() != null ? d.getCreatedAt() : Instant.EPOCH))
                .toList();

        boolean hasActiveDealDispute = false;
        boolean hasActiveMilestoneDispute = false;
        boolean hasResolvedDispute = false;

        for (Dispute dispute : disputes) {
            if (dispute.getId() != null) {
                evidence.reference("DISPUTE", dispute.getId(),
                        dispute.getCreatedAt() != null ? dispute.getCreatedAt() : Instant.now(),
                        null, "DISPUTE_REPOSITORY", null);
            }
            evidence.input("DISPUTE", dispute.getId(), dispute.getDealId(), dispute.getMilestoneId(),
                    dispute.getRaisedBy(), dispute.getStatus(), dispute.getCreatedAt(),
                    dispute.getResolvedAt(), dispute.getResolvedBy());

            boolean isTargetDispute = (milestone == null)
                    || (dispute.getMilestoneId() == null)
                    || dispute.getMilestoneId().equals(milestone.getId());

            if (isTargetDispute) {
                if (dispute.getStatus() == DisputeStatus.OPEN || dispute.getStatus() == DisputeStatus.UNDER_REVIEW) {
                    if (dispute.getMilestoneId() == null) {
                        hasActiveDealDispute = true;
                    } else {
                        hasActiveMilestoneDispute = true;
                    }
                } else if (dispute.getStatus() == DisputeStatus.RESOLVED) {
                    hasResolvedDispute = true;
                }
                // REJECTED status is informational; it does not block or require review on its own.
            }
        }

        if (hasActiveDealDispute) {
            blocks.add("DISPUTE_ACTIVE_ON_DEAL");
        }
        if (hasActiveMilestoneDispute) {
            blocks.add("DISPUTE_ACTIVE_ON_MILESTONE");
        }
        if (hasResolvedDispute && blocks.isEmpty()) {
            reviews.add("DISPUTE_RECENTLY_RESOLVED");
        }

        List<String> reasons = new ArrayList<>(blocks);
        reasons.addAll(reviews);

        DecisionStatus status;
        String explanation;

        if (!blocks.isEmpty()) {
            status = DecisionStatus.BLOCK;
            explanation = "Active open dispute found on deal or milestone. Decision is blocked.";
        } else if (!reviews.isEmpty()) {
            status = DecisionStatus.REVIEW;
            explanation = "Historical resolved dispute found. Revalidation and review required before downstream processing.";
        } else {
            status = DecisionStatus.ALLOW;
            reasons = List.of("DISPUTE_NONE_ACTIVE");
            explanation = "No active open or unresolved disputes on deal or milestone.";
        }

        evidence.input("DISPUTE_DECISION", status.name(), String.join("|", reasons));

        return evidence.command(context, type(), status, reasons, explanation);
    }
}
