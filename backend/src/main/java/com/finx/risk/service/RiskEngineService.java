package com.finx.risk.service;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.dto.RiskEvidenceDtos.*;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskSignal;
import com.finx.risk.enums.RiskEnums.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service managing deal risk calculations and compatibility inspection views.
 *
 * Explicit recalculation (mutating): assessDealRisk(dealId)
 * Stable read-only inspection (non-mutating): getAssessmentForDeal(dealId)
 */
@Service
public class RiskEngineService {

    private final DealRepository deals;
    private final RiskEventService subjects;
    private final RiskScoringService scoring;
    private final RiskEvidenceReadService evidenceReadService;

    public RiskEngineService(DealRepository deals,
                             RiskEventService subjects,
                             RiskScoringService scoring,
                             RiskEvidenceReadService evidenceReadService) {
        this.deals = deals;
        this.subjects = subjects;
        this.scoring = scoring;
        this.evidenceReadService = evidenceReadService;
    }

    /**
     * Explicit mutating command to recalculate deal risk.
     * Appends history, updates subject scores, emits risk events, and opens reviews if required.
     */
    @Transactional
    public RiskAssessment assessDealRisk(UUID dealId) {
        Deal deal = deals.findById(dealId).orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        List<Assessment> assessments = new ArrayList<>();
        assessments.add(scoring.recalculate(subjects.subject(deal.getBuyerId(), deal.getBuyerOrganizationId(), UserType.BUYER).id, "DEAL_INSPECTION", null));
        if (deal.getSellerId() != null) {
            assessments.add(scoring.recalculate(subjects.subject(deal.getSellerId(), deal.getSellerOrganizationId(), UserType.VENDOR).id, "DEAL_INSPECTION", null));
        }
        Assessment highest = assessments.stream().max(Comparator.comparingInt(Assessment::totalRiskScore)).orElseThrow();
        RiskAssessment view = new RiskAssessment(
                RiskEntityType.DEAL,
                dealId,
                RiskLevel.valueOf(highest.riskLevel().name()),
                highest.totalRiskScore(),
                "Maximum of the current buyer and vendor business scores. Payouts follow configured review policy.",
                !highest.reviewRequired()
        );
        view.isTransient = true;
        view.ruleVersion = highest.ruleVersion();
        view.setSignals(assessments.stream().flatMap(a -> a.topRiskFactors().stream().filter(f -> f.impact() > 0)
                .map(f -> new RiskSignal(view.getId(), f.code(), RiskLevel.valueOf(a.riskLevel().name()), a.userType() + ": " + f.reason() + " (" + f.impact() + " points)"))).toList());
        return view;
    }

    /**
     * Pure read-only deal assessment inspection view.
     * Guarantees 0 writes, 0 history rows, 0 reviews, 0 score updates, and 0 events.
     */
    @Transactional(readOnly = true)
    public RiskAssessment getAssessmentForDeal(UUID dealId) {
        Deal deal = deals.findById(dealId).orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);
        return toCompatibilityView(deal, snapshot);
    }

    private RiskAssessment toCompatibilityView(Deal deal, DealRiskEvidenceSnapshot snapshot) {
        SubjectRiskEvidence buyer = snapshot.buyerEvidence();
        SubjectRiskEvidence vendor = snapshot.vendorEvidence();

        SubjectRiskEvidence highest = null;
        if (buyer.score() != null && vendor.score() != null) {
            highest = vendor.score() > buyer.score() ? vendor : buyer;
        } else if (buyer.score() != null) {
            highest = buyer;
        } else if (vendor.score() != null) {
            highest = vendor;
        }

        int score = highest != null && highest.score() != null ? highest.score() : 0;
        RiskLevel level = highest != null && highest.riskLevel() != null
                ? RiskLevel.valueOf(highest.riskLevel().name())
                : RiskLevel.LOW;
        boolean isCleared = !snapshot.reviewRequired();

        RiskAssessment view = new RiskAssessment(
                RiskEntityType.DEAL,
                deal.getId(),
                level,
                score,
                highest != null
                        ? "Maximum of the current buyer and vendor business scores. Payouts follow configured review policy."
                        : "No business risk assessment available for deal counterparties. Evidence is unresolved.",
                isCleared
        );
        view.isTransient = true;
        view.ruleVersion = highest != null ? highest.ruleVersion() : null;
        view.reviewRequired = snapshot.reviewRequired();
        view.reviewStatus = snapshot.reviewRequired() ? "OPEN" : "CLEARED";

        List<RiskSignal> signals = new ArrayList<>();
        if (buyer.persistedAssessmentId() != null) {
            for (String reason : buyer.humanReadableReasons()) {
                signals.add(new RiskSignal(view.getId(), "BUYER_RISK",
                        buyer.riskLevel() != null ? RiskLevel.valueOf(buyer.riskLevel().name()) : RiskLevel.LOW,
                        "BUYER: " + reason));
            }
        }
        if (vendor.persistedAssessmentId() != null) {
            for (String reason : vendor.humanReadableReasons()) {
                signals.add(new RiskSignal(view.getId(), "VENDOR_RISK",
                        vendor.riskLevel() != null ? RiskLevel.valueOf(vendor.riskLevel().name()) : RiskLevel.LOW,
                        "VENDOR: " + reason));
            }
        }
        view.setSignals(signals);
        return view;
    }
}
