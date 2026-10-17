package com.finx.release.observability;

import com.finx.banking.readiness.BankingProviderReadinessService;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.release.execution.gate.ProductionSettlementDispatchGate;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Health indicator monitoring critical operational invariants of the FinX release execution platform.
 * <p>
 * STRICT PRIVACY RULE:
 * Details report aggregate operational counts only. ZERO account numbers, entity IDs, or secrets are exposed.
 */
@Component
public class ReleaseExecutionHealthIndicator implements HealthIndicator {

    public static final Status STATUS_DEGRADED = new Status("DEGRADED");

    private final ReleaseObservabilityProperties properties;
    private final FinancialOutboxRepository outboxRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final ReleaseInstructionRepository releaseInstructionRepository;
    private final ReconciliationItemRepository reconciliationItemRepository;
    private final BankingProviderReadinessService readinessService;
    private final ProductionSettlementDispatchGate dispatchGate;

    public ReleaseExecutionHealthIndicator(
            ReleaseObservabilityProperties properties,
            FinancialOutboxRepository outboxRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            ReleaseInstructionRepository releaseInstructionRepository,
            ReconciliationItemRepository reconciliationItemRepository,
            BankingProviderReadinessService readinessService,
            ProductionSettlementDispatchGate dispatchGate
    ) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.attemptRepository = attemptRepository;
        this.releaseInstructionRepository = releaseInstructionRepository;
        this.reconciliationItemRepository = reconciliationItemRepository;
        this.readinessService = readinessService;
        this.dispatchGate = dispatchGate;
    }

    @Override
    public Health health() {
        Instant now = Instant.now();

        long deadLetterCount = outboxRepository.countByStatus(FinancialOutboxStatus.DEAD_LETTER);
        long unknownOutcomeCount = attemptRepository.countByStatus(SettlementExecutionAttemptStatus.UNKNOWN);
        long staleLeasedCount = outboxRepository.countStaleLeasedMessages(now.minusSeconds(properties.getLeaseStaleSeconds()));
        long stalePendingCount = releaseInstructionRepository.findStalePendingSettlement(now.minusSeconds(properties.getPendingSettlementStaleSeconds())).size();
        long blockedReconCount = reconciliationItemRepository.countBlockedReconciliationItems();
        long reversalRequiredCount = reconciliationItemRepository.countByReasonCode(ReconciliationReasonCode.BANK_FINALITY_REVERSED);

        boolean readyForSandbox = readinessService.evaluateReadiness().readyForSandbox();
        boolean productionGateOpen = dispatchGate.isProductionDispatchAllowed();

        boolean hasDegradedConditions = deadLetterCount >= properties.getDeadLetterWarning()
                || unknownOutcomeCount >= properties.getUnknownOutcomeWarning()
                || staleLeasedCount > 0
                || stalePendingCount > 0
                || reversalRequiredCount > 0;

        Health.Builder builder = hasDegradedConditions ? Health.status(STATUS_DEGRADED) : Health.up();

        return builder
                .withDetail("outboxDeadLetterCount", deadLetterCount)
                .withDetail("unknownOutcomeCount", unknownOutcomeCount)
                .withDetail("staleLeasedOutboxCount", staleLeasedCount)
                .withDetail("stalePendingSettlementCount", stalePendingCount)
                .withDetail("blockedReconciliationCount", blockedReconCount)
                .withDetail("reversalRequiredCount", reversalRequiredCount)
                .withDetail("providerSandboxReady", readyForSandbox)
                .withDetail("productionGateOpen", productionGateOpen)
                .build();
    }
}
