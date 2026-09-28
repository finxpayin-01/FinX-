package com.finx.trust.scoring;

public class TrustConfidenceCalculator {
    public double calculate(long deals, long milestones, long counterparties, long days, long observations,
            java.util.Map<String,com.finx.trust.config.TrustRules.ConfidenceMetric> config) {
        var values=java.util.Map.of("deals",deals,"milestones",milestones,"counterparties",counterparties,"days",days,"observations",observations);
        return Math.min(100,config.entrySet().stream().mapToDouble(e->e.getValue().maximumPoints()
                *Math.min(1,Math.max(0,values.get(e.getKey()))/e.getValue().fullSupportAt())).sum());
    }
}
