package com.finx.risk.service;

import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskAdminService.Business;
import com.finx.risk.service.RiskAdminService.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated service for managing and evaluating Buyer risk.
 */
@Service
public class BuyerRiskService {

    private final RiskAdminService adminService;
    private final RiskScoringService scoringService;
    private final BusinessRiskRepository db;

    public BuyerRiskService(RiskAdminService adminService, RiskScoringService scoringService, BusinessRiskRepository db) {
        this.adminService = adminService;
        this.scoringService = scoringService;
        this.db = db;
    }

    /**
     * Retrieve paginated buyers filtered by risk criteria.
     */
    @Transactional(readOnly = true)
    public Page<Business> getBuyers(Level riskLevel, Integer minScore, Integer maxScore,
                                    String verificationStatus, Instant from, Instant to,
                                    BigDecimal minTransactionVolume, Boolean openAlerts,
                                    int page, int size) {
        return adminService.businesses(UserType.BUYER, riskLevel, minScore, maxScore,
                verificationStatus, from, to, minTransactionVolume, openAlerts, page, size);
    }

    /**
     * Recalculate risk specifically for a Buyer entity.
     */
    @Transactional
    public Assessment assessBuyer(UUID buyerEntityId, String trigger, UUID actor) {
        RiskScore subject = adminService.resolveSubject(buyerEntityId);
        if (subject.userType != UserType.BUYER) {
            throw new IllegalArgumentException("Entity " + buyerEntityId + " is not a BUYER");
        }
        return scoringService.recalculate(subject.id, trigger, actor);
    }

    /**
     * Retrieve current risk assessment and factor scores for a buyer.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getBuyerRiskBreakdown(UUID buyerEntityId) {
        RiskScore subject = adminService.resolveSubject(buyerEntityId);
        Assessment current = db.current(subject);
        return Map.of(
                "subject", adminService.business(subject),
                "assessment", current != null ? current : "No assessment recorded",
                "factorScores", current != null ? current.factorScores() : Map.of(),
                "topRiskFactors", current != null ? current.topRiskFactors() : java.util.List.of()
        );
    }
}
