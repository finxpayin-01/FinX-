package com.finx.risk.service;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskSignal;
import com.finx.risk.enums.RiskEnums.RiskEntityType;
import com.finx.risk.enums.RiskEnums.RiskLevel;
import com.finx.risk.repository.RiskAssessmentRepository;
import com.finx.risk.repository.RiskSignalRepository;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RiskEngineService {

    private static final Logger log = LoggerFactory.getLogger(RiskEngineService.class);

    private final RiskAssessmentRepository assessmentRepository;
    private final RiskSignalRepository signalRepository;
    private final DealRepository dealRepository;
    private final KycProfileRepository kycRepository;
    private final KybProfileRepository kybRepository;
    private final OrganizationBankAccountRepository bankAccountRepository;
    private final DisputeRepository disputeRepository;

    public RiskEngineService(RiskAssessmentRepository assessmentRepository,
                             RiskSignalRepository signalRepository,
                             DealRepository dealRepository,
                             KycProfileRepository kycRepository,
                             KybProfileRepository kybRepository,
                             OrganizationBankAccountRepository bankAccountRepository,
                             DisputeRepository disputeRepository) {
        this.assessmentRepository = assessmentRepository;
        this.signalRepository = signalRepository;
        this.dealRepository = dealRepository;
        this.kycRepository = kycRepository;
        this.kybRepository = kybRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.disputeRepository = disputeRepository;
    }

    @Transactional
    public RiskAssessment assessDealRisk(UUID dealId) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        int score = 10; // Base score
        List<RiskSignal> signals = new ArrayList<>();

        // Check Buyer KYC
        boolean buyerKyc = kycRepository.existsByUserIdAndStatus(deal.getBuyerId(), VerificationStatus.VERIFIED);
        if (!buyerKyc) {
            score += 20;
            signals.add(new RiskSignal(null, "KYC_PENDING", RiskLevel.MEDIUM, "Buyer individual signatory KYC is pending"));
        }

        // Check Seller KYB
        UUID sellerTarget = deal.getSellerOrganizationId() != null ? deal.getSellerOrganizationId() : deal.getSellerId();
        if (sellerTarget != null) {
            boolean sellerKyb = kybRepository.existsByOrganizationIdAndStatus(sellerTarget, VerificationStatus.VERIFIED);
            if (!sellerKyb) {
                score += 30;
                signals.add(new RiskSignal(null, "KYB_PENDING", RiskLevel.HIGH, "Vendor entity KYB is not verified"));
            }

            boolean bankVerified = bankAccountRepository.findByOrganizationIdAndVerificationStatus(sellerTarget, BankVerificationStatus.VERIFIED).isPresent();
            if (!bankVerified) {
                score += 25;
                signals.add(new RiskSignal(null, "BANK_UNVERIFIED", RiskLevel.HIGH, "Vendor bank account penny drop is not verified"));
            }
        }

        // Check Amount Threshold
        if (deal.getTotalAmount() != null && deal.getTotalAmount().compareTo(BigDecimal.valueOf(5000000)) > 0) {
            score += 15;
            signals.add(new RiskSignal(null, "LARGE_TRANSACTION", RiskLevel.MEDIUM, "High value deal exceeds standard ₹50,00,000 threshold"));
        }

        RiskLevel level;
        boolean cleared;
        if (score >= 70) {
            level = RiskLevel.CRITICAL;
            cleared = false;
        } else if (score >= 45) {
            level = RiskLevel.HIGH;
            cleared = false;
        } else if (score >= 25) {
            level = RiskLevel.MEDIUM;
            cleared = true;
        } else {
            level = RiskLevel.LOW;
            cleared = true;
        }

        RiskAssessment assessment = assessmentRepository.findByEntityTypeAndEntityId(RiskEntityType.DEAL, dealId)
                .orElse(new RiskAssessment(RiskEntityType.DEAL, dealId, level, score, "", cleared));

        assessment.setRiskLevel(level);
        assessment.setRiskScore(score);
        assessment.setCleared(cleared);
        assessment.setFactorsSummary("Evaluated " + signals.size() + " risk factors. Risk score: " + score + "/100 (" + level + ")");

        RiskAssessment saved = assessmentRepository.save(assessment);

        for (RiskSignal sig : signals) {
            sig.setRiskAssessmentId(saved.getId());
            signalRepository.save(sig);
        }

        log.info("Assessed risk for deal {}: score={} level={}", dealId, score, level);
        return saved;
    }

    @Transactional(readOnly = true)
    public RiskAssessment getAssessmentForDeal(UUID dealId) {
        return assessmentRepository.findByEntityTypeAndEntityId(RiskEntityType.DEAL, dealId)
                .orElseGet(() -> assessDealRisk(dealId));
    }
}
