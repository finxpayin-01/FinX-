package com.finx.risk.controller;

import com.finx.common.response.ApiResponse;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.service.*;
import com.finx.security.service.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping({"/api/admin/risk","/api/v1/admin/risk"})
@PreAuthorize("hasRole('ADMIN')")
public class AdminRiskController {
    private final RiskAdminService admin;
    private final RiskScoringService scoring;
    private final RiskReviewService reviews;
    private final RiskPolicyService policies;
    private final BuyerRiskService buyerService;
    private final VendorRiskService vendorService;
    private final RiskAlertService alertService;
    private final RiskHistoryService historyService;

    public AdminRiskController(RiskAdminService admin,
                               RiskScoringService scoring,
                               RiskReviewService reviews,
                               RiskPolicyService policies,
                               BuyerRiskService buyerService,
                               VendorRiskService vendorService,
                               RiskAlertService alertService,
                               RiskHistoryService historyService) {
        this.admin = admin;
        this.scoring = scoring;
        this.reviews = reviews;
        this.policies = policies;
        this.buyerService = buyerService;
        this.vendorService = vendorService;
        this.alertService = alertService;
        this.historyService = historyService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<?> dashboard() {
        return ApiResponse.success(admin.dashboard());
    }

    @GetMapping("/buyers")
    public ApiResponse<?> buyers(
            @RequestParam(required=false) Level riskLevel,
            @RequestParam(required=false) Integer minScore,
            @RequestParam(required=false) Integer maxScore,
            @RequestParam(required=false) String verificationStatus,
            @RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,
            @RequestParam(required=false) BigDecimal minTransactionVolume,
            @RequestParam(required=false) Boolean openAlerts,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return ApiResponse.success(buyerService.getBuyers(riskLevel, minScore, maxScore, verificationStatus, from, to, minTransactionVolume, openAlerts, page, size));
    }

    @GetMapping("/vendors")
    public ApiResponse<?> vendors(
            @RequestParam(required=false) Level riskLevel,
            @RequestParam(required=false) Integer minScore,
            @RequestParam(required=false) Integer maxScore,
            @RequestParam(required=false) String verificationStatus,
            @RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,
            @RequestParam(required=false) BigDecimal minTransactionVolume,
            @RequestParam(required=false) Boolean openAlerts,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return ApiResponse.success(vendorService.getVendors(riskLevel, minScore, maxScore, verificationStatus, from, to, minTransactionVolume, openAlerts, page, size));
    }

    @GetMapping("/businesses")
    public ApiResponse<?> businesses(
            @RequestParam(required=false) UserType userType,
            @RequestParam(required=false) Level riskLevel,
            @RequestParam(required=false) Integer minScore,
            @RequestParam(required=false) Integer maxScore,
            @RequestParam(required=false) String verificationStatus,
            @RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,
            @RequestParam(required=false) BigDecimal minTransactionVolume,
            @RequestParam(required=false) Boolean openAlerts,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return ApiResponse.success(admin.businesses(userType, riskLevel, minScore, maxScore, verificationStatus, from, to, minTransactionVolume, openAlerts, page, size));
    }

    @GetMapping("/{entityId}")
    public ApiResponse<?> profile(@PathVariable UUID entityId) {
        return ApiResponse.success(admin.profile(entityId));
    }

    @GetMapping("/{entityId}/history")
    public ApiResponse<?> history(@PathVariable UUID entityId, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return ApiResponse.success(historyService.getHistory(entityId, page, size));
    }

    @GetMapping("/{entityId}/factors")
    public ApiResponse<?> factors(@PathVariable UUID entityId) {
        var subject = admin.resolveSubject(entityId);
        var latest = admin.history(subject.id, 0, 1).items();
        return ApiResponse.success(latest.isEmpty() ? java.util.List.of() : latest.getFirst().topRiskFactors());
    }

    @GetMapping("/alerts")
    public ApiResponse<?> alerts(@RequestParam(defaultValue="true") boolean open, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return ApiResponse.success(alertService.getAlerts(open, page, size));
    }

    @GetMapping("/reviews")
    public ApiResponse<?> reviews(@RequestParam(required=false) ReviewStatus status, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return ApiResponse.success(admin.reviews(status, page, size));
    }

    @PostMapping("/{entityId}/recalculate")
    public ApiResponse<?> recalculate(@PathVariable UUID entityId, @AuthenticationPrincipal UserPrincipal actor) {
        var subject = admin.resolveSubject(entityId);
        return ApiResponse.success(scoring.recalculate(subject.id, "ADMIN_REQUEST", actor.getId()));
    }

    @PostMapping("/reviews/{reviewId}/decision")
    public ApiResponse<?> decision(@PathVariable UUID reviewId, @Valid @RequestBody Decision decision, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(reviews.decide(reviewId, decision, actor.getId()));
    }

    @GetMapping("/rules")
    public ApiResponse<?> rules() {
        return ApiResponse.success(admin.rules());
    }

    @PatchMapping("/rules/{ruleId}")
    public ApiResponse<?> rule(@PathVariable UUID ruleId, @Valid @RequestBody PolicyUpdate request, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(policies.update(ruleId, request, actor.getId()));
    }
}
