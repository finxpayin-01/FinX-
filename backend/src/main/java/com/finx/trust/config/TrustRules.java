package com.finx.trust.config;

import com.finx.trust.model.*;
import java.util.*;

/** Immutable, serialized configuration. Every calculation retains the exact version and rules. */
public record TrustRules(String algorithmVersion, Map<TrustRole, Map<TrustFactor, Double>> weights,
        Map<TrustEventType, Rule> events, Map<String, Double> minimumValue,
        int minimumCompletedDeals, int minimumBehaviourEvents, int minimumCounterparties,
        int positiveUnitsPerDeal, int positiveUnitsPerCounterparty, double priorWeight,
        List<Double> decay, double seriousDecayFloor, double fraudScoreCap,
        int burstEventsPerDay, int rapidMilestoneSeconds, Map<String,ConfidenceMetric> confidence) {
    public record Rule(TrustFactor factor, double score, double weight, String description) {}
    public record ConfidenceMetric(double maximumPoints, double fullSupportAt) {}

    public static TrustRules defaults() {
        var weights = new EnumMap<TrustRole, Map<TrustFactor, Double>>(TrustRole.class);
        weights.put(TrustRole.OVERALL, weights(.25,.20,.15,.15,.10,.10,.05));
        weights.put(TrustRole.BUYER, weights(.40,.10,.15,.15,.05,.10,.05));
        weights.put(TrustRole.VENDOR, weights(0,.35,.15,.15,.20,.10,.05));
        var events = new EnumMap<TrustEventType, Rule>(TrustEventType.class);
        add(events, TrustFactor.PAYMENT, 100, 1, "Payment completed", TrustEventType.PAYMENT_COMPLETED);
        add(events, TrustFactor.PAYMENT, 25, 2, "Payment deadline missed", TrustEventType.PAYMENT_DELAYED);
        add(events, TrustFactor.PAYMENT, 5, 3, "Payment failed or reversed", TrustEventType.PAYMENT_FAILED, TrustEventType.PAYMENT_REVERSED);
        add(events, TrustFactor.PAYMENT, 30, 1, "Payment cancelled", TrustEventType.PAYMENT_CANCELLED);
        add(events, TrustFactor.MILESTONE, 100, 1, "Milestone delivered or approved", TrustEventType.MILESTONE_COMPLETED, TrustEventType.MILESTONE_APPROVED);
        add(events, TrustFactor.MILESTONE, 100, 1, "Milestone submitted by its deadline", TrustEventType.MILESTONE_SUBMITTED);
        add(events, TrustFactor.MILESTONE, 25, 2, "Milestone submitted late or overdue", TrustEventType.MILESTONE_OVERDUE);
        add(events, TrustFactor.MILESTONE, 25, 1, "Milestone required revision", TrustEventType.MILESTONE_REJECTED);
        add(events, TrustFactor.MILESTONE, 5, 3, "Milestone abandoned", TrustEventType.MILESTONE_ABANDONED);
        add(events, TrustFactor.MILESTONE, 30, 2, "Approval exceeded agreed review deadline", TrustEventType.APPROVAL_DELAYED);
        add(events, TrustFactor.MILESTONE, 10, 3, "Unfair rejection confirmed by review", TrustEventType.UNFAIR_REJECTION_CONFIRMED);
        add(events, TrustFactor.DISPUTE, 100, 1, "Dispute resolved in organization's favour", TrustEventType.DISPUTE_WON);
        add(events, TrustFactor.DISPUTE, 5, 3, "Dispute lost following verified adjudication", TrustEventType.DISPUTE_LOST);
        add(events, TrustFactor.COMPLIANCE, 100, 1, "Contract fulfilled", TrustEventType.CONTRACT_FULFILLED);
        add(events, TrustFactor.COMPLIANCE, 5, 3, "Contract breach confirmed", TrustEventType.CONTRACT_BREACH);
        add(events, TrustFactor.EVIDENCE, 100, 1, "Evidence accepted or invoice verified", TrustEventType.DOCUMENT_VERIFIED, TrustEventType.INVOICE_MATCHED);
        add(events, TrustFactor.EVIDENCE, 15, 2, "Evidence rejected or invoice inconsistent", TrustEventType.DOCUMENT_REJECTED, TrustEventType.INVOICE_MISMATCHED);
        add(events, TrustFactor.EVIDENCE, 10, 2, "Duplicate or suspicious evidence confirmed", TrustEventType.DOCUMENT_DUPLICATE, TrustEventType.SUSPICIOUS_ATTACHMENT);
        add(events, TrustFactor.HISTORY, 100, 1, "Deal completed", TrustEventType.DEAL_COMPLETED);
        // Cancellation, KYB and unresolved disputes are context, not proof of fault.
        add(events, TrustFactor.NETWORK, 20, 2, "Closed or coordinated transaction pattern recorded; review context",
                TrustEventType.CIRCULAR_ACTIVITY_CONFIRMED, TrustEventType.SELF_DEALING_CONFIRMED, TrustEventType.COORDINATED_ACTIVITY_CONFIRMED);
        add(events, TrustFactor.NETWORK, 45, 1, "Supporting counterparty risk observation", TrustEventType.NETWORK_RISK_OBSERVED);
        add(events, TrustFactor.COMPLIANCE, 0, 10, "Fraud confirmed by verified investigation", TrustEventType.FRAUD_CONFIRMED);
        return new TrustRules("TRUST_V1", weights, events, Map.of("INR",1000d,"USD",10d,"USDC",10d),
                3,10,2,3,10,2, List.of(1d,.8,.6,.3,.1),.6,20,50,60,
                Map.of("deals",new ConfidenceMetric(30,20),"milestones",new ConfidenceMetric(25,50),
                    "counterparties",new ConfidenceMetric(20,10),"days",new ConfidenceMetric(10,365),
                    "observations",new ConfidenceMetric(15,100)));
    }
    private static Map<TrustFactor, Double> weights(double... values) {
        var result = new EnumMap<TrustFactor, Double>(TrustFactor.class);
        for (var f : TrustFactor.values()) result.put(f, values[f.ordinal()]);
        return result;
    }
    private static void add(Map<TrustEventType, Rule> rules, TrustFactor factor, double score,
            double weight, String description, TrustEventType... types) {
        for (var type : types) rules.put(type, new Rule(factor,score,weight,description));
    }
    public void validate() {
        if (!"TRUST_V1".equals(algorithmVersion) || weights == null || events == null || minimumValue == null
                || minimumValue.isEmpty() || minimumCompletedDeals < 1 || minimumBehaviourEvents < 1
                || minimumCounterparties < 2 || positiveUnitsPerDeal < 1 || positiveUnitsPerCounterparty < 1
                || minimumCompletedDeals > 10000 || minimumBehaviourEvents > 100000 || minimumCounterparties > 1000
                || positiveUnitsPerDeal > 100 || positiveUnitsPerCounterparty > 1000
                || !finite(priorWeight, .1, 100) || !finite(seriousDecayFloor,0,1)
                || !finite(fraudScoreCap,0,39) || burstEventsPerDay < 5 || rapidMilestoneSeconds < 0
                || decay == null || decay.size() != 5) throw new IllegalArgumentException("Invalid scoring thresholds");
        for (var role : TrustRole.values()) {
            var row = weights.get(role);
            if (row == null || row.size() != 7 || Arrays.stream(TrustFactor.values()).anyMatch(f -> !finite(row.get(f),0,1))
                    || Math.abs(row.values().stream().mapToDouble(Double::doubleValue).sum()-1) > .000001)
                throw new IllegalArgumentException("Each role must have seven nonnegative weights summing to 1");
            if (row.get(TrustFactor.NETWORK) > .05 || row.get(TrustFactor.HISTORY) > .10)
                throw new IllegalArgumentException("Network and volume cannot dominate reliability");
        }
        double previous = 1;
        if(confidence==null || !confidence.keySet().equals(Set.of("deals","milestones","counterparties","days","observations"))
                || confidence.values().stream().anyMatch(c->c==null || !finite(c.maximumPoints(),0,50) || !finite(c.fullSupportAt(),1,100000))
                || Math.abs(confidence.values().stream().mapToDouble(ConfidenceMetric::maximumPoints).sum()-100)>.000001)
            throw new IllegalArgumentException("Confidence metric points must sum to 100 with positive support thresholds");
        for (var d : decay) { if (!finite(d,0,previous)) throw new IllegalArgumentException("Decay must be decreasing in [0,1]"); previous=d; }
        minimumValue.forEach((currency, value) -> { if (!currency.matches("[A-Z]{3,10}") || !finite(value,.01,1e12)) throw new IllegalArgumentException("Invalid currency minimum"); });
        if (!events.keySet().equals(defaults().events().keySet())) throw new IllegalArgumentException("All V1 event rules are required");
        events.forEach((type,rule) -> {
            var original = defaults().events().get(type);
            if (rule == null || rule.factor()!=original.factor() || !finite(rule.score(),0,100)
                    || !finite(rule.weight(),.1,100) || rule.description()==null || rule.description().isBlank()
                    || rule.description().length()>200 || (original.score()<50 && rule.score()>=50)
                    || (original.score()>50 && rule.score()<=50)) throw new IllegalArgumentException("Invalid event rule: "+type);
        });
    }
    private static boolean finite(Double d, double min, double max) { return d!=null && Double.isFinite(d) && d>=min && d<=max; }
}
