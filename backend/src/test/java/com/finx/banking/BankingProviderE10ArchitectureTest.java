package com.finx.banking;

import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.banking.transport.BankHttpTransport;
import com.finx.banking.transport.BankingWebClientFactory;
import com.finx.engine.adapter.ApprovalDecisionAdapter;
import com.finx.engine.adapter.DisputeDecisionAdapter;
import com.finx.engine.adapter.DocumentDecisionAdapter;
import com.finx.engine.adapter.FraudDecisionAdapter;
import com.finx.engine.adapter.MilestoneRulesDecisionAdapter;
import com.finx.engine.adapter.ReconciliationDecisionAdapter;
import com.finx.engine.adapter.RiskDecisionAdapter;
import com.finx.engine.adapter.TrustDecisionAdapter;
import com.finx.engine.release.ReleaseDecisionEngine;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.service.MilestoneService;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.reconciliation.service.ReleaseReconciliationEvidenceReader;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.execution.service.SettlementDispatchStateService;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import com.finx.release.observation.service.SettlementStatusRecoveryService;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.ReleaseAuthorizationService;
import com.finx.release.service.ReleaseInstructionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BankingProviderE10ArchitectureTest {

    @Test
    @DisplayName("Core domain services depend only on BankingProvider interface, not PartnerBankingProvider")
    void coreDomainServices_dependOnlyOnBankingProviderInterface() {
        List<Class<?>> coreServices = List.of(
                SettlementDispatchHandler.class,
                SettlementDispatchStateService.class,
                SettlementExecutionAttemptService.class,
                ReleaseAuthorizationService.class,
                ReleaseInstructionService.class,
                SettlementStatusRecoveryService.class,
                TrustedSettlementFinalizer.class,
                DeterministicReconciliationMatcher.class,
                ReleaseReconciliationEvidenceReader.class
        );

        for (Class<?> service : coreServices) {
            for (Field field : service.getDeclaredFields()) {
                assertThat(PartnerBankingProvider.class.isAssignableFrom(field.getType()))
                        .as("Core service %s must never declare PartnerBankingProvider dependency", service.getSimpleName())
                        .isFalse();
                assertThat(field.getType().getPackageName())
                        .as("Core service %s field %s must not belong to partner package", service.getSimpleName(), field.getName())
                        .doesNotContain("com.finx.banking.provider.partner");
            }
        }
    }

    @Test
    @DisplayName("SettlementDispatchHandler does not import bank-specific DTOs or partner implementations")
    void settlementDispatchHandler_doesNotImportBankSpecificDtos() throws IOException {
        Path sourcePath = Path.of("src/main/java/com/finx/release/execution/service/SettlementDispatchHandler.java");
        if (!Files.exists(sourcePath)) {
            sourcePath = Path.of("backend/src/main/java/com/finx/release/execution/service/SettlementDispatchHandler.java");
        }
        assertThat(Files.exists(sourcePath)).isTrue();

        List<String> lines = Files.readAllLines(sourcePath);
        for (String line : lines) {
            if (line.trim().startsWith("import ")) {
                assertThat(line)
                        .as("SettlementDispatchHandler must not import partner adapter: " + line)
                        .doesNotContain("com.finx.banking.provider.partner")
                        .doesNotContain("Axis")
                        .doesNotContain("Hdfc")
                        .doesNotContain("Icici")
                        .doesNotContain("YesBank")
                        .doesNotContain("Dbs");
            }
        }

        for (Field field : SettlementDispatchHandler.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("SettlementDispatchHandler field %s must not be partner-specific", field.getName())
                    .doesNotContain("partner")
                    .doesNotContain("Axis");
        }
    }

    @Test
    @DisplayName("Reconciliation services do not import or depend on partner adapter")
    void reconciliation_doesNotImportPartnerAdapter() {
        List<Class<?>> reconciliationClasses = List.of(
                DeterministicReconciliationMatcher.class,
                ReleaseReconciliationEvidenceReader.class
        );

        for (Class<?> clazz : reconciliationClasses) {
            for (Field f : clazz.getDeclaredFields()) {
                assertThat(PartnerBankingProvider.class.isAssignableFrom(f.getType()))
                        .as("%s must not depend on PartnerBankingProvider", clazz.getSimpleName())
                        .isFalse();
                assertThat(f.getType().getPackageName())
                        .as("%s field %s must not be in partner package", clazz.getSimpleName(), f.getName())
                        .doesNotContain("com.finx.banking.provider.partner");
            }
        }
    }

    @Test
    @DisplayName("Decision engines and adapters do not import or depend on partner adapter")
    void decisionEngines_doNotImportPartnerAdapter() {
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
                assertThat(PartnerBankingProvider.class.isAssignableFrom(f.getType()))
                        .as("%s must not depend on PartnerBankingProvider", clazz.getSimpleName())
                        .isFalse();
                assertThat(f.getType().getPackageName())
                        .as("%s field %s must not be in partner package", clazz.getSimpleName(), f.getName())
                        .doesNotContain("com.finx.banking.provider.partner");
            }
        }
    }

    @Test
    @DisplayName("Partner adapter does not access LedgerService, EscrowService, ReleaseReservationRepository, or MilestoneService")
    void partnerAdapter_doesNotAccessCoreLedgerOrEscrowOrReservationsOrMilestones() {
        List<Class<?>> forbiddenDependencies = List.of(
                LedgerService.class,
                EscrowService.class,
                ReleaseReservationRepository.class,
                MilestoneService.class
        );

        for (Field f : PartnerBankingProvider.class.getDeclaredFields()) {
            for (Class<?> forbidden : forbiddenDependencies) {
                assertThat(forbidden.isAssignableFrom(f.getType()))
                        .as("PartnerBankingProvider must not declare dependency on " + forbidden.getSimpleName())
                        .isFalse();
            }
        }

        for (Constructor<?> ctor : PartnerBankingProvider.class.getDeclaredConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                for (Class<?> forbidden : forbiddenDependencies) {
                    assertThat(forbidden.isAssignableFrom(p.getType()))
                            .as("PartnerBankingProvider constructor must not accept " + forbidden.getSimpleName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("Transport layer has zero financial-domain mutation dependencies")
    void transportLayer_hasNoFinancialDomainMutationDependency() {
        List<Class<?>> transportClasses = List.of(
                BankHttpTransport.class,
                BankingWebClientFactory.class
        );

        List<String> forbiddenPackages = List.of(
                "com.finx.ledger",
                "com.finx.escrow",
                "com.finx.release.repository",
                "com.finx.milestone"
        );

        for (Class<?> clazz : transportClasses) {
            for (Field f : clazz.getDeclaredFields()) {
                String pkg = f.getType().getPackageName();
                for (String forbidden : forbiddenPackages) {
                    assertThat(pkg)
                            .as("%s field %s must not depend on financial domain package %s", clazz.getSimpleName(), f.getName(), forbidden)
                            .doesNotStartWith(forbidden);
                }
            }
            for (Method m : clazz.getDeclaredMethods()) {
                String returnPkg = m.getReturnType().getPackageName();
                for (String forbidden : forbiddenPackages) {
                    assertThat(returnPkg)
                            .as("%s method %s return type must not depend on financial domain package %s", clazz.getSimpleName(), m.getName(), forbidden)
                            .doesNotStartWith(forbidden);
                }
            }
        }
    }
}
