package com.finx.release.observability;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operational threshold configuration for FinX release execution observability.
 */
@Component
@ConfigurationProperties(prefix = "finx.release.observability")
public class ReleaseObservabilityProperties {

    private int outboxBacklogWarning = 50;
    private int deadLetterWarning = 1;
    private int unknownOutcomeWarning = 1;
    private long leaseStaleSeconds = 300;
    private long pendingSettlementStaleSeconds = 900;

    @PostConstruct
    public void validate() {
        if (outboxBacklogWarning < 0) {
            throw new IllegalArgumentException("outboxBacklogWarning must be non-negative: " + outboxBacklogWarning);
        }
        if (deadLetterWarning < 0) {
            throw new IllegalArgumentException("deadLetterWarning must be non-negative: " + deadLetterWarning);
        }
        if (unknownOutcomeWarning < 0) {
            throw new IllegalArgumentException("unknownOutcomeWarning must be non-negative: " + unknownOutcomeWarning);
        }
        if (leaseStaleSeconds <= 0) {
            throw new IllegalArgumentException("leaseStaleSeconds must be strictly positive: " + leaseStaleSeconds);
        }
        if (pendingSettlementStaleSeconds <= 0) {
            throw new IllegalArgumentException("pendingSettlementStaleSeconds must be strictly positive: " + pendingSettlementStaleSeconds);
        }
    }

    public int getOutboxBacklogWarning() { return outboxBacklogWarning; }
    public void setOutboxBacklogWarning(int outboxBacklogWarning) { this.outboxBacklogWarning = outboxBacklogWarning; }

    public int getDeadLetterWarning() { return deadLetterWarning; }
    public void setDeadLetterWarning(int deadLetterWarning) { this.deadLetterWarning = deadLetterWarning; }

    public int getUnknownOutcomeWarning() { return unknownOutcomeWarning; }
    public void setUnknownOutcomeWarning(int unknownOutcomeWarning) { this.unknownOutcomeWarning = unknownOutcomeWarning; }

    public long getLeaseStaleSeconds() { return leaseStaleSeconds; }
    public void setLeaseStaleSeconds(long leaseStaleSeconds) { this.leaseStaleSeconds = leaseStaleSeconds; }

    public long getPendingSettlementStaleSeconds() { return pendingSettlementStaleSeconds; }
    public void setPendingSettlementStaleSeconds(long pendingSettlementStaleSeconds) { this.pendingSettlementStaleSeconds = pendingSettlementStaleSeconds; }
}
