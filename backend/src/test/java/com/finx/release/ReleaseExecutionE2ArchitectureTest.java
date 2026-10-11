package com.finx.release;

import com.finx.banking.controller.BankingWebhookController;
import com.finx.banking.provider.BankingProvider;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.JournalLineRepository;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.release.service.EscrowReservationService;
import com.finx.release.service.ReleaseAuthorizationService;
import com.finx.release.service.ReleaseDecisionExecutionValidator;
import com.finx.release.service.ReleaseFundingScopeResolver;
import com.finx.release.service.ReleaseInstructionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseExecutionE2ArchitectureTest {

    private static final List<Class<?>> E2_SERVICES = List.of(
            ReleaseInstructionService.class,
            ReleaseAuthorizationService.class,
            ReleaseDecisionExecutionValidator.class,
            ReleaseFundingScopeResolver.class,
            EscrowReservationService.class
    );

    private static final List<Class<?>> PROHIBITED_TYPES = List.of(
            BankingProvider.class,
            SettlementAuthorizationService.class,
            PaymentOrchestrationService.class,
            SettlementInstructionRepository.class,
            LedgerService.class,
            JournalEntryRepository.class,
            JournalLineRepository.class,
            LedgerAccountRepository.class,
            BankingWebhookController.class
    );

    @Test
    @DisplayName("1. E2 services must not declare fields of prohibited external components")
    void noProhibitedFields() {
        for (Class<?> clazz : E2_SERVICES) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_TYPES) {
                    assertThat(field.getType())
                            .withFailMessage("E2 service %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }

    @Test
    @DisplayName("2. E2 services constructors must not inject prohibited components")
    void noProhibitedConstructors() {
        for (Class<?> clazz : E2_SERVICES) {
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
    @DisplayName("3. E2 methods must not use prohibited execution types in parameters or return types")
    void noProhibitedMethods() {
        for (Class<?> clazz : E2_SERVICES) {
            for (Method method : clazz.getDeclaredMethods()) {
                for (Class<?> prohibited : PROHIBITED_TYPES) {
                    assertThat(method.getReturnType())
                            .withFailMessage("Method %s in %s must not return prohibited type %s",
                                    method.getName(), clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
                for (Parameter param : method.getParameters()) {
                    for (Class<?> prohibited : PROHIBITED_TYPES) {
                        assertThat(param.getType())
                                .withFailMessage("Parameter %s in method %s in %s must not use prohibited type %s",
                                        param.getName(), method.getName(), clazz.getSimpleName(), prohibited.getSimpleName())
                                .isNotEqualTo(prohibited);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("4. ReleaseInstructionService must not depend on EscrowService")
    void releaseInstructionServiceNoEscrowService() {
        for (Field f : ReleaseInstructionService.class.getDeclaredFields()) {
            assertThat(f.getType()).isNotEqualTo(EscrowService.class);
        }
    }

    @Test
    @DisplayName("5. ReleaseAuthorizationService must not depend on EscrowService")
    void releaseAuthorizationServiceNoEscrowService() {
        for (Field f : ReleaseAuthorizationService.class.getDeclaredFields()) {
            assertThat(f.getType()).isNotEqualTo(EscrowService.class);
        }
    }
}
