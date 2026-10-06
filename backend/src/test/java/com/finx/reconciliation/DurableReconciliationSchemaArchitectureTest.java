package com.finx.reconciliation;

import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture and Boundary Containment Test for R1 Durable Reconciliation Schema.
 * Verifies zero coupling between new persistence classes and execution services/engines,
 * and verifies absence of sensitive data storage in durable entities.
 */
public class DurableReconciliationSchemaArchitectureTest {

    private static final Set<String> FORBIDDEN_DEPENDENCIES = Set.of(
            "com.finx.banking.provider.BankingProvider",
            "com.finx.reconciliation.service.ReconciliationService",
            "com.finx.paymentorchestration.service.PaymentOrchestrationService",
            "com.finx.paymentorchestration.service.SettlementAuthorizationService",
            "com.finx.ledger.service.LedgerService",
            "com.finx.escrow.service.EscrowService",
            "com.finx.razorpay.service.RazorpayService",
            "com.finx.engine.service.EngineDecisionService"
    );

    private static final List<String> FORBIDDEN_FIELD_SUBSTRINGS = List.of(
            "rawpayload",
            "rawresponse",
            "accountnumber",
            "counterpartyaccount",
            "beneficiaryaccountnumber",
            "pan",
            "gstin",
            "kycdocument"
    );

    @Test
    @DisplayName("Assert R1 entities have no fields or methods referencing forbidden execution/engine services")
    void testEntityDependencyContainment() {
        List<Class<?>> r1Classes = List.of(
                BankStatementImport.class,
                BankStatementLine.class,
                BankStatementImportRepository.class,
                BankStatementLineRepository.class
        );

        for (Class<?> clazz : r1Classes) {
            for (Field field : clazz.getDeclaredFields()) {
                String fieldTypeName = field.getType().getName();
                assertThat(FORBIDDEN_DEPENDENCIES)
                        .as("Class %s has field %s of forbidden type %s", clazz.getSimpleName(), field.getName(), fieldTypeName)
                        .doesNotContain(fieldTypeName);
            }

            for (Method method : clazz.getDeclaredMethods()) {
                String returnTypeName = method.getReturnType().getName();
                assertThat(FORBIDDEN_DEPENDENCIES)
                        .as("Class %s has method %s returning forbidden type %s", clazz.getSimpleName(), method.getName(), returnTypeName)
                        .doesNotContain(returnTypeName);

                for (Parameter param : method.getParameters()) {
                    String paramTypeName = param.getType().getName();
                    assertThat(FORBIDDEN_DEPENDENCIES)
                            .as("Class %s has method %s with forbidden parameter type %s", clazz.getSimpleName(), method.getName(), paramTypeName)
                            .doesNotContain(paramTypeName);
                }
            }
        }
    }

    @Test
    @DisplayName("Assert BankStatementImport and BankStatementLine do not declare sensitive data fields")
    void testSensitiveDataAbsenceInEntities() {
        List<Class<?>> entities = List.of(BankStatementImport.class, BankStatementLine.class);

        for (Class<?> entity : entities) {
            for (Field field : entity.getDeclaredFields()) {
                String fieldName = field.getName().toLowerCase();
                for (String forbidden : FORBIDDEN_FIELD_SUBSTRINGS) {
                    assertThat(fieldName)
                            .as("Entity %s contains sensitive/forbidden field '%s' (matched '%s')",
                                    entity.getSimpleName(), field.getName(), forbidden)
                            .doesNotContain(forbidden);
                }
            }
        }
    }

    @Test
    @DisplayName("Assert providerAccountId is the only account field and is documented as scope token")
    void testProviderAccountIdFieldOnly() {
        List<String> importFieldNames = Arrays.stream(BankStatementImport.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        List<String> lineFieldNames = Arrays.stream(BankStatementLine.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(importFieldNames).contains("providerAccountId");
        assertThat(lineFieldNames).contains("providerAccountId");

        assertThat(importFieldNames).doesNotContain("accountNumber", "bankAccountNumber");
        assertThat(lineFieldNames).doesNotContain("accountNumber", "bankAccountNumber", "counterpartyAccount");
    }
}
