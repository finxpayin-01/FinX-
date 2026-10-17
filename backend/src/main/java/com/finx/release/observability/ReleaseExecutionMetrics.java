package com.finx.release.observability;

import com.finx.banking.readiness.BankingProviderReadinessService;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.release.execution.gate.ProductionSettlementDispatchGate;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Metric collector registering all operational FinX release execution gauges on the Micrometer registry.
 * <p>
 * STRICT CARDINALITY RULES:
 * - Only low-cardinality metrics are registered.
 * - ZERO entity IDs, releaseInstructionIds, dealIds, accounts, or user IDs are exposed in metric tags.
 * - Collection operations are strictly read-only: ZERO bank calls, ZERO ledger writes, ZERO state mutations.
 */
@Component
public class ReleaseExecutionMetrics {

    private final MeterRegistry meterRegistry;
    private final ReleaseInstructionRepository releaseInstructionRepository;
    private final FinancialOutboxRepository outboxRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final SettlementObservationRepository observationRepository;
    private final ReconciliationItemRepository reconciliationItemRepository;
    private final BankingProviderReadinessService readinessService;
    private final ProductionSettlementDispatchGate dispatchGate;

    public ReleaseExecutionMetrics(
            MeterRegistry meterRegistry,
            ReleaseInstructionRepository releaseInstructionRepository,
            FinancialOutboxRepository outboxRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            SettlementObservationRepository observationRepository,
            ReconciliationItemRepository reconciliationItemRepository,
            BankingProviderReadinessService readinessService,
            ProductionSettlementDispatchGate dispatchGate
    ) {
        this.meterRegistry = meterRegistry;
        this.releaseInstructionRepository = releaseInstructionRepository;
        this.outboxRepository = outboxRepository;
        this.attemptRepository = attemptRepository;
        this.observationRepository = observationRepository;
        this.reconciliationItemRepository = reconciliationItemRepository;
        this.readinessService = readinessService;
        this.dispatchGate = dispatchGate;
    }

