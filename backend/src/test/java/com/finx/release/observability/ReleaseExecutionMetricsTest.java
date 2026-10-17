package com.finx.release.observability;

import com.finx.banking.readiness.BankingProviderReadiness;
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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReleaseExecutionMetricsTest {

    @Mock
    private ReleaseInstructionRepository releaseInstructionRepository;
    @Mock
    private FinancialOutboxRepository outboxRepository;
    @Mock
    private SettlementExecutionAttemptRepository attemptRepository;
    @Mock
    private SettlementObservationRepository observationRepository;
    @Mock
    private ReconciliationItemRepository reconciliationItemRepository;
    @Mock
    private BankingProviderReadinessService readinessService;
    @Mock
    private ProductionSettlementDispatchGate dispatchGate;

    private MeterRegistry meterRegistry;
    private ReleaseExecutionMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new ReleaseExecutionMetrics(
                meterRegistry,
                releaseInstructionRepository,
                outboxRepository,
                attemptRepository,
                observationRepository,
                reconciliationItemRepository,
                readinessService,
                dispatchGate
        );
        metrics.registerMetrics();
    }

    @Test
    @DisplayName("All required low-cardinality release execution metrics are registered")
    void allExpectedMetricsRegistered() {
        List<String> expectedMetrics = List.of(
                "finx.release.instructions.total",
                "finx.release.outbox.pending",
                "finx.release.outbox.retry",
                "finx.release.outbox.leased",
                "finx.release.outbox.held",
                "finx.release.outbox.dead_letter",
                "finx.release.outbox.oldest_pending_age_seconds",
                "finx.release.attempts.prepared",
                "finx.release.attempts.sending",
                "finx.release.attempts.unknown",
                "finx.release.attempts.acknowledged",
                "finx.release.pending_settlement.count",
                "finx.release.pending_settlement.oldest_age_seconds",
                "finx.release.settled.count",
                "finx.release.observations.conflict",
                "finx.release.observations.reversed",
                "finx.release.reconciliation.review",
                "finx.release.reconciliation.blocked",
                "finx.release.reconciliation.reversal_required",
                "finx.release.provider.ready_sandbox",
                "finx.release.provider.ready_production",
                "finx.release.production_gate.open"
        );

        for (String metricName : expectedMetrics) {
            assertThat(meterRegistry.find(metricName).gauge())
                    .as("Metric %s should be registered as a Gauge", metricName)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("Zero high-cardinality financial tags are attached to registered metrics")
    void noHighCardinalityTags() {
        Set<String> forbiddenTagKeys = Set.of(
                "releaseinstructionid", "dealid", "milestoneid", "organizationid", "userid",
                "accountnumber", "bankaccountid", "providersettlementid", "utr", "idempotencykey",
                "operationkey"
        );

        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String normalizedKey = tag.getKey().toLowerCase().replaceAll("[^a-z]", "");
                assertThat(forbiddenTagKeys)
                        .as("Metric %s has forbidden high-cardinality tag key: %s", meter.getId().getName(), tag.getKey())
                        .doesNotContain(normalizedKey);
            }
        }
    }

    @Test
    @DisplayName("Metric gauges return expected aggregate values without state mutation")
    void gaugeValuesReadAccurately() {
        when(releaseInstructionRepository.count()).thenReturn(42L);
        when(outboxRepository.countByStatus(FinancialOutboxStatus.PENDING)).thenReturn(5L);
        when(outboxRepository.countByStatus(FinancialOutboxStatus.HELD)).thenReturn(2L);
        when(outboxRepository.countByStatus(FinancialOutboxStatus.DEAD_LETTER)).thenReturn(0L);
        when(attemptRepository.countByStatus(SettlementExecutionAttemptStatus.UNKNOWN)).thenReturn(1L);
        when(dispatchGate.isProductionDispatchAllowed()).thenReturn(false);

        BankingProviderReadiness mockReadiness = new BankingProviderReadiness(
                "MOCK", true, true, false, false, false, Collections.emptySet(), true, false, List.of()
        );
        when(readinessService.evaluateReadiness()).thenReturn(mockReadiness);

        assertThat(meterRegistry.get("finx.release.instructions.total").gauge().value()).isEqualTo(42.0);
        assertThat(meterRegistry.get("finx.release.outbox.pending").gauge().value()).isEqualTo(5.0);
        assertThat(meterRegistry.get("finx.release.outbox.held").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("finx.release.outbox.dead_letter").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("finx.release.attempts.unknown").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("finx.release.production_gate.open").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("finx.release.provider.ready_sandbox").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("finx.release.provider.ready_production").gauge().value()).isEqualTo(0.0);
    }
}
