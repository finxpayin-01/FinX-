package com.finx.risk;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.risk.config.RiskPolicies;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.engine.RiskRuleEngine;
import com.finx.risk.entity.*;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.*;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(RazorpayTestConfiguration.class)
@Transactional
class RiskScoringComprehensiveTest {

    @Autowired EntityManager em;
    @Autowired RiskRuleEngine engine;
    @Autowired RiskScoringService scoring;
    @Autowired RiskEventService events;
    @Autowired RiskAdminService admin;
    @Autowired BuyerRiskService buyerService;
    @Autowired VendorRiskService vendorService;
    @Autowired RiskAlertService alertService;
    @Autowired RiskHistoryService historyService;
    @Autowired RiskReviewService reviews;
    @Autowired RiskPolicyService policies;
    @Autowired BusinessRiskRepository db;
    @Autowired UserRepository users;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.parse("2026-09-25T12:00:00Z");

    private Evidence ev(double val) {
        return new Evidence(val, "test-evidence", now);
    }

    private User createTestUser(Role role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setName("Test " + role.name());
        u.setPasswordHash("hashed-pw");
        u.setRole(role);
        u.setStatus(com.finx.common.enums.UserStatus.ACTIVE);
        return users.save(u);
    }

    // 1. Boundary Scores (0, 20, 21, 40, 41, 60, 61, 80, 81, 100)
    @ParameterizedTest(name = "Score {0} maps to Risk Level {1}")
    @CsvSource({
            "0, LOW",
            "20, LOW",
            "21, MODERATE",
            "40, MODERATE",
            "41, ELEVATED",
            "60, ELEVATED",
            "61, HIGH",
            "80, HIGH",
            "81, CRITICAL",
            "100, CRITICAL"
    })
    @DisplayName("Boundary scores map precisely to configured Risk Levels")
    void testBoundaryScores_EveryCategoryAndScoreLevel(int score, Level expectedLevel) {
        Level actual = RiskRuleEngine.level(score);
        assertEquals(expectedLevel, actual);
        Action action = RiskRuleEngine.action(actual);
        assertNotNull(action);
        if (score <= 20) assertEquals(Action.ALLOW, action);
        else if (score <= 40) assertEquals(Action.MONITOR, action);
        else if (score <= 60) assertEquals(Action.VERIFY, action);
        else if (score <= 80) assertEquals(Action.MANUAL_REVIEW, action);
        else assertEquals(Action.TEMPORARY_HOLD, action);
    }

    // 2. New Buyer & 3. Verified Buyer
    @Test
    @DisplayName("New buyer starts with unverified status and drops to 0 when verified")
    void testNewBuyer_AndVerifiedBuyer() {
        Policy policy = RiskPolicies.defaults(UserType.BUYER);
        // New buyer with KYC and KYB incomplete
        Calculation fresh = engine.calculate(policy, Map.of(
                "kycIncomplete", ev(1),
                "kybIncomplete", ev(1)
        ), List.of());

        assertEquals(10, fresh.score());
        assertEquals(Level.LOW, fresh.level());
        assertEquals(Action.ALLOW, fresh.action());
        assertTrue(fresh.factors().stream().anyMatch(f -> f.code().equals("KYC_INCOMPLETE")));

        // Verified buyer (KYC = 0, KYB = 0)
        Calculation verified = engine.calculate(policy, Map.of(
                "kycIncomplete", ev(0),
                "kybIncomplete", ev(0)
        ), fresh.factors());

        assertEquals(0, verified.score());
        assertEquals(Level.LOW, verified.level());
        assertEquals(-10, verified.factors().stream().mapToDouble(Factor::change).sum(), 0.001);
    }

    // 4. New Vendor & Verified Vendor
    @Test
    @DisplayName("New vendor starts with unverified status and drops to 0 when verified")
    void testNewVendor_AndVerifiedVendor() {
        Policy policy = RiskPolicies.defaults(UserType.VENDOR);
        Calculation fresh = engine.calculate(policy, Map.of(
                "kycIncomplete", ev(1),
                "kybIncomplete", ev(1)
        ), List.of());

        assertEquals(10, fresh.score());
        assertEquals(Level.LOW, fresh.level());

        Calculation verified = engine.calculate(policy, Map.of(
                "kycIncomplete", ev(0),
                "kybIncomplete", ev(0)
        ), fresh.factors());

        assertEquals(0, verified.score());
        assertEquals(-10, verified.factors().stream().mapToDouble(Factor::change).sum(), 0.001);
    }

