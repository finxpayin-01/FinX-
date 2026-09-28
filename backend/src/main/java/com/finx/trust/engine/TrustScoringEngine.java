package com.finx.trust.engine;

import com.finx.trust.config.TrustRules;
import com.finx.trust.dto.TrustResult;
import com.finx.trust.entity.TrustEvent;
import com.finx.trust.model.*;
import com.finx.trust.scoring.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

/** Pure deterministic calculation; caller provides the clock, ordered facts and exact rules. */
public class TrustScoringEngine {
    private final Map<TrustFactor, FactorScorer> scorers;
    private final TrustConfidenceCalculator confidence = new TrustConfidenceCalculator();
    public TrustScoringEngine() {
        scorers = new EnumMap<>(TrustFactor.class);
        for (var f : TrustFactor.values()) scorers.put(f,new BehaviourScorer());
    }
    public TrustScoringEngine(Map<TrustFactor, FactorScorer> scorers) { this.scorers=Map.copyOf(scorers); }

    private record Observation(TrustEvent event, TrustRules.Rule rule, double weight) {}

    public TrustResult calculate(UUID org, TrustRole role, Instant joined, Instant at,
            long version, TrustRules rules, Stream<TrustEvent> input) {
        var observations = new EnumMap<TrustFactor, Map<String, Observation>>(TrustFactor.class);
        for (var factor : TrustFactor.values()) observations.put(factor,new TreeMap<>());
        var stats = new TreeMap<String,Long>();
        var deals = new HashSet<UUID>(); var milestones = new HashSet<UUID>();
        var counterparties = new HashSet<UUID>(); var units = new HashSet<String>();
        var acceptedUnits = new HashSet<String>(); var acceptedMilestones=new HashSet<UUID>();
        var cpDeals = new HashMap<UUID,Set<UUID>>(); var days = new HashMap<LocalDate,Integer>();
        var disputes = new HashMap<String,TrustEventType>();
        long[] counts = {0,0,0,0}; // facts, excluded positives, fraud, fast completion
        final MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
        input.filter(e -> !e.occurredAt.isAfter(at) && !e.createdAt.isAfter(at)
                && (role==TrustRole.OVERALL || e.role==role || e.role==TrustRole.OVERALL)).forEach(e -> {
            counts[0]++;
            digest.update((e.id+"\n").getBytes(StandardCharsets.UTF_8));
            stats.merge(e.eventType.name(),1L,Long::sum);
            days.merge(e.occurredAt.atZone(ZoneOffset.UTC).toLocalDate(),1,Integer::sum);
            if (e.eventType==TrustEventType.FRAUD_CONFIRMED) counts[2]++;
            String unit = e.milestoneId!=null ? e.milestoneId.toString() : e.dealId!=null ? e.dealId.toString() : e.sourceKey;
            if (e.eventType.name().startsWith("DISPUTE_")) {
                String dispute = Objects.toString(e.metadata.get("disputeId"),unit);
                if (e.eventType!=TrustEventType.DISPUTE_CREATED || !disputes.containsKey(dispute)) disputes.put(dispute,e.eventType);
            }
            boolean meaningful = meaningful(e,rules,org);
            if (meaningful && e.eventType==TrustEventType.DEAL_COMPLETED && e.dealId!=null) {
                deals.add(e.dealId);
                cpDeals.computeIfAbsent(e.counterpartyId,k->new HashSet<>()).add(e.dealId);
                // Successful completed contracts provide actual compliance and dispute exposure.
                put(observations,unit+":contract",e,rules.events().get(TrustEventType.CONTRACT_FULFILLED),rules,at);
                var exposure=rules.events().get(TrustEventType.DISPUTE_WON);
                put(observations,unit+":exposure",e,new TrustRules.Rule(TrustFactor.DISPUTE,exposure.score(),exposure.weight(),"Completed transaction exposure"),rules,at);
            }
            var rule=rules.events().get(e.eventType);
            if (rule==null) return;
            if (rule.score()>50 && !meaningful) { counts[1]++; return; }
            if (e.eventType==TrustEventType.MILESTONE_COMPLETED && e.metadata.get("elapsedSeconds") instanceof Number n
                    && n.longValue()<rules.rapidMilestoneSeconds()) { counts[3]++; if (rule.score()>50) return; }
            if (meaningful) {
                units.add(unit); counterparties.add(e.counterpartyId);
                if (e.milestoneId!=null) milestones.add(e.milestoneId);
            }
            // No deadline means a submission is context, never evidence of being on time.
            if (e.eventType==TrustEventType.MILESTONE_SUBMITTED && !Boolean.TRUE.equals(e.metadata.get("deadlineKnown"))) return;
            put(observations,unit,e,rule,rules,at);
        });
        var factors = new EnumMap<TrustFactor,TrustResult.Component>(TrustFactor.class);
        var positives = new ArrayList<String>(); var negatives = new ArrayList<String>();
        double overall=0; long effectiveCount=0;
        for (var factor : TrustFactor.values()) {
            double total=0, weight=0; long used=0;
            var dealCaps=new HashMap<UUID,Integer>(); var cpCaps=new HashMap<UUID,Integer>();
            var descriptions=new TreeMap<String,Long>();
            var ordered=observations.get(factor).values().stream().sorted(Comparator
                    .comparing((Observation o)->o.event().occurredAt).reversed().thenComparing(o->o.event().id)).toList();
            for (var observation : ordered) {
                var e=observation.event(); var rule=observation.rule();
                if (rule.score()>50) {
                    if (dealCaps.getOrDefault(e.dealId,0)>=rules.positiveUnitsPerDeal()
                            || cpCaps.getOrDefault(e.counterpartyId,0)>=rules.positiveUnitsPerCounterparty()) { counts[1]++; continue; }
                    dealCaps.merge(e.dealId,1,Integer::sum); cpCaps.merge(e.counterpartyId,1,Integer::sum);
                }
                total+=rule.score()*observation.weight(); weight+=observation.weight(); used++;
                if(meaningful(e,rules,org)) {
                    acceptedUnits.add(e.milestoneId!=null?e.milestoneId.toString():e.dealId.toString());
                    if(e.milestoneId!=null) acceptedMilestones.add(e.milestoneId);
                }
                descriptions.merge((rule.score()<50 ? "-" : "+")+rule.description(),1L,Long::sum);
            }
            if (factor==TrustFactor.NETWORK && !cpDeals.isEmpty()) {
                double largest=cpDeals.values().stream().mapToInt(Set::size).max().orElse(0);
                double concentration=largest/Math.max(1,deals.size());
                // Concentration limits supporting evidence; no guilt-by-association veto.
                total+=(50+50*(1-concentration))*Math.min(10,deals.size()); weight+=Math.min(10,deals.size());
                if (concentration>.75 && deals.size()>=3) negatives.add("Most completed transactions involve one counterparty; diversity confidence is limited.");
            }
            double score=round(scorers.get(factor).score(total,weight,rules.priorWeight()));
            double factorWeight=rules.weights().get(role).get(factor);
            factors.put(factor,new TrustResult.Component(score,factorWeight,used,round(weight)));
            overall+=score*factorWeight; effectiveCount+=used;
            descriptions.forEach((description,count) -> {
                String text=count+" × "+description.substring(1);
                if(description.startsWith("-")) negatives.add(text); else positives.add(text);
            });
        }
        long open=disputes.values().stream().filter(t->t==TrustEventType.DISPUTE_CREATED).count();
        if (open>0) negatives.add(open+" unresolved disputes; no fault penalty applied.");
        if (counts[1]>0) negatives.add(counts[1]+" positive observations excluded by value, self-dealing or concentration safeguards.");
        if (counts[3]>0) negatives.add(counts[3]+" unusually rapid milestone completions require review.");
        if (days.values().stream().anyMatch(n->n>rules.burstEventsPerDay())) negatives.add("Unusual daily activity volume requires review; event counts do not determine reliability.");
        long diversifiedDeals=cpDeals.values().stream().mapToLong(s->Math.min(s.size(),rules.positiveUnitsPerCounterparty())).sum();
        long qualifiedUnits=Math.min(acceptedUnits.size(), counterparties.size()*(long)rules.positiveUnitsPerCounterparty());
        boolean sufficient=(diversifiedDeals>=rules.minimumCompletedDeals() || qualifiedUnits>=rules.minimumBehaviourEvents())
                && counterparties.size()>=rules.minimumCounterparties();
        double confidenceScore=round(confidence.calculate(diversifiedDeals,Math.min(acceptedMilestones.size(),qualifiedUnits),counterparties.size(),
                ChronoUnit.DAYS.between(joined,at),Math.min(effectiveCount,qualifiedUnits*3),rules.confidence()));
        if (!sufficient) overall=50;
        if (counts[2]>0) overall=Math.min(overall,rules.fraudScoreCap());
        overall=round(overall);
        var level=TrustLevel.of(overall,sufficient || counts[2]>0);
        String warning=level==TrustLevel.HIGH_RISK ? "High counterparty risk. Additional verification is recommended before proceeding."
                : level==TrustLevel.ELEVATED_RISK ? "Elevated counterparty risk detected." : null;
        stats.put("completedDeals",(long)deals.size()); stats.put("qualifiedDeals",diversifiedDeals);
        stats.put("uniqueCounterparties",(long)counterparties.size()); stats.put("qualifiedBehaviourUnits",qualifiedUnits);
        stats.put("milestones",(long)milestones.size()); stats.put("unresolvedDisputes",open);
        stats.put("excludedPositiveObservations",counts[1]); stats.put("effectiveObservations",effectiveCount);
        String explanation=level==TrustLevel.INSUFFICIENT_DATA ? "Insufficient transaction history to establish a reliable trust score."
                : "Reliability reflects recorded outcomes, weighted by role, severity and recency. Missing factors remain neutral. Confidence measures supporting history. This score provides decision support only.";
        return new TrustResult(org,role,overall,confidenceScore,level,factors,List.copyOf(positives),List.copyOf(negatives),stats,
                explanation,warning,rules.algorithmVersion(),version,counts[0],HexFormat.of().formatHex(digest.digest()),at);
    }
    private static boolean meaningful(TrustEvent e, TrustRules rules, UUID org) {
        Double minimum=rules.minimumValue().get(e.currency);
        return e.dealId!=null && e.counterpartyId!=null && !org.equals(e.counterpartyId)
                && e.eventValue!=null && minimum!=null && e.eventValue.doubleValue()>=minimum;
    }
    private static void put(Map<TrustFactor,Map<String,Observation>> all, String unit, TrustEvent event,
            TrustRules.Rule rule, TrustRules rules, Instant at) {
        long age=Math.max(0,ChronoUnit.DAYS.between(event.occurredAt,at));
        double decay=rules.decay().get(age<=90?0:age<=180?1:age<=365?2:age<=730?3:4);
        if (event.eventType==TrustEventType.FRAUD_CONFIRMED || (event.eventType==TrustEventType.CONTRACT_BREACH && event.severity>=4))
            decay=Math.max(decay,rules.seriousDecayFloor());
        var observation=new Observation(event,rule,decay*rule.weight()*(rule.score()<50 ? event.severity : 1));
        // Multiple uploads/retries for one milestone cannot mint additional positive evidence.
        all.get(rule.factor()).merge(unit,observation,(old,next) -> {
            double oldImpact=(old.rule().score()-50)*old.weight(), nextImpact=(next.rule().score()-50)*next.weight();
            return nextImpact<oldImpact ? next : old;
        });
    }
    private static double round(double value) { return Math.round(value*100)/100d; }
}
