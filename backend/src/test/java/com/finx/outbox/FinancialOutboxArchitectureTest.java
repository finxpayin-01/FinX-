package com.finx.outbox;

import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.outbox.service.FinancialOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class FinancialOutboxArchitectureTest {

    private static final List<String> FORBIDDEN_DEPENDENCY_TOKENS = List.of(
            "BankingProvider",
            "MockBankingProvider",
            "LedgerService",
            "EscrowService",
            "SettlementAuthorizationService",
            "PaymentOrchestrationService",
            "BankingWebhookController",
            "Scheduled",
            "Kafka",
            "Redis",
            "Blockchain",
            "Razorpay"
    );

    @Test
    @DisplayName("1. FinancialOutboxService has zero forbidden dependencies")
    void testFinancialOutboxServiceDependencies() {
        assertClassHasNoForbiddenTokens(FinancialOutboxService.class);
    }

    @Test
    @DisplayName("2. FinancialOutboxRepository has zero forbidden dependencies")
    void testFinancialOutboxRepositoryDependencies() {
        assertClassHasNoForbiddenTokens(FinancialOutboxRepository.class);
    }

    @Test
    @DisplayName("3. FinancialOutboxMessage has zero forbidden dependencies")
    void testFinancialOutboxMessageDependencies() {
        assertClassHasNoForbiddenTokens(FinancialOutboxMessage.class);
    }

    private void assertClassHasNoForbiddenTokens(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            String typeName = field.getType().getName();
            for (String forbidden : FORBIDDEN_DEPENDENCY_TOKENS) {
                assertThat(typeName)
                        .withFailMessage("Class %s has forbidden dependency on field %s of type %s", clazz.getSimpleName(), field.getName(), typeName)
                        .doesNotContain(forbidden);
            }
        }

        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            for (Class<?> param : constructor.getParameterTypes()) {
                String typeName = param.getName();
                for (String forbidden : FORBIDDEN_DEPENDENCY_TOKENS) {
                    assertThat(typeName)
                            .withFailMessage("Class %s constructor has forbidden dependency on %s", clazz.getSimpleName(), typeName)
                            .doesNotContain(forbidden);
                }
            }
        }

        for (Method method : clazz.getDeclaredMethods()) {
            String returnTypeName = method.getReturnType().getName();
            for (String forbidden : FORBIDDEN_DEPENDENCY_TOKENS) {
                assertThat(returnTypeName)
                        .withFailMessage("Class %s method %s returns forbidden type %s", clazz.getSimpleName(), method.getName(), returnTypeName)
                        .doesNotContain(forbidden);
            }
            for (Class<?> param : method.getParameterTypes()) {
                String typeName = param.getName();
                for (String forbidden : FORBIDDEN_DEPENDENCY_TOKENS) {
                    assertThat(typeName)
                            .withFailMessage("Class %s method %s has forbidden parameter type %s", clazz.getSimpleName(), method.getName(), typeName)
                            .doesNotContain(forbidden);
                }
            }
        }
    }
}
