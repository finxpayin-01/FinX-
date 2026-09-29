package com.finx.risk.config;

import com.finx.risk.dto.RiskDtos.*;
import java.math.BigDecimal;
import java.util.*;
import static com.finx.risk.dto.RiskDtos.Category.*;

public final class RiskPolicies {
    private RiskPolicies() {}
    public static Policy defaults(UserType type) {
        Map<Category, Integer> weights = type == UserType.BUYER
                ? Map.of(VERIFICATION,20, PAYMENT,25, DISPUTE,15, TRANSACTION,15, BUSINESS,10, SECURITY,10, PLATFORM,5)
                : Map.of(VERIFICATION,20, PERFORMANCE,25, DISPUTE,20, TRANSACTION,10, BUSINESS,10, DOCUMENT,10, SECURITY,5);
        List<Rule> rules = new ArrayList<>(List.of(
                rule("KYC_INCOMPLETE",VERIFICATION,"kycIncomplete",1,25,"Individual verification is incomplete"),
                rule("KYB_INCOMPLETE",VERIFICATION,"kybIncomplete",1,25,"Business verification is incomplete"),
                rule("KYC_VERIFICATION_FAILED",VERIFICATION,"kycFailed",1,70,"Individual verification failed"),
                rule("KYB_VERIFICATION_FAILED",VERIFICATION,"kybFailed",1,70,"Business verification failed"),
                rule("DOCUMENT_NAME_MISMATCH",VERIFICATION,"documentMismatch",1,50,"Verification reports a document name mismatch"),
                rule("VERIFICATION_EXPIRED",VERIFICATION,"verificationExpired",1,50,"Verification has expired"),
                rule("BANK_VERIFICATION_FAILED",VERIFICATION,"bankFailed",1,50,"Bank account verification failed or reported a mismatch"),
                rule("DISPUTE_RATIO_HIGH",DISPUTE,"disputeRatio",0.3,60,"Recent dispute-to-project ratio exceeds policy threshold"),
                rule("DISPUTE_VELOCITY_HIGH",DISPUTE,"disputeVelocity",3,30,"Multiple disputes occurred inside the configured velocity window"),
                rule("UNRELATED_COUNTERPARTY_DISPUTES",DISPUTE,"disputeCounterparties",3,30,"Disputes involve multiple distinct counterparties"),
                rule("DISPUTES_LOST",DISPUTE,"disputesLost",2,40,"Recorded adverse dispute outcomes exceed policy threshold"),
                rule("TRANSACTION_VALUE_ANOMALY",TRANSACTION,"valueRatio",3,60,"Project value exceeds the historical baseline in the same currency"),
                rule("TRANSACTION_VELOCITY_ANOMALY",TRANSACTION,"projectVelocity",5,50,"Project creation velocity exceeds policy threshold"),
                rule("HIGH_VALUE_PROJECT_VELOCITY",TRANSACTION,"highValueVelocity",3,50,"Multiple high-value projects were created in the velocity window"),
                rule("PAYMENT_VOLUME_ANOMALY",TRANSACTION,"volumeRatio",3,50,"Recent payment volume exceeds its comparable historical baseline"),
                rule("BUSINESS_PROFILE_INCOMPLETE",BUSINESS,"profileMissingRatio",0.5,40,"Business profile is missing required fields"),
                rule("YOUNG_BUSINESS",BUSINESS,"youngCompany",1,20,"Verified incorporation date falls within the observation window"),
                rule("BUSINESS_IDENTIFIERS_UNVERIFIED",BUSINESS,"identifiersUnverified",1,40,"Applicable business identifiers have not been verified"),
                rule("BANK_ACCOUNT_RECENTLY_CHANGED",SECURITY,"bankChanges",1,50,"A payout bank account was changed in the velocity window"),
                rule("BANK_ACCOUNT_CHANGE_VELOCITY",SECURITY,"bankChanges",3,50,"Repeated payout destination changes exceed policy threshold"),
                rule("AUTH_FAILURE_VELOCITY",SECURITY,"authFailures",5,60,"Repeated authentication failures exceed policy threshold"),
                rule("SUSPICIOUS_SECURITY_ACTIVITY",SECURITY,"securityEvents",2,50,"Trusted security events exceed policy threshold"),
                rule("NEW_DEVICE_HIGH_VALUE",SECURITY,"newDeviceHighValue",1,60,"A new device initiated a high-value operation")
        ));
        if (type == UserType.BUYER) rules.addAll(List.of(
                rule("PAYMENT_FAILURE_RATE_HIGH",PAYMENT,"paymentFailureRatio",0.3,60,"Failed payments exceed the configured proportion of attempts"),
                rule("PAYMENT_FAILURE_VELOCITY",PAYMENT,"paymentFailures",3,40,"Repeated payment failures occurred in the velocity window"),
                rule("PAYMENT_REVERSED",PAYMENT,"paymentReversals",1,60,"Bank payment reversals occurred in the observation window"),
                rule("FUNDING_DELAYED",PAYMENT,"delayedFundingRatio",0.5,40,"Unfunded instructions have exceeded the observation window"),
                rule("PAYMENT_FREQUENCY_HIGH",PAYMENT,"paymentVelocity",10,40,"Payment frequency exceeds policy threshold"),
                rule("MILESTONE_REJECTION_FREQUENCY",PLATFORM,"rejectionRatio",0.5,60,"Milestone rejection proportion exceeds policy threshold"),
                rule("PROJECT_CANCELLATION_FREQUENCY",PLATFORM,"cancellationRatio",0.5,50,"Project cancellations exceed policy threshold"),
                rule("VENDOR_INVITATION_VELOCITY",PLATFORM,"vendorInvitations",10,40,"Vendor invitations exceed policy threshold")
        ));
        else rules.addAll(List.of(
                rule("MILESTONE_REJECTION_RATE_HIGH",PERFORMANCE,"rejectionRatio",0.3,60,"Rejected deliverables exceed the configured proportion of submissions"),
                rule("LATE_MILESTONE_RATE_HIGH",PERFORMANCE,"lateRatio",0.3,40,"Overdue or late completed milestones exceed policy threshold"),
                rule("REPEATED_RESUBMISSIONS",PERFORMANCE,"resubmissionRatio",0.5,40,"Repeated submissions per milestone exceed policy threshold"),
                rule("LOW_PROJECT_COMPLETION",PERFORMANCE,"incompleteRatio",0.5,40,"Mature projects have not completed within the observation period"),
                rule("HIGH_MILESTONE_VALUE",TRANSACTION,"milestoneValueRatio",3,50,"Milestone value exceeds the historical same-currency baseline"),
                rule("DUPLICATE_INVOICE",DOCUMENT,"duplicateInvoices",1,70,"Invoice content duplicates an existing invoice"),
                rule("INVOICE_AMOUNT_MISMATCH",DOCUMENT,"invoiceMismatch",1,70,"Invoice amount or project information does not match"),
                rule("DOCUMENT_CHANGED",DOCUMENT,"documentChanges",3,50,"Supporting documents changed repeatedly"),
                rule("DUPLICATE_SUPPORTING_DOCUMENT",DOCUMENT,"duplicateDocuments",1,50,"Supporting evidence duplicates an existing document"),
                rule("INVOICE_VELOCITY",DOCUMENT,"invoiceVelocity",10,40,"Invoice submission velocity exceeds policy threshold")
        ));
        return new Policy(weights, rules, 90, 24, 5, new BigDecimal("5000000"), true, true, "INR");
    }

