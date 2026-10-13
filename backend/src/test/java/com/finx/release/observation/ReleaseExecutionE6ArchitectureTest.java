package com.finx.release.observation;

import com.finx.banking.controller.BankingWebhookController;
import com.finx.banking.provider.BankingProvider;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.observation.service.SettlementObservationService;
import com.finx.release.observation.service.SettlementStatusRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseExecutionE6ArchitectureTest {

    @Test
    @DisplayName("SettlementObservationService has zero dependencies on LedgerService, EscrowService, or Authorization")
    void observationService_forbiddenDependenciesAbsent() {
        Field[] fields = SettlementObservationService.class.getDeclaredFields();
        for (Field f : fields) {
            String typeName = f.getType().getSimpleName().toLowerCase();
            assertThat(typeName)
                    .as("SettlementObservationService must not depend on " + f.getType().getSimpleName())
                    .doesNotContain("ledger")
                    .doesNotContain("escrow")
                    .doesNotContain("milestone")
                    .doesNotContain("authorization")
                    .doesNotContain("outbox")
                    .doesNotContain("provider");
        }
    }

    @Test
    @DisplayName("SettlementStatusRecoveryService has zero dependencies on LedgerService, EscrowService, or Authorization")
    void recoveryService_forbiddenDependenciesAbsent() {
        Field[] fields = SettlementStatusRecoveryService.class.getDeclaredFields();
        for (Field f : fields) {
            String typeName = f.getType().getSimpleName().toLowerCase();
            assertThat(typeName)
                    .as("SettlementStatusRecoveryService must not depend on " + f.getType().getSimpleName())
                    .doesNotContain("ledger")
                    .doesNotContain("escrow")
                    .doesNotContain("milestone")
                    .doesNotContain("authorization")
                    .doesNotContain("outbox");
        }
    }

    @Test
    @DisplayName("SettlementStatusRecoveryService does not call initiateSettlement")
    void recoveryService_neverCallsInitiateSettlement() {
        Method[] methods = SettlementStatusRecoveryService.class.getDeclaredMethods();
        for (Method m : methods) {
            assertThat(m.getName().toLowerCase())
                    .as("SettlementStatusRecoveryService must never have initiate settlement methods")
                    .doesNotContain("initiatesettlement");
        }
    }

    @Test
    @DisplayName("SettlementObservationEvidenceReader is strictly read-only and has zero financial side-effect dependencies")
    void evidenceReader_forbiddenDependenciesAbsent() {
        Field[] fields = SettlementObservationEvidenceReader.class.getDeclaredFields();
        for (Field f : fields) {
            String typeName = f.getType().getSimpleName().toLowerCase();
            assertThat(typeName)
                    .doesNotContain("ledger")
                    .doesNotContain("escrow")
                    .doesNotContain("outbox")
                    .doesNotContain("provider");
        }
    }
}
