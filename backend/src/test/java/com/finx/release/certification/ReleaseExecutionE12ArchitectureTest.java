package com.finx.release.certification;

import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.reconciliation.service.ReleaseReconciliationEvidenceReader;
import com.finx.release.controller.AdminReleaseManagementController;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.gate.ProductionSettlementDispatchGate;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseExecutionE12ArchitectureTest {

    @Test
    @DisplayName("No direct payout controller or remote gate opening endpoint")
    void verifyNoRemoteGateEnablingEndpoints() {
        for (Method method : AdminReleaseManagementController.class.getDeclaredMethods()) {
            assertThat(method.getName().toLowerCase())
                    .as("Controller method '%s' must not expose remote gate enabling or live payout actions", method.getName())
                    .doesNotContain("enableproduction")
                    .doesNotContain("opengate")
                    .doesNotContain("setcertified")
                    .doesNotContain("directpayout");
        }
    }

    @Test
    @DisplayName("Banking providers do not depend on LedgerService or EscrowService")
    void providers_doNotDependOnLedgerOrEscrow() {
        List<Class<?>> providerClasses = List.of(MockBankingProvider.class, PartnerBankingProvider.class);
        List<Class<?>> forbiddenDependencies = List.of(LedgerService.class, EscrowService.class);

        for (Class<?> pClass : providerClasses) {
            for (Field field : pClass.getDeclaredFields()) {
                for (Class<?> forbidden : forbiddenDependencies) {
                    assertThat(forbidden.isAssignableFrom(field.getType()))
                            .as("%s field '%s' must not depend on %s", pClass.getSimpleName(), field.getName(), forbidden.getSimpleName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("Reconciliation does not depend on BankingProvider (passive statement ingestion model)")
    void reconciliation_doesNotDependOnBankingProvider() {
        List<Class<?>> reconClasses = List.of(
                DeterministicReconciliationMatcher.class,
                ReleaseReconciliationEvidenceReader.class
        );

        for (Class<?> rClass : reconClasses) {
            for (Field field : rClass.getDeclaredFields()) {
                assertThat(BankingProvider.class.isAssignableFrom(field.getType()))
                        .as("%s field '%s' must not depend on BankingProvider", rClass.getSimpleName(), field.getName())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("Trusted settlement finalizer does not depend on BankingProvider")
    void finalizer_doesNotDependOnBankingProvider() {
        for (Field field : TrustedSettlementFinalizer.class.getDeclaredFields()) {
            assertThat(BankingProvider.class.isAssignableFrom(field.getType()))
                    .as("TrustedSettlementFinalizer field '%s' must not depend on BankingProvider", field.getName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("ReleaseInstruction does not expose generic updateStatus or setStatus setter")
    void releaseInstruction_doesNotExposeGenericStatusSetter() {
        boolean hasSetStatus = Arrays.stream(ReleaseInstruction.class.getMethods())
                .anyMatch(m -> m.getName().equals("setStatus") || m.getName().equals("updateStatus"));

        assertThat(hasSetStatus)
                .as("ReleaseInstruction must NOT expose a generic public setStatus/updateStatus method")
                .isFalse();
    }

    @Test
    @DisplayName("ReleaseInstruction and SettlementExecutionAttempt do not store raw bank account numbers")
    void financialEntities_doNotStoreRawBankAccount() {
        for (Field field : ReleaseInstruction.class.getDeclaredFields()) {
            assertThat(field.getName().toLowerCase())
                    .as("ReleaseInstruction must not declare raw account field '%s'", field.getName())
                    .isNotEqualTo("bankaccountnumber")
                    .isNotEqualTo("rawaccountnumber");
        }

        for (Field field : SettlementExecutionAttempt.class.getDeclaredFields()) {
            assertThat(field.getName().toLowerCase())
                    .as("SettlementExecutionAttempt must not declare raw account field '%s'", field.getName())
                    .isNotEqualTo("bankaccountnumber")
                    .isNotEqualTo("rawaccountnumber");
        }
    }

    @Test
    @DisplayName("ProductionSettlementDispatchGate defaults closed")
    void productionGate_defaultsClosed() {
        ProductionSettlementDispatchGate defaultGate = new ProductionSettlementDispatchGate(false, null, null, false);
        assertThat(defaultGate.isProductionDispatchAllowed()).isFalse();
        assertThat(defaultGate.isMasterProductionEnabled()).isFalse();
        assertThat(defaultGate.isE12Certified()).isFalse();
    }

    @Test
    @DisplayName("ProductionCertificationProperties defaults all flags to false")
    void certificationProperties_defaultToFalse() {
        ProductionCertificationProperties props = new ProductionCertificationProperties();
        assertThat(props.isTechnicalCertified()).isFalse();
        assertThat(props.isBankContractCertified()).isFalse();
        assertThat(props.isBankUatCertified()).isFalse();
        assertThat(props.isSecurityCertified()).isFalse();
        assertThat(props.isComplianceCertified()).isFalse();
        assertThat(props.isExecutiveSignoff()).isFalse();
        assertThat(props.isIncidentRunbookCertified()).isFalse();
        assertThat(props.isReconciliationCertified()).isFalse();
    }
}