    // 5. Failed Payment
    @Test
    @DisplayName("Buyer failed payments trigger PAYMENT_FAILURE_RATE_HIGH and elevate score")
    void testBuyer_FailedPaymentIncreasesRisk() {
        Policy policy = RiskPolicies.defaults(UserType.BUYER);
        Calculation res = engine.calculate(policy, Map.of(
                "paymentFailureRatio", ev(0.6),
                "paymentFailures", ev(4)
        ), List.of());

        assertTrue(res.score() >= 25);
        assertTrue(res.factors().stream().anyMatch(f -> f.code().equals("PAYMENT_FAILURE_RATE_HIGH")));
        assertTrue(res.factors().stream().anyMatch(f -> f.code().equals("PAYMENT_FAILURE_VELOCITY")));
    }

    // 6. High Dispute Ratio
    @Test
    @DisplayName("High dispute ratio triggers DISPUTE_RATIO_HIGH with explainable reason")
    void testHighDisputeRatioIncreasesRisk() {
        Policy policy = RiskPolicies.defaults(UserType.VENDOR);
        Calculation res = engine.calculate(policy, Map.of(
                "disputeRatio", ev(0.5),
                "disputeVelocity", ev(3)
        ), List.of());

        assertTrue(res.score() > 0);
        assertTrue(res.factors().stream().anyMatch(f -> f.code().equals("DISPUTE_RATIO_HIGH")));
        Factor factor = res.factors().stream().filter(f -> f.code().equals("DISPUTE_RATIO_HIGH")).findFirst().orElseThrow();
        assertTrue(factor.impact() > 0);
        assertNotNull(factor.reason());
        assertFalse(factor.reason().isBlank());
    }

    // 7. Bank Account Change Shortly Before Payout
    @Test
    @DisplayName("Bank account change triggers BANK_ACCOUNT_RECENTLY_CHANGED")
    void testVendor_BankAccountChangeIncreasesRisk() {
        Policy policy = RiskPolicies.defaults(UserType.VENDOR);
        Calculation res = engine.calculate(policy, Map.of(
                "bankChanges", ev(1)
        ), List.of());

        assertTrue(res.factors().stream().anyMatch(factor -> factor.code().equals("BANK_ACCOUNT_RECENTLY_CHANGED")));
        Factor factor = res.factors().stream().filter(item -> item.code().equals("BANK_ACCOUNT_RECENTLY_CHANGED")).findFirst().orElseThrow();
        assertEquals(Category.SECURITY, factor.category());
        assertTrue(factor.impact() > 0);
    }

    // 8. Large Transaction Spike
    @Test
    @DisplayName("Large transaction value spike triggers TRANSACTION_VALUE_ANOMALY")
    void testBuyer_LargeTransactionSpikeIncreasesRisk() {
        Policy policy = RiskPolicies.defaults(UserType.BUYER);
        Calculation res = engine.calculate(policy, Map.of(
                "valueRatio", ev(5.0),
                "projectVelocity", ev(6)
        ), List.of());

        assertTrue(res.factors().stream().anyMatch(f -> f.code().equals("TRANSACTION_VALUE_ANOMALY")));
        assertTrue(res.factors().stream().anyMatch(f -> f.code().equals("TRANSACTION_VELOCITY_ANOMALY")));
    }

    // 9. Milestone Rejection Pattern
    @Test
    @DisplayName("Milestone rejection pattern triggers MILESTONE_REJECTION_RATE_HIGH for vendor")
    void testVendor_MilestoneRejectionPatternIncreasesRisk() {
        Policy policy = RiskPolicies.defaults(UserType.VENDOR);
        Calculation res = engine.calculate(policy, Map.of(
                "rejectionRatio", ev(0.5),
                "resubmissionRatio", ev(0.6)
        ), List.of());

        assertTrue(res.factors().stream().anyMatch(f -> f.code().equals("MILESTONE_REJECTION_RATE_HIGH")));
        assertTrue(res.factors().stream().anyMatch(f -> f.code().equals("REPEATED_RESUBMISSIONS")));
    }

