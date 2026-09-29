package com.finx.risk.engine;

import com.finx.risk.dto.RiskDtos.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RiskRuleEngine {
    public static Level level(int score) {
        if (score < 0 || score > 100) throw new IllegalArgumentException("Score must be 0-100");
        return score <= 20 ? Level.LOW : score <= 40 ? Level.MODERATE : score <= 60 ? Level.ELEVATED
                : score <= 80 ? Level.HIGH : Level.CRITICAL;
    }

    public static Action action(Level level) {
        return switch (level) {
            case LOW -> Action.ALLOW;
            case MODERATE -> Action.MONITOR;
            case ELEVATED -> Action.VERIFY;
            case HIGH -> Action.MANUAL_REVIEW;
            case CRITICAL -> Action.TEMPORARY_HOLD;
        };
    }

    public Calculation calculate(Policy policy, Map<String,Evidence> evidence, List<Factor> previous) {
        Map<Category,Double> raw = new EnumMap<>(Category.class);
        Map<Category,Double> scores = new EnumMap<>(Category.class);
        List<Rule> triggered = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Rule r : policy.rules()) {
            if (!r.enabled()) continue;
            Evidence e = evidence.get(r.metric());
            if (e == null) { unavailable.add(r.metric()); continue; }
            if (!Double.isFinite(e.value()) || e.value() < 0) throw new IllegalArgumentException("Invalid risk evidence");
            if (e.value() >= r.threshold()) {
                triggered.add(r);
                raw.merge(r.category(), (double) r.points(), Double::sum);
            }
        }
        policy.weights().keySet().forEach(c -> scores.put(c,Math.min(100,raw.getOrDefault(c,0d))));
        double total = scores.entrySet().stream().mapToDouble(e -> e.getValue()*policy.weights().get(e.getKey())/100d).sum();
        int score = Math.max(0, Math.min(100,(int)Math.round(total)));
        Map<String,Factor> old = new HashMap<>();
        previous.forEach(f -> old.put(f.code(),f));
        List<Factor> factors = new ArrayList<>();
        for (Rule r : triggered) {
            Evidence e = evidence.get(r.metric());
            // Proportionally allocate a capped category so factor impacts add to the weighted score.
            double points = raw.get(r.category()) == 0 ? 0 : r.points()*scores.get(r.category())/raw.get(r.category());
            double impact = points*policy.weights().get(r.category())/100d;
            Factor before = old.remove(r.code());
            factors.add(new Factor(r.code(),r.category(),e.value(),r.threshold(),points,impact,
                    impact-(before == null ? 0 : before.impact()),r.reason(),e.reference(),e.occurredAt()));
        }
        old.values().stream().filter(f -> f.impact() > 0).forEach(f -> factors.add(new Factor(f.code(),f.category(),
                0,f.threshold(),0,0,-f.impact(),"No longer contributes: " + f.reason(),f.evidenceReference(),f.occurredAt())));
        factors.sort(Comparator.comparingDouble((Factor f) -> Math.abs(f.change())).reversed()
                .thenComparing(Factor::code));
        return new Calculation(score,level(score),action(level(score)),scores,factors,unavailable.stream().distinct().sorted().toList());
    }
}
