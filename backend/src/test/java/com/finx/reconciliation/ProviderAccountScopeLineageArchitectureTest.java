package com.finx.reconciliation;

import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class ProviderAccountScopeLineageArchitectureTest {

    @Test
    @DisplayName("1. PaymentInstruction has explicit providerAccountId mapped to provider_account_id")
    void testPaymentInstructionProviderAccountField() throws Exception {
        Field field = PaymentInstruction.class.getDeclaredField("providerAccountId");
        assertThat(field).isNotNull();
        assertThat(field.getType()).isEqualTo(String.class);

        Column col = field.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("provider_account_id");
        assertThat(col.length()).isEqualTo(128);
    }

    @Test
    @DisplayName("2. SettlementInstruction has explicit providerAccountId mapped to provider_account_id")
    void testSettlementInstructionProviderAccountField() throws Exception {
        Field field = SettlementInstruction.class.getDeclaredField("providerAccountId");
        assertThat(field).isNotNull();
        assertThat(field.getType()).isEqualTo(String.class);

        Column col = field.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("provider_account_id");
        assertThat(col.length()).isEqualTo(128);
    }

    @Test
    @DisplayName("3. PaymentTransaction retains paymentInstructionId FK lineage and has NO duplicate providerAccountId column")
    void testPaymentTransactionLineageArchitecture() throws Exception {
        Field piFkField = PaymentTransaction.class.getDeclaredField("paymentInstructionId");
        assertThat(piFkField).isNotNull();

        Column col = piFkField.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("payment_instruction_id");

        Set<String> ptFields = Arrays.stream(PaymentTransaction.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(ptFields).doesNotContain("providerAccountId");
        assertThat(ptFields).doesNotContain("provider_account_id");
    }

    @Test
    @DisplayName("4. Verify forbidden components (Matcher, ReconciliationDecisionAdapter, ReleaseDecisionEngine) are NOT present")
    void testForbiddenComponentsAbsent() {
        List<String> forbiddenClasses = List.of(
                "com.finx.reconciliation.engine.DeterministicReconciliationMatcher",
                "com.finx.reconciliation.engine.ReconciliationMatcher",
                "com.finx.engine.adapter.ReconciliationDecisionAdapter",
                "com.finx.engine.service.ReleaseDecisionEngine"
        );

        for (String fqcn : forbiddenClasses) {
            assertThat(isClassPresent(fqcn))
                    .as("Forbidden class must NOT exist in R2.5: " + fqcn)
                    .isFalse();
        }
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
