package com.finx.release.observability;

import com.finx.banking.provider.BankingProvider;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.release.control.ReleaseOperationalControlService;
import com.finx.release.controller.AdminReleaseManagementController;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseExecutionE11ArchitectureTest {

    private static final List<Class<?>> FORBIDDEN_CONTROLLER_DEPENDENCIES = List.of(
            BankingProvider.class,
            LedgerService.class,
            EscrowService.class,
            TrustedSettlementFinalizer.class,
            SettlementDispatchHandler.class
    );

    @Test
    @DisplayName("AdminReleaseManagementController does not depend directly on money movement, finalizer, or provider")
    void adminController_doesNotDependOnFinancialMutationServices() {
        for (Field field : AdminReleaseManagementController.class.getDeclaredFields()) {
            for (Class<?> forbidden : FORBIDDEN_CONTROLLER_DEPENDENCIES) {
                assertThat(forbidden.isAssignableFrom(field.getType()))
                        .as("AdminReleaseManagementController field '%s' must not declare dependency on %s",
                                field.getName(), forbidden.getSimpleName())
                        .isFalse();
            }
        }

        for (Constructor<?> ctor : AdminReleaseManagementController.class.getDeclaredConstructors()) {
            for (Parameter param : ctor.getParameters()) {
                for (Class<?> forbidden : FORBIDDEN_CONTROLLER_DEPENDENCIES) {
                    assertThat(forbidden.isAssignableFrom(param.getType()))
                            .as("AdminReleaseManagementController constructor must not accept %s", forbidden.getSimpleName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("ReleaseOperationalControlService does not depend on provider, ledger, escrow, or finalizer")
    void operationalControlService_doesNotDependOnDirectMoneyMovement() {
        for (Field field : ReleaseOperationalControlService.class.getDeclaredFields()) {
            for (Class<?> forbidden : FORBIDDEN_CONTROLLER_DEPENDENCIES) {
                assertThat(forbidden.isAssignableFrom(field.getType()))
                        .as("ReleaseOperationalControlService field '%s' must not declare dependency on %s",
                                field.getName(), forbidden.getSimpleName())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("ReleaseExecutionMetrics does not declare dependencies on mutation services")
    void metrics_doesNotDependOnMutationServices() {
        List<Class<?>> forbiddenMutationServices = List.of(
                LedgerService.class,
                EscrowService.class,
                SettlementDispatchHandler.class,
                TrustedSettlementFinalizer.class
        );

        for (Field field : ReleaseExecutionMetrics.class.getDeclaredFields()) {
            for (Class<?> forbidden : forbiddenMutationServices) {
                assertThat(forbidden.isAssignableFrom(field.getType()))
                        .as("ReleaseExecutionMetrics field '%s' must not depend on %s", field.getName(), forbidden.getSimpleName())
                        .isFalse();
            }
        }
    }
}