    private static Rule rule(String code, Category category, String metric, double threshold, int points, String reason) {
        return new Rule(code,category,metric,threshold,points,true,reason);
    }

    public static void validate(UserType type, Policy p) {
        Policy original = defaults(type);
        if (p == null || p.weights() == null || !p.weights().keySet().equals(original.weights().keySet())
                || p.weights().values().stream().anyMatch(v -> v == null || v < 0 || v > 100)
                || p.weights().values().stream().mapToInt(Integer::intValue).sum() != 100)
            throw new IllegalArgumentException("Category weights must be nonnegative and total exactly 100");
        if (p.windowDays() < 1 || p.windowDays() > 730 || p.velocityHours() < 1 || p.velocityHours() > 168
                || p.minimumBaselineProjects() < 2 || p.minimumBaselineProjects() > 1000
                || p.highValueAmount() == null || p.highValueAmount().signum() <= 0
                || p.currency() == null || !p.currency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Invalid observation window, baseline, amount, or currency");
        if (p.rules() == null || p.rules().size() != original.rules().size())
            throw new IllegalArgumentException("Keep the policy rule catalog intact; disable rules when needed");
        Set<String> seen = new HashSet<>();
        for (Rule r : p.rules()) {
            Rule definition = original.rules().stream().filter(d -> d.code().equals(r.code())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown rule code"));
            if (!seen.add(r.code()) || definition.category() != r.category() || !definition.metric().equals(r.metric())
                    || !Double.isFinite(r.threshold()) || r.threshold() <= 0 || r.points() < 0 || r.points() > 100
                    || r.reason() == null || r.reason().isBlank() || r.reason().length() > 500)
                throw new IllegalArgumentException("Invalid rule definition: " + r.code());
        }
    }
}
