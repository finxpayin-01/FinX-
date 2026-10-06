package com.finx.engine.policy.fraud;

import com.finx.banking.provider.BankingProvider;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.reconciliation.service.ReconciliationService;
import com.finx.risk.service.RiskScoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudPolicyRegistrationArchitectureTest {

    private static final List<Class<?>> FRAUD_POLICY_CLASSES = List.of(
            FraudPolicyDefinition.class,
            FraudPolicyRegistrar.class
    );

    private static final List<Class<?>> PROHIBITED_COMPONENTS = List.of(
            BankingProvider.class,
            SettlementAuthorizationService.class,
            PaymentOrchestrationService.class,
            LedgerService.class,
            EscrowService.class,
            RazorpayService.class,
            PaymentService.class,
            RiskScoringService.class,
            ReconciliationService.class
    );

    @Test
    @DisplayName("F1-08: Fraud policy package classes must not declare fields of prohibited execution/scoring components")
    void fraudPolicyClassesMustNotDeclareProhibitedFields() {
        for (Class<?> clazz : FRAUD_POLICY_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_COMPONENTS) {
                    assertThat(field.getType())
                            .withFailMessage("Class %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
                String fieldTypeName = field.getType().getName();
                assertThat(fieldTypeName)
                        .withFailMessage("Class %s must not declare banking or web client fields: %s",
                                clazz.getSimpleName(), fieldTypeName)
                        .doesNotContain("BankingProvider")
                        .doesNotContain("WebClient")
                        .doesNotContain("RestClient")
                        .doesNotContain("Razorpay");
            }
        }
    }

    @Test
    @DisplayName("F1-09: Fraud policy constructors must not accept prohibited execution/scoring components")
    void fraudPolicyConstructorsMustNotInjectProhibitedComponents() {
        for (Class<?> clazz : FRAUD_POLICY_CLASSES) {
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                for (Class<?> paramType : ctor.getParameterTypes()) {
                    for (Class<?> prohibited : PROHIBITED_COMPONENTS) {
                        assertThat(paramType)
                                .withFailMessage("Class %s constructor must not accept parameter of type %s",
                                        clazz.getSimpleName(), prohibited.getSimpleName())
                                .isNotEqualTo(prohibited);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("F1-10: Fraud policy methods must not return prohibited execution/scoring components")
    void fraudPolicyMethodsMustNotReturnProhibitedComponents() {
        for (Class<?> clazz : FRAUD_POLICY_CLASSES) {
            for (Method method : clazz.getDeclaredMethods()) {
                for (Class<?> prohibited : PROHIBITED_COMPONENTS) {
                    assertThat(method.getReturnType())
                            .withFailMessage("Class %s method %s must not return type %s",
                                    clazz.getSimpleName(), method.getName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }

    @Test
    @DisplayName("F1-11: Fraud policy package must not be or reference REST controllers")
    void noRestControllersInFraudPolicyPackage() {
        for (Class<?> clazz : FRAUD_POLICY_CLASSES) {
            assertThat(clazz.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class))
                    .withFailMessage("Class %s must not be a @RestController", clazz.getSimpleName())
                    .isFalse();
            assertThat(clazz.isAnnotationPresent(org.springframework.stereotype.Controller.class))
                    .withFailMessage("Class %s must not be a @Controller", clazz.getSimpleName())
                    .isFalse();
        }
    }
}
