package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.evaluator.DecisionEvaluator;
import com.finx.engine.model.*;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.engine.service.EngineDecisionService;
import com.finx.engine.service.EnginePolicyVersionService;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class CommonEngineFrameworkArchitectureTest {

    private static final List<Class<?>> ENGINE_FRAMEWORK_CLASSES = List.of(
            // Models
            DecisionStatus.class,
            EngineType.class,
            EvaluationOutcome.class,
            PolicyScope.class,
            DecisionSubjectType.class,
            EvidenceReference.class,
            EngineDecisionFingerprint.class,
            RecordEngineDecisionCommand.class,
            EvaluationContext.class,
            // Entities
            EnginePolicyVersion.class,
            EngineDecision.class,
            // Repositories
            EnginePolicyVersionRepository.class,
            EngineDecisionRepository.class,
            // Services
            EnginePolicyVersionService.class,
            EngineDecisionService.class,
            // Evaluator SPI
            DecisionEvaluator.class
    );

    private static final List<Class<?>> PROHIBITED_FINANCIAL_EXECUTION_TYPES = List.of(
            BankingProvider.class,
            SettlementAuthorizationService.class,
            PaymentOrchestrationService.class,
            LedgerService.class,
            EscrowService.class,
            RazorpayService.class,
            PaymentService.class
    );

    @Test
    @DisplayName("1. Engine framework classes must not declare fields of prohibited execution components")
    void engineFrameworkClassesMustNotDeclareProhibitedFields() {
        for (Class<?> clazz : ENGINE_FRAMEWORK_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_FINANCIAL_EXECUTION_TYPES) {
                    assertThat(field.getType())
                            .withFailMessage("Class %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
                // Check string names for WebClient, RestClient, BankingProvider, Razorpay
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
    @DisplayName("2. Engine framework constructors must not inject prohibited execution components")
    void engineFrameworkConstructorsMustNotInjectProhibitedComponents() {
        for (Class<?> clazz : ENGINE_FRAMEWORK_CLASSES) {
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                for (Class<?> paramType : ctor.getParameterTypes()) {
                    for (Class<?> prohibited : PROHIBITED_FINANCIAL_EXECUTION_TYPES) {
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
    @DisplayName("3. Engine framework methods must not return prohibited execution components")
    void engineFrameworkMethodsMustNotReturnProhibitedComponents() {
        for (Class<?> clazz : ENGINE_FRAMEWORK_CLASSES) {
            for (Method method : clazz.getDeclaredMethods()) {
                for (Class<?> prohibited : PROHIBITED_FINANCIAL_EXECUTION_TYPES) {
                    assertThat(method.getReturnType())
                            .withFailMessage("Class %s method %s must not return type %s",
                                    clazz.getSimpleName(), method.getName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }

    @Test
    @DisplayName("4. Engine framework classes must not reference blockchain or cryptocurrency types")
    void noBlockchainReferencesInEngineFramework() {
        for (Class<?> clazz : ENGINE_FRAMEWORK_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                String typeName = field.getType().getName().toLowerCase();
                assertThat(typeName)
                        .withFailMessage("Class %s must not reference blockchain types in field %s",
                                clazz.getSimpleName(), field.getName())
                        .doesNotContain("blockchain")
                        .doesNotContain("web3")
                        .doesNotContain("ethereum")
                        .doesNotContain("solidity")
                        .doesNotContain("crypto");
            }
        }
    }

    @Test
    @DisplayName("5. Engine framework classes must not be or reference REST controllers")
    void noRestControllersInEngineFramework() {
        for (Class<?> clazz : ENGINE_FRAMEWORK_CLASSES) {
            assertThat(clazz.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class))
                    .withFailMessage("Class %s must not be a @RestController in this foundation phase", clazz.getSimpleName())
                    .isFalse();
            assertThat(clazz.isAnnotationPresent(org.springframework.stereotype.Controller.class))
                    .withFailMessage("Class %s must not be a @Controller in this foundation phase", clazz.getSimpleName())
                    .isFalse();
        }
    }
}
