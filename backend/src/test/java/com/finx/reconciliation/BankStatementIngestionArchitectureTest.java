package com.finx.reconciliation;

import com.finx.banking.dto.BankStatementEntry;
import com.finx.banking.dto.BankStatementPage;
import com.finx.banking.dto.BankStatementQuery;
import com.finx.banking.provider.BankingProvider;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.service.BankStatementIngestionService;
import com.finx.reconciliation.service.BankStatementPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BankStatementIngestionArchitectureTest {

    private static final Set<String> FORBIDDEN_SERVICE_DEPENDENCIES = Set.of(
            "com.finx.reconciliation.service.ReconciliationService",
            "com.finx.paymentorchestration.service.PaymentOrchestrationService",
            "com.finx.paymentorchestration.service.SettlementAuthorizationService",
            "com.finx.ledger.service.LedgerService",
            "com.finx.escrow.service.EscrowService",
            "com.finx.engine.service.EngineDecisionService",
            "com.finx.engine.service.ReleaseDecisionEngine",
            "com.finx.payment.service.RazorpayService"
    );

    @Test
    @DisplayName("1. BankStatementIngestionService does not depend on forbidden transaction/settlement/engine services")
    void testIngestionServiceIsolation() {
        Field[] fields = BankStatementIngestionService.class.getDeclaredFields();
        for (Field f : fields) {
            String typeName = f.getType().getName();
            assertThat(FORBIDDEN_SERVICE_DEPENDENCIES)
                    .as("BankStatementIngestionService should not depend on " + typeName)
                    .doesNotContain(typeName);
        }
    }

    @Test
    @DisplayName("2. BankStatementPersistenceService does not depend on BankingProvider (Network/DB separation)")
    void testPersistenceServiceNetworkIsolation() {
        Field[] fields = BankStatementPersistenceService.class.getDeclaredFields();
        for (Field f : fields) {
            assertThat(f.getType())
                    .as("BankStatementPersistenceService must not hold reference to BankingProvider")
                    .isNotEqualTo(BankingProvider.class);
        }
    }

    @Test
    @DisplayName("3. Sensitive banking and customer data fields are absent from durable statement entities and DTOs")
    void testSensitiveDataAbsence() {
        List<Class<?>> evidenceTypes = List.of(
                BankStatementQuery.class,
                BankStatementPage.class,
                BankStatementEntry.class,
                BankStatementImport.class,
                BankStatementLine.class
        );

        List<String> forbiddenFieldNames = List.of(
                "rawpayload", "rawresponse", "counterpartyaccount", "counterpartyname",
                "beneficiaryaccountnumber", "pan", "gstin", "kyc", "customeraccountnumber"
        );

        for (Class<?> clazz : evidenceTypes) {
            Set<String> fieldNames = Arrays.stream(clazz.getDeclaredFields())
                    .map(f -> f.getName().toLowerCase())
                    .collect(Collectors.toSet());

            for (String forbidden : forbiddenFieldNames) {
                assertThat(fieldNames)
                        .as("Class " + clazz.getSimpleName() + " must not contain sensitive field: " + forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    @DisplayName("4. Verify no @Scheduled annotations on ingestion or persistence services")
    void testNoAutomaticSchedulers() {
        Method[] ingestionMethods = BankStatementIngestionService.class.getDeclaredMethods();
        for (Method m : ingestionMethods) {
            assertThat(Arrays.stream(m.getDeclaredAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().contains("Scheduled")))
                    .isFalse();
        }

        Method[] persistenceMethods = BankStatementPersistenceService.class.getDeclaredMethods();
        for (Method m : persistenceMethods) {
            assertThat(Arrays.stream(m.getDeclaredAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().contains("Scheduled")))
                    .isFalse();
        }
    }
}
