package com.finx.release;

import com.finx.banking.controller.BankingWebhookController;
import com.finx.banking.provider.BankingProvider;
import com.finx.engine.release.ReleaseDecisionEngine;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleasePersistenceFoundationArchitectureTest {

    private static final List<Class<?>> E1_PRODUCTION_CLASSES = List.of(
            ReleaseInstruction.class,
            ReleaseReservation.class,
            ReleaseInstructionStatus.class,
            ReleaseReservationStatus.class,
            ReleaseInstructionRepository.class,
            ReleaseReservationRepository.class
    );

    private static final List<Class<?>> PROHIBITED_TYPES = List.of(
            BankingProvider.class,
            SettlementAuthorizationService.class,
            PaymentOrchestrationService.class,
            LedgerService.class,
            EscrowService.class,
            ReleaseDecisionEngine.class,
            BankingWebhookController.class,
            DeterministicReconciliationMatcher.class
    );

    @Test
    @DisplayName("1. E1 classes must not declare fields of prohibited execution components")
    void noProhibitedFields() {
        for (Class<?> clazz : E1_PRODUCTION_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_TYPES) {
                    assertThat(field.getType())
                            .withFailMessage("E1 class %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }

    @Test
    @DisplayName("2. E1 methods must not use prohibited execution types in parameters or return types")
    void noProhibitedMethods() {
        for (Class<?> clazz : E1_PRODUCTION_CLASSES) {
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
    @DisplayName("3. ReleaseInstruction must not contain plaintext or raw bank account/PII fields")
    void sensitiveDataBoundary() {
        for (Field field : ReleaseInstruction.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertThat(name)
                    .withFailMessage("ReleaseInstruction must not contain raw/sensitive field: %s", name)
                    .doesNotContain("accountnumber")
                    .doesNotContain("ifsc")
                    .doesNotContain("rawpayload")
                    .doesNotContain("secret")
                    .doesNotContain("pan")
                    .doesNotContain("gstin")
                    .doesNotContain("kyc");
        }
    }
}
