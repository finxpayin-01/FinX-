package com.finx.release;

import com.finx.banking.controller.BankingWebhookController;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.notification.service.NotificationService;
import com.finx.outbox.service.FinancialOutboxLeaseService;
import com.finx.outbox.worker.FinancialOutboxWorker;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReleaseExecutionE4ArchitectureTest {

    private static final List<Class<?>> E4_COMPONENTS = List.of(
            FinancialOutboxWorker.class,
            FinancialOutboxLeaseService.class,
            SettlementExecutionAttemptService.class
    );

    private static final List<Class<?>> PROHIBITED_TYPES = List.of(
            BankingProvider.class,
            MockBankingProvider.class,
            SettlementAuthorizationService.class,
            LedgerService.class,
            EscrowService.class,
            BankingWebhookController.class,
            NotificationService.class
    );

    @Test
    @DisplayName("1. FinancialOutboxWorker depends only on leaseService and attemptService")
    void workerAllowedDependencies() {
        boolean hasLeaseService = false;
        boolean hasAttemptService = false;

        for (Field f : FinancialOutboxWorker.class.getDeclaredFields()) {
            if (f.getType().equals(FinancialOutboxLeaseService.class)) {
                hasLeaseService = true;
            }
            if (f.getType().equals(SettlementExecutionAttemptService.class)) {
                hasAttemptService = true;
            }
        }

        assertThat(hasLeaseService).isTrue();
        assertThat(hasAttemptService).isTrue();
    }

    @Test
    @DisplayName("2. E4 worker components must NOT declare fields of prohibited types")
    void noProhibitedFields() {
        for (Class<?> clazz : E4_COMPONENTS) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_TYPES) {
                    assertThat(field.getType())
                            .withFailMessage("E4 class %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }

    @Test
    @DisplayName("3. E4 constructors must NOT inject prohibited components")
    void noProhibitedConstructors() {
        for (Class<?> clazz : E4_COMPONENTS) {
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                for (Parameter param : ctor.getParameters()) {
                    for (Class<?> prohibited : PROHIBITED_TYPES) {
                        assertThat(param.getType())
                                .withFailMessage("Constructor in %s must not accept prohibited parameter type %s",
                                        clazz.getSimpleName(), prohibited.getSimpleName())
                                .isNotEqualTo(prohibited);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("4. E4 worker components must NOT use @Scheduled annotations")
    void noScheduledAnnotations() {
        for (Class<?> clazz : E4_COMPONENTS) {
            for (Method m : clazz.getDeclaredMethods()) {
                assertThat(m.isAnnotationPresent(Scheduled.class))
                        .withFailMessage("Method %s in %s must not be annotated with @Scheduled",
                                m.getName(), clazz.getSimpleName())
                        .isFalse();
            }
        }
    }
}
