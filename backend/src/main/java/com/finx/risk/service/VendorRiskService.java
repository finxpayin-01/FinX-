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
 * Dedicated service for managing and evaluating Vendor risk.
 */
@Service
public class VendorRiskService {

    private final RiskAdminService adminService;
    private final RiskScoringService scoringService;
    private final BusinessRiskRepository db;

    public VendorRiskService(RiskAdminService adminService, RiskScoringService scoringService, BusinessRiskRepository db) {
        this.adminService = adminService;
        this.scoringService = scoringService;
        this.db = db;
    }

    /**
     * Retrieve paginated vendors filtered by risk criteria.
     */
    @Transactional(readOnly = true)
    public Page<Business> getVendors(Level riskLevel, Integer minScore, Integer maxScore,
                                     String verificationStatus, Instant from, Instant to,
                                     BigDecimal minTransactionVolume, Boolean openAlerts,
                                     int page, int size) {
        return adminService.businesses(UserType.VENDOR, riskLevel, minScore, maxScore,
                verificationStatus, from, to, minTransactionVolume, openAlerts, page, size);
    }

    /**
     * Recalculate risk specifically for a Vendor entity.
     */
    @Transactional
    public Assessment assessVendor(UUID vendorEntityId, String trigger, UUID actor) {
        RiskScore subject = adminService.resolveSubject(vendorEntityId);
        if (subject.userType != UserType.VENDOR) {
            throw new IllegalArgumentException("Entity " + vendorEntityId + " is not a VENDOR");
        }
        return scoringService.recalculate(subject.id, trigger, actor);
    }

    /**
     * Retrieve current risk assessment and factor scores for a vendor.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getVendorRiskBreakdown(UUID vendorEntityId) {
        RiskScore subject = adminService.resolveSubject(vendorEntityId);
        Assessment current = db.current(subject);
        return Map.of(
                "subject", adminService.business(subject),
                "assessment", current != null ? current : "No assessment recorded",
                "factorScores", current != null ? current.factorScores() : Map.of(),
                "topRiskFactors", current != null ? current.topRiskFactors() : java.util.List.of()
        );
    }
}