    @PostConstruct
    public void registerMetrics() {
        // Release instructions
        Gauge.builder("finx.release.instructions.total", releaseInstructionRepository, r -> (double) r.count())
                .description("Total number of release instructions in all lifecycle states")
                .register(meterRegistry);

        // Outbox states
        Gauge.builder("finx.release.outbox.pending", outboxRepository, r -> (double) r.countByStatus(FinancialOutboxStatus.PENDING))
                .description("Outbox messages currently pending worker lease")
                .register(meterRegistry);

        Gauge.builder("finx.release.outbox.retry", outboxRepository, r -> (double) r.countByStatus(FinancialOutboxStatus.RETRY))
                .description("Outbox messages currently scheduled for retry")
                .register(meterRegistry);

        Gauge.builder("finx.release.outbox.leased", outboxRepository, r -> (double) r.countByStatus(FinancialOutboxStatus.LEASED))
                .description("Outbox messages currently leased by an active worker")
                .register(meterRegistry);

        Gauge.builder("finx.release.outbox.held", outboxRepository, r -> (double) r.countByStatus(FinancialOutboxStatus.HELD))
                .description("Outbox messages administratively held prior to dispatch")
                .register(meterRegistry);

        Gauge.builder("finx.release.outbox.dead_letter", outboxRepository, r -> (double) r.countByStatus(FinancialOutboxStatus.DEAD_LETTER))
                .description("Outbox messages in terminal dead-letter status")
                .register(meterRegistry);

        Gauge.builder("finx.release.outbox.oldest_pending_age_seconds", this, ReleaseExecutionMetrics::getOldestPendingAgeSeconds)
                .description("Age in seconds of the oldest pending outbox message")
                .register(meterRegistry);

        // Attempts
        Gauge.builder("finx.release.attempts.prepared", attemptRepository, r -> (double) r.countByStatus(SettlementExecutionAttemptStatus.PREPARED))
                .description("Settlement attempts prepared before network start")
                .register(meterRegistry);

        Gauge.builder("finx.release.attempts.sending", attemptRepository, r -> (double) r.countByStatus(SettlementExecutionAttemptStatus.SENDING))
                .description("Settlement attempts in-flight across network boundary")
                .register(meterRegistry);

        Gauge.builder("finx.release.attempts.unknown", attemptRepository, r -> (double) r.countByStatus(SettlementExecutionAttemptStatus.UNKNOWN))
                .description("Settlement attempts in unknown network outcome state")
                .register(meterRegistry);

        Gauge.builder("finx.release.attempts.acknowledged", attemptRepository, r -> (double) r.countByStatus(SettlementExecutionAttemptStatus.ACKNOWLEDGED))
                .description("Settlement attempts acknowledged by provider")
                .register(meterRegistry);

        // Pending settlement
        Gauge.builder("finx.release.pending_settlement.count", releaseInstructionRepository, r -> (double) r.countByStatus(ReleaseInstructionStatus.PENDING_SETTLEMENT))
                .description("Release instructions awaiting settlement observation")
                .register(meterRegistry);

        Gauge.builder("finx.release.pending_settlement.oldest_age_seconds", this, ReleaseExecutionMetrics::getOldestPendingSettlementAgeSeconds)
                .description("Age in seconds of the oldest pending-settlement release instruction")
                .register(meterRegistry);

        // Settled count
        Gauge.builder("finx.release.settled.count", releaseInstructionRepository, r -> (double) r.countByStatus(ReleaseInstructionStatus.SETTLED))
                .description("Release instructions settled with finality")
                .register(meterRegistry);

        // Observations
        Gauge.builder("finx.release.observations.conflict", observationRepository, r -> (double) r.countConflictingObservations())
                .description("Settlement observations exhibiting conflicting status evidence")
                .register(meterRegistry);

        Gauge.builder("finx.release.observations.reversed", observationRepository, r -> (double) r.countByFinality(SettlementObservationFinality.REVERSED))
                .description("Settlement observations indicating reversed bank settlements")
                .register(meterRegistry);

        // Reconciliation
        Gauge.builder("finx.release.reconciliation.review", reconciliationItemRepository, r -> (double) r.countByStatus(ReconciliationItemStatus.REQUIRES_REVIEW))
                .description("Reconciliation items flagged for operational review")
                .register(meterRegistry);

        Gauge.builder("finx.release.reconciliation.blocked", reconciliationItemRepository, r -> (double) r.countBlockedReconciliationItems())
                .description("Reconciliation items blocking automated closure")
                .register(meterRegistry);

        Gauge.builder("finx.release.reconciliation.reversal_required", reconciliationItemRepository, r -> (double) r.countByReasonCode(ReconciliationReasonCode.BANK_FINALITY_REVERSED))
                .description("Reconciliation items indicating bank settlement reversal")
                .register(meterRegistry);

        // Provider & Gate readiness
        Gauge.builder("finx.release.provider.ready_sandbox", readinessService, s -> s.evaluateReadiness().readyForSandbox() ? 1.0 : 0.0)
                .description("Provider readiness for sandbox execution (1=ready, 0=not ready)")
                .register(meterRegistry);

        Gauge.builder("finx.release.provider.ready_production", readinessService, s -> s.evaluateReadiness().readyForProduction() ? 1.0 : 0.0)
                .description("Provider technical readiness for production execution (1=ready, 0=not ready)")
                .register(meterRegistry);

        Gauge.builder("finx.release.production_gate.open", dispatchGate, g -> g.isProductionDispatchAllowed() ? 1.0 : 0.0)
                .description("Production settlement dispatch gate status (1=open, 0=closed)")
                .register(meterRegistry);
    }

    public double getOldestPendingAgeSeconds() {
        return outboxRepository.findOldestPendingCreatedAt()
                .map(createdAt -> Math.max(0.0, (double) Duration.between(createdAt, Instant.now()).toSeconds()))
                .orElse(0.0);
    }

    public double getOldestPendingSettlementAgeSeconds() {
        return releaseInstructionRepository.findOldestCreatedAtByStatus(ReleaseInstructionStatus.PENDING_SETTLEMENT)
                .map(createdAt -> Math.max(0.0, (double) Duration.between(createdAt, Instant.now()).toSeconds()))
                .orElse(0.0);
    }
}