    // 10. KYC Failure & 11. KYB Failure
    @Test
    @DisplayName("Verification failures trigger KYC_VERIFICATION_FAILED and KYB_VERIFICATION_FAILED")
    void testVerificationFailures() {
        Policy buyerPolicy = RiskPolicies.defaults(UserType.BUYER);
        Calculation kycRes = engine.calculate(buyerPolicy, Map.of("kycFailed", ev(1)), List.of());
        assertTrue(kycRes.factors().stream().anyMatch(f -> f.code().equals("KYC_VERIFICATION_FAILED")));

        Policy vendorPolicy = RiskPolicies.defaults(UserType.VENDOR);
        Calculation kybRes = engine.calculate(vendorPolicy, Map.of("kybFailed", ev(1)), List.of());
        assertTrue(kybRes.factors().stream().anyMatch(f -> f.code().equals("KYB_VERIFICATION_FAILED")));
    }

    // 12. Risk decrease after successful history
    @Test
    @DisplayName("Risk score decreases after successful history, recording negative change")
    void testRiskDecreaseAfterSuccessfulHistory() {
        Policy policy = RiskPolicies.defaults(UserType.BUYER);
        Calculation highRisk = engine.calculate(policy, Map.of(
                "paymentFailureRatio", ev(0.8),
                "disputeRatio", ev(0.5)
        ), List.of());
        assertTrue(highRisk.score() > 20);

        // Good performance returns metrics to normal
        Calculation resolved = engine.calculate(policy, Map.of(
                "paymentFailureRatio", ev(0.0),
                "disputeRatio", ev(0.0)
        ), highRisk.factors());

        assertEquals(0, resolved.score());
        double totalChange = resolved.factors().stream().mapToDouble(Factor::change).sum();
        assertTrue(totalChange < 0, "Score change must be negative on improvement");
    }

    // 13. Manual Review Workflow (OPEN, IN_REVIEW, RESTRICTED, CLEARED, ESCALATED)
    @Test
    @DisplayName("Manual review workflow progresses through OPEN, IN_REVIEW, RESTRICTED, CLEARED with audit logging")
    void testManualReviewWorkflow_OpenInReviewClearedRestrictedEscalated() {
        User buyer = createTestUser(Role.BUYER, "review-buyer-" + UUID.randomUUID() + "@finx.com");
        User adminUser = createTestUser(Role.ADMIN, "admin-reviewer-" + UUID.randomUUID() + "@finx.com");
        RiskScore subject = events.subject(buyer.getId(), null, UserType.BUYER);

        // Force critical score to trigger manual review
        Policy original = RiskPolicies.defaults(UserType.BUYER);
        Map<Category, Integer> weights = new EnumMap<>(original.weights());
        weights.replaceAll((k, v) -> k == Category.VERIFICATION ? 100 : 0);
        var rulesList = original.rules().stream().map(r -> r.code().equals("KYC_INCOMPLETE") || r.code().equals("KYB_INCOMPLETE")
                ? new Rule(r.code(), r.category(), r.metric(), r.threshold(), 50, true, r.reason()) : r).toList();
        Policy calibrated = new Policy(weights, rulesList, 90, 24, 5, BigDecimal.valueOf(100000), true, true, "INR");
        RiskRule rule = policies.rule(UserType.BUYER);
        policies.update(rule.id, new PolicyUpdate(rule.version, calibrated, "Calibration"), adminUser.getId());

        Assessment assessment = scoring.recalculate(subject.id, "TEST_CRITICAL", adminUser.getId());
        assertEquals(100, assessment.totalRiskScore());
        assertTrue(assessment.reviewRequired());

        // Review must be in OPEN state
        RiskReview review = db.query(RiskReview.class, "from RiskReview where subjectId=:id", Map.of("id", subject.id), 1).getFirst();
        assertEquals(ReviewStatus.OPEN, review.status);

        // Transition to IN_REVIEW
        reviews.decide(review.id, new Decision(ReviewStatus.IN_REVIEW, "Investigating evidence", "Notes", List.of("ref-1"), Action.VERIFY, null, review.version), adminUser.getId());
        assertEquals(ReviewStatus.IN_REVIEW, review.status);

        // Transition to RESTRICTED
        Instant expiry = Instant.now().plusSeconds(7200);
        reviews.decide(review.id, new Decision(ReviewStatus.RESTRICTED, "Temporary verification hold", "Hold payout", List.of(), Action.TEMPORARY_HOLD, expiry, review.version), adminUser.getId());
        assertEquals(ReviewStatus.RESTRICTED, review.status);
        assertEquals(expiry, review.restrictionUntil);

        // Transition to CLEARED
        reviews.decide(review.id, new Decision(ReviewStatus.CLEARED, "Verification verified by compliance", "All clear", List.of(), Action.ALLOW, null, review.version), adminUser.getId());
        assertEquals(ReviewStatus.CLEARED, review.status);

        // Alert must be cleared
        RiskAlert alert = db.get(RiskAlert.class, review.alertId);
        assertNotNull(alert.clearedAt);

        // Verify audit log has the actions
        em.flush();
        int auditCount = jdbc.queryForObject("select count(*) from audit_logs where action='RISK_REVIEW_DECISION' and entity_id=?",
                Integer.class, review.id.toString());
        assertEquals(3, auditCount);
    }

