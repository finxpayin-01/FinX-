package com.finx.risk.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class RiskDtos {
    private RiskDtos() {}
    public enum UserType { BUYER, VENDOR }
    public enum Level { LOW, MODERATE, ELEVATED, HIGH, CRITICAL }
    public enum Action { ALLOW, MONITOR, VERIFY, MANUAL_REVIEW, TEMPORARY_HOLD }
    public enum ReviewStatus { OPEN, IN_REVIEW, CLEARED, RESTRICTED, ESCALATED }
    public enum Category { VERIFICATION, PAYMENT, DISPUTE, TRANSACTION, BUSINESS, SECURITY, PLATFORM, PERFORMANCE, DOCUMENT }
    public enum EventType {
        KYC_UPDATED, KYB_UPDATED, KYC_FAILED, KYB_FAILED, BANK_ACCOUNT_CHANGED,
        PROJECT_CREATED, PROJECT_CANCELLED, PAYMENT_INITIATED, PAYMENT_FAILED, PAYMENT_REVERSED,
        PAYMENT_SUCCEEDED, MILESTONE_SUBMITTED, MILESTONE_APPROVED, MILESTONE_REJECTED,
        DISPUTE_CREATED, DISPUTE_RESOLVED, INVOICE_UPLOADED, PROFILE_UPDATED, SECURITY_EVENT,
        POLICY_CHANGED, SCHEDULED_REFRESH, REGISTERED
    }
    public record Rule(String code, Category category, String metric, double threshold,
                       int points, boolean enabled, String reason) {}
    public record Policy(Map<Category, Integer> weights, List<Rule> rules, int windowDays,
                         int velocityHours, int minimumBaselineProjects, BigDecimal highValueAmount,
                         boolean reviewHighValuePayouts, boolean holdCriticalPayouts, String currency) {}
    public record Evidence(double value, String reference, Instant occurredAt) {}
    public record Factor(String code, Category category, double observedValue, double threshold,
                         double categoryPoints, double impact, double change, String reason,
                         String evidenceReference, Instant occurredAt) {}
    public record Calculation(int score, Level level, Action action, Map<Category, Double> factorScores,
                              List<Factor> factors, List<String> unavailableSignals) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Assessment(UUID id, UUID entityId, UUID userId, UUID organizationId, UserType userType,
                             int totalRiskScore, Level riskLevel, Integer previousScore, int scoreChange,
                             Map<Category, Double> factorScores, List<Factor> topRiskFactors,
                             List<String> triggeredRules, List<String> reasonCodes, List<String> humanReadableReasons,
                             Instant calculatedAt, String ruleVersion, boolean reviewRequired, ReviewStatus reviewStatus,
                             Action action, String trigger, List<String> unavailableSignals, double roundingAdjustment) {
        public UserType entityType() { return userType; }
        public int riskScore() { return totalRiskScore; }
    }
    public record Decision(@NotNull ReviewStatus decision, @NotBlank @Size(max=2000) String reason,
                           @Size(max=4000) String notes, @Size(max=20) List<@Size(max=255) String> evidenceReferences,
                           @NotNull Action action, Instant restrictionUntil, @Min(0) long expectedVersion) {}
    public record PolicyUpdate(@Min(1) long expectedVersion, @NotNull Policy policy,
                               @NotBlank @Size(max=2000) String reason) {}
}
