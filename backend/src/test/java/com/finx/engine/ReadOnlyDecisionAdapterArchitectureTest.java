package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.adapter.DocumentDecisionAdapter;
import com.finx.engine.adapter.DomainEvaluationResult;
import com.finx.engine.adapter.ReadOnlyDecisionAdapter;
import com.finx.engine.adapter.ReadOnlyDomainDecisionCoordinator;
import com.finx.engine.adapter.RiskDecisionAdapter;
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
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReadOnlyDecisionAdapterArchitectureTest {

    private static final List<Class<?>> ADAPTER_CLASSES = List.of(
            ReadOnlyDecisionAdapter.class,
            RiskDecisionAdapter.class,
            DocumentDecisionAdapter.class,
            DomainEvaluationResult.class,
            ReadOnlyDomainDecisionCoordinator.class
    );

    private static final List<Class<?>> PROHIBITED_FINANCIAL_TYPES = List.of(
            BankingProvider.class,
            SettlementAuthorizationService.class,
            PaymentOrchestrationService.class,
            LedgerService.class,
            EscrowService.class,
            RazorpayService.class,
            PaymentService.class
    );

    @Test
    @DisplayName("1. Adapter classes must not declare fields of prohibited financial execution components")
    void adapterClassesMustNotDeclareProhibitedFields() {
        for (Class<?> clazz : ADAPTER_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_FINANCIAL_TYPES) {
                    assertThat(field.getType())
                            .withFailMessage("Adapter class %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }

                String fieldTypeName = field.getType().getName();
                assertThat(fieldTypeName)
                        .withFailMessage("Adapter class %s must not declare banking/payment/reconciliation/blockchain fields: %s",
                                clazz.getSimpleName(), fieldTypeName)
                        .doesNotContain("BankingProvider")
                        .doesNotContain("SettlementAuthorizationService")
                        .doesNotContain("PaymentOrchestrationService")
                        .doesNotContain("LedgerService")
                        .doesNotContain("EscrowService")
                        .doesNotContain("Razorpay")
                        .doesNotContain("ReconciliationService")
                        .doesNotContain("blockchain")
                        .doesNotContain("Controller");
            }
        }
    }

    @Test
    @DisplayName("2. Adapter constructors must not accept prohibited financial execution components")
    void adapterConstructorsMustNotAcceptProhibitedComponents() {
        for (Class<?> clazz : ADAPTER_CLASSES) {
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                for (Parameter param : constructor.getParameters()) {
                    for (Class<?> prohibited : PROHIBITED_FINANCIAL_TYPES) {
                        assertThat(param.getType())
                                .withFailMessage("Constructor in %s must not accept prohibited type %s",
                                        clazz.getSimpleName(), prohibited.getSimpleName())
                                .isNotEqualTo(prohibited);
                    }
                    String paramTypeName = param.getType().getName();
                    assertThat(paramTypeName)
                            .withFailMessage("Constructor parameter %s in %s is prohibited",
                                    paramTypeName, clazz.getSimpleName())
                            .doesNotContain("BankingProvider")
                            .doesNotContain("SettlementAuthorizationService")
                            .doesNotContain("PaymentOrchestrationService")
                            .doesNotContain("LedgerService")
                            .doesNotContain("EscrowService")
                            .doesNotContain("Razorpay")
                            .doesNotContain("ReconciliationService")
                            .doesNotContain("blockchain")
                            .doesNotContain("Controller");
                }
            }
        }
    }

    @Test
    @DisplayName("3. Adapter methods must not return or accept prohibited financial execution types")
    void adapterMethodsMustNotUseProhibitedTypes() {
        for (Class<?> clazz : ADAPTER_CLASSES) {
            for (Method method : clazz.getDeclaredMethods()) {
                for (Class<?> prohibited : PROHIBITED_FINANCIAL_TYPES) {
                    assertThat(method.getReturnType())
                            .withFailMessage("Method %s in %s must not return prohibited type %s",
                                    method.getName(), clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }

                for (Parameter param : method.getParameters()) {
                    for (Class<?> prohibited : PROHIBITED_FINANCIAL_TYPES) {
                        assertThat(param.getType())
                                .withFailMessage("Method %s in %s must not accept prohibited parameter %s",
                                        method.getName(), clazz.getSimpleName(), prohibited.getSimpleName())
                                .isNotEqualTo(prohibited);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("4. Risk and Document adapters must implement ReadOnlyDecisionAdapter")
    void adaptersMustImplementContract() {
        assertThat(ReadOnlyDecisionAdapter.class.isAssignableFrom(RiskDecisionAdapter.class)).isTrue();
        assertThat(ReadOnlyDecisionAdapter.class.isAssignableFrom(DocumentDecisionAdapter.class)).isTrue();
    }
}
