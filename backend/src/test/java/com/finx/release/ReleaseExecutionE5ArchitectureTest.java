package com.finx.release;

import com.finx.banking.controller.BankingWebhookController;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.engine.release.ReleaseDecisionEngine;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.notification.service.NotificationService;
import com.finx.outbox.service.FinancialOutboxLeaseService;
import com.finx.outbox.worker.FinancialOutboxWorker;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.release.execution.gate.SandboxSettlementDispatchGate;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.execution.service.SettlementDispatchStateService;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import com.finx.release.service.ReleaseAuthorizationService;
import com.finx.release.service.ReleaseInstructionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReleaseExecutionE5ArchitectureTest {

    private static final List<Class<?>> BANKING_PROVIDER_FORBIDDEN_COMPONENTS = List.of(
            FinancialOutboxWorker.class,
            FinancialOutboxLeaseService.class,
            SettlementExecutionAttemptService.class,
            SettlementDispatchStateService.class,
            ReleaseAuthorizationService.class,
            ReleaseInstructionService.class,
            ReleaseDecisionEngine.class
    );

    private static final List<Class<?>> ALL_E5_DISPATCH_COMPONENTS = List.of(
            SettlementDispatchHandler.class,
            SettlementDispatchStateService.class,
            SandboxSettlementDispatchGate.class,
            FinancialOutboxWorker.class,
            FinancialOutboxLeaseService.class,
            SettlementExecutionAttemptService.class
    );

    private static final List<Class<?>> PROHIBITED_FINANCIAL_FINALITY_SERVICES = List.of(
            LedgerService.class,
            EscrowService.class,
            SettlementAuthorizationService.class,
            BankingWebhookController.class,
            NotificationService.class
    );

    @Test
    @DisplayName("1. SettlementDispatchHandler is the ONLY execution service declaring BankingProvider field")
    void bankingProviderAllowedOnlyInDispatchHandlerAndGate() {
        for (Class<?> clazz : BANKING_PROVIDER_FORBIDDEN_COMPONENTS) {
            for (Field field : clazz.getDeclaredFields()) {
                assertThat(field.getType())
                        .withFailMessage("Class %s must NOT declare field of BankingProvider", clazz.getSimpleName())
                        .isNotEqualTo(BankingProvider.class)
                        .isNotEqualTo(MockBankingProvider.class);
            }
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                for (Parameter param : ctor.getParameters()) {
                    assertThat(param.getType())
                            .withFailMessage("Class %s constructor must NOT inject BankingProvider", clazz.getSimpleName())
                            .isNotEqualTo(BankingProvider.class)
                            .isNotEqualTo(MockBankingProvider.class);
                }
            }
        }
    }

    @Test
    @DisplayName("2. SettlementDispatchStateService must NOT declare BankingProvider or PiiEncryptionService")
    void stateServiceHasNoProviderOrPiiDependency() {
        for (Field f : SettlementDispatchStateService.class.getDeclaredFields()) {
            assertThat(f.getType().getSimpleName())
                    .withFailMessage("SettlementDispatchStateService must not declare %s", f.getType().getSimpleName())
                    .doesNotContain("BankingProvider")
                    .doesNotContain("PiiEncryptionService");
        }
    }

    @Test
    @DisplayName("3. Zero interaction with LedgerService, EscrowService, or SettlementAuthorizationService in E5 dispatch components")
    void noFinancialFinalityServicesInE5() {
        for (Class<?> clazz : ALL_E5_DISPATCH_COMPONENTS) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_FINANCIAL_FINALITY_SERVICES) {
                    assertThat(field.getType())
                            .withFailMessage("Class %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                for (Parameter param : ctor.getParameters()) {
                    for (Class<?> prohibited : PROHIBITED_FINANCIAL_FINALITY_SERVICES) {
                        assertThat(param.getType())
                                .withFailMessage("Constructor in %s must not inject %s",
                                        clazz.getSimpleName(), prohibited.getSimpleName())
                                .isNotEqualTo(prohibited);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("4. Zero @Scheduled methods in any E5 dispatch component")
    void noScheduledMethodsInE5Components() {
        for (Class<?> clazz : ALL_E5_DISPATCH_COMPONENTS) {
            for (Method m : clazz.getDeclaredMethods()) {
                assertThat(m.isAnnotationPresent(Scheduled.class))
                        .withFailMessage("Method %s in %s must NOT be annotated with @Scheduled",
                                m.getName(), clazz.getSimpleName())
                        .isFalse();
            }
        }
    }
}