    // 14. Rule Configuration Changes
    @Test
    @DisplayName("Admin rule configuration changes dynamically update scoring calculation")
    void testRuleConfigurationChanges_DynamicallyAffectsScoring() {
        Policy initial = RiskPolicies.defaults(UserType.BUYER);
        Map<Category, Integer> modifiedWeights = new EnumMap<>(initial.weights());
        modifiedWeights.put(Category.PAYMENT, 45);
        modifiedWeights.put(Category.VERIFICATION, 10);
        modifiedWeights.put(Category.PLATFORM, 0);
        modifiedWeights.put(Category.BUSINESS, 5);

        Policy updatedPolicy = new Policy(modifiedWeights, initial.rules(), 90, 24, 5, initial.highValueAmount(), true, true, "INR");
        RiskPolicies.validate(UserType.BUYER, updatedPolicy);

        Calculation before = engine.calculate(initial, Map.of("paymentFailureRatio", ev(1.0)), List.of());
        Calculation after = engine.calculate(updatedPolicy, Map.of("paymentFailureRatio", ev(1.0)), before.factors());

        assertEquals(15, before.score());
        assertEquals(27, after.score());
        assertEquals(12, after.factors().getFirst().change());
    }

    // 15. Service Layer Integration (BuyerRiskService, VendorRiskService, RiskAlertService, RiskHistoryService)
    @Test
    @DisplayName("Dedicated Buyer, Vendor, Alert, and History services query and manage risk correctly")
    void testDedicatedRiskServices() {
        User buyer = createTestUser(Role.BUYER, "buyer-svc-" + UUID.randomUUID() + "@finx.com");
        User vendor = createTestUser(Role.SELLER, "vendor-svc-" + UUID.randomUUID() + "@finx.com");

        RiskScore buyerScore = events.subject(buyer.getId(), null, UserType.BUYER);
        RiskScore vendorScore = events.subject(vendor.getId(), null, UserType.VENDOR);

        scoring.recalculate(buyerScore.id, "TEST", null);
        scoring.recalculate(vendorScore.id, "TEST", null);

        // Buyer service queries
        var buyersPage = buyerService.getBuyers(null, null, null, null, null, null, null, null, 0, 10);
        assertNotNull(buyersPage);
        assertTrue(buyersPage.total() >= 1);

        var buyerBreakdown = buyerService.getBuyerRiskBreakdown(buyer.getId());
        assertNotNull(buyerBreakdown.get("assessment"));

        // Vendor service queries
        var vendorsPage = vendorService.getVendors(null, null, null, null, null, null, null, null, 0, 10);
        assertNotNull(vendorsPage);
        assertTrue(vendorsPage.total() >= 1);

        // History service queries
        var history = historyService.getHistory(buyer.getId(), 0, 10);
        assertNotNull(history);
        assertTrue(history.total() >= 1);

        var latest = historyService.getLatestAssessment(vendor.getId());
        assertNotNull(latest);
        assertEquals(UserType.VENDOR, latest.userType());
    }
}
