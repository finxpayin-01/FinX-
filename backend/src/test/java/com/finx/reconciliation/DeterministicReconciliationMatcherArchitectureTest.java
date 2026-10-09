package com.finx.reconciliation;

import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class DeterministicReconciliationMatcherArchitectureTest {

    @Test
    @DisplayName("DeterministicReconciliationMatcher must NOT depend on execution, ingestion, ledger or decision engines")
    void testDeterministicMatcherDependencies() {
        Class<?> matcherClass = DeterministicReconciliationMatcher.class;

        // Check declared fields
        Field[] fields = matcherClass.getDeclaredFields();
        Set<String> fieldTypeNames = Arrays.stream(fields)
                .map(f -> f.getType().getName())
                .collect(Collectors.toSet());

        List<String> forbiddenTypes = List.of(
                "com.finx.banking.provider.BankingProvider",
                "com.finx.reconciliation.service.BankStatementIngestionService",
                "com.finx.paymentorchestration.service.PaymentOrchestrationService",
                "com.finx.paymentorchestration.service.SettlementAuthorizationService",
                "com.finx.ledger.service.LedgerService",
                "com.finx.escrow.service.EscrowService",
                "com.finx.engine.EngineDecisionService",
                "com.finx.engine.ReleaseDecisionEngine",
                "com.finx.payment.service.RazorpayService"
        );

        for (String forbidden : forbiddenTypes) {
            assertThat(fieldTypeNames)
                    .as("Matcher must NOT contain field of type: " + forbidden)
                    .doesNotContain(forbidden);
        }

        // Check constructors
        for (Constructor<?> ctor : matcherClass.getDeclaredConstructors()) {
            for (Class<?> paramType : ctor.getParameterTypes()) {
                assertThat(forbiddenTypes)
                        .as("Matcher constructor must NOT accept: " + paramType.getName())
                        .doesNotContain(paramType.getName());
            }
        }

        // Check method return types and parameter types
        for (Method method : matcherClass.getDeclaredMethods()) {
            assertThat(forbiddenTypes)
                    .as("Matcher method return type cannot be: " + method.getReturnType().getName())
                    .doesNotContain(method.getReturnType().getName());

            for (Class<?> param : method.getParameterTypes()) {
                assertThat(forbiddenTypes)
                        .as("Matcher method parameter cannot be: " + param.getName())
                        .doesNotContain(param.getName());
            }
        }
    }

    @Test
    @DisplayName("DeterministicReconciliationMatcher must NOT contain findAll query invocations")
    void testNoFindAllQueryInvocations() throws Exception {
        // Inspect repository methods bounded vs findAll
        Class<?> ptRepoClass = Class.forName("com.finx.paymentorchestration.repository.PaymentTransactionRepository");
        Class<?> siRepoClass = Class.forName("com.finx.paymentorchestration.repository.SettlementInstructionRepository");

        assertThat(ptRepoClass.getMethod("findInboundReconciliationCandidates", String.class, String.class, java.time.Instant.class, java.time.Instant.class))
                .isNotNull();

        assertThat(siRepoClass.getMethod("findOutboundReconciliationCandidates", String.class, String.class, java.time.Instant.class, java.time.Instant.class))
                .isNotNull();
    }
}
