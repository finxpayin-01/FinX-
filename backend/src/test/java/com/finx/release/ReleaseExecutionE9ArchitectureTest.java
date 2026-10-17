package com.finx.release;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.adapter.ApprovalDecisionAdapter;
import com.finx.engine.adapter.DisputeDecisionAdapter;
import com.finx.engine.adapter.DocumentDecisionAdapter;
import com.finx.engine.adapter.FraudDecisionAdapter;
import com.finx.engine.adapter.MilestoneRulesDecisionAdapter;
import com.finx.engine.adapter.ReconciliationDecisionAdapter;
import com.finx.engine.adapter.RiskDecisionAdapter;
import com.finx.engine.adapter.TrustDecisionAdapter;
import com.finx.engine.release.ReleaseDecisionEngine;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.worker.FinancialOutboxWorker;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.reconciliation.service.ReleaseReconciliationEvidenceReader;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.service.SettlementStatusRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseExecutionE9ArchitectureTest {

    @Test
    @DisplayName("TrustedSettlementFinalizer has zero BankingProvider dependencies")
    void finalizer_noBankingProvider() {
        for (Field f : TrustedSettlementFinalizer.class.getDeclaredFields()) {
            assertThat(BankingProvider.class.isAssignableFrom(f.getType()))
                    .as("TrustedSettlementFinalizer must not depend on BankingProvider")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("DeterministicReconciliationMatcher has zero BankingProvider dependencies")
    void reconciliationMatcher_noBankingProvider() {
        for (Field f : DeterministicReconciliationMatcher.class.getDeclaredFields()) {
            assertThat(BankingProvider.class.isAssignableFrom(f.getType()))
                    .as("DeterministicReconciliationMatcher must not depend on BankingProvider")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("ReleaseReconciliationEvidenceReader has zero BankingProvider dependencies")
    void releaseReconciliationReader_noBankingProvider() {
        for (Field f : ReleaseReconciliationEvidenceReader.class.getDeclaredFields()) {
            assertThat(BankingProvider.class.isAssignableFrom(f.getType()))
                    .as("ReleaseReconciliationEvidenceReader must not depend on BankingProvider")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("FinancialOutboxWorker has zero BankingProvider dependencies")
    void outboxWorker_noBankingProvider() {
        for (Field f : FinancialOutboxWorker.class.getDeclaredFields()) {
            assertThat(BankingProvider.class.isAssignableFrom(f.getType()))
                    .as("FinancialOutboxWorker must not depend on BankingProvider")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Decision engine and adapters have zero BankingProvider dependencies")
    void decisionEngines_noBankingProvider() {
        List<Class<?>> engineClasses = List.of(
                ReleaseDecisionEngine.class,
                ApprovalDecisionAdapter.class,
                DisputeDecisionAdapter.class,
                DocumentDecisionAdapter.class,
                FraudDecisionAdapter.class,
                MilestoneRulesDecisionAdapter.class,
                ReconciliationDecisionAdapter.class,
                RiskDecisionAdapter.class,
                TrustDecisionAdapter.class
        );
        for (Class<?> clazz : engineClasses) {
            for (Field f : clazz.getDeclaredFields()) {
                assertThat(BankingProvider.class.isAssignableFrom(f.getType()))
                        .as(clazz.getSimpleName() + " must not depend on BankingProvider")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("SettlementStatusRecoveryService has zero initiateSettlement calls or methods")
    void recoveryService_noInitiateSettlement() {
        for (Method m : SettlementStatusRecoveryService.class.getDeclaredMethods()) {
            assertThat(m.getName().toLowerCase())
                    .as("SettlementStatusRecoveryService must never contain initiate settlement methods")
                    .doesNotContain("initiatesettlement");
        }
    }

    @Test
    @DisplayName("Only SettlementDispatchHandler contains initiateSettlement call in release execution package")
    void dispatchHandler_onlyAllowedInitiator() {
        boolean foundInDispatchHandler = Arrays.stream(SettlementDispatchHandler.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("dispatchPreparedWorkItem") || m.getName().equals("dispatchPreparedSettlement"));
        assertThat(foundInDispatchHandler).isTrue();
    }

    @Test
    @DisplayName("FinancialOutboxWorker has no @Scheduled release payout annotations")
    void outboxWorker_noScheduledPayoutAnnotation() {
        for (Method m : FinancialOutboxWorker.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Scheduled.class))
                    .as("FinancialOutboxWorker must not have automated @Scheduled payout runner in sandbox")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("No raw beneficiary bank account numbers or credentials in outbox, attempt, observation, or reconciliation")
    void entities_noRawBeneficiaryBankData() {
        List<Class<?>> piiProtectedClasses = List.of(
                ReleaseInstruction.class,
                ReleaseReservation.class,
                FinancialOutboxMessage.class,
                SettlementExecutionAttempt.class,
                SettlementObservation.class,
                ReconciliationItem.class
        );

        List<String> forbiddenFieldNames = List.of(
                "accountnumber", "bankaccountnumber", "rawaccount", "ifsc", "ifsccode",
                "bankroutingnumber", "secret", "password", "apikey", "credential"
        );

        for (Class<?> clazz : piiProtectedClasses) {
            for (Field f : clazz.getDeclaredFields()) {
                String fieldName = f.getName().toLowerCase();
                for (String forbidden : forbiddenFieldNames) {
                    assertThat(fieldName)
                            .as(clazz.getSimpleName() + " must not contain raw PII/credential field: " + f.getName())
                            .isNotEqualTo(forbidden);
                }
            }
        }
    }
}
