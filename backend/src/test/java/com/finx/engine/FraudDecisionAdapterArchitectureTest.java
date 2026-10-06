package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.adapter.FraudDecisionAdapter;
import com.finx.engine.adapter.FraudDecisionEvidenceReader;
import com.finx.escrow.service.EscrowService;
import com.finx.invoice.matching.InvoiceMatchingEngine;
import com.finx.invoice.service.InvoiceService;
import com.finx.ledger.service.LedgerService;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.engine.RiskRuleEngine;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.service.RiskEvidenceReadService;
import com.finx.risk.service.RiskScoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class FraudDecisionAdapterArchitectureTest {

    private static final List<Class<?>> FRAUD_ADAPTER_CLASSES = List.of(
            FraudDecisionAdapter.class,
            FraudDecisionEvidenceReader.class
    );

    private static final List<Class<?>> PROHIBITED_TYPES = List.of(
            BankingProvider.class,
            SettlementAuthorizationService.class,
            PaymentOrchestrationService.class,
            LedgerService.class,
            EscrowService.class,
            RazorpayService.class,
            PaymentService.class,
            PaymentTransactionRepository.class,
            SettlementInstructionRepository.class,
            RiskScoringService.class,
            RiskEvidenceReadService.class,
            RiskRuleEngine.class,
            RiskAssessment.class,
            RiskScore.class,
            InvoiceMatchingEngine.class,
            InvoiceService.class
    );

    @Test
    @DisplayName("1. Fraud adapter classes must not declare fields of prohibited execution or derived components")
    void fraudAdapterClassesMustNotDeclareProhibitedFields() {
        for (Class<?> clazz : FRAUD_ADAPTER_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_TYPES) {
                    assertThat(field.getType())
                            .withFailMessage("Fraud class %s must not declare field of prohibited type %s",
                                    clazz.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }

                String fieldTypeName = field.getType().getName();
                assertThat(fieldTypeName)
                        .withFailMessage("Fraud class %s must not declare banking/payment/risk/reconciliation/blockchain fields: %s",
                                clazz.getSimpleName(), fieldTypeName)
                        .doesNotContain("BankingProvider")
                        .doesNotContain("SettlementAuthorizationService")
                        .doesNotContain("PaymentOrchestrationService")
                        .doesNotContain("LedgerService")
                        .doesNotContain("EscrowService")
                        .doesNotContain("Razorpay")
                        .doesNotContain("Reconciliation")
                        .doesNotContain("RiskScoringService")
                        .doesNotContain("RiskAssessment")
                        .doesNotContain("RiskScore")
                        .doesNotContain("InvoiceMatchingEngine")
                        .doesNotContain("blockchain")
                        .doesNotContain("Controller");
            }
        }
    }

    @Test
    @DisplayName("2. Fraud adapter constructors must not accept prohibited components")
    void fraudAdapterConstructorsMustNotAcceptProhibitedComponents() {
        for (Class<?> clazz : FRAUD_ADAPTER_CLASSES) {
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                for (Parameter param : constructor.getParameters()) {
                    for (Class<?> prohibited : PROHIBITED_TYPES) {
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
                            .doesNotContain("Reconciliation")
                            .doesNotContain("Risk")
                            .doesNotContain("InvoiceMatchingEngine");
                }
            }
        }
    }

    @Test
    @DisplayName("3. Fraud adapter methods must not return or take prohibited types")
    void fraudAdapterMethodsMustNotUseProhibitedTypes() {
        for (Class<?> clazz : FRAUD_ADAPTER_CLASSES) {
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
                                .withFailMessage("Method %s parameter %s in %s must not use prohibited type %s",
                                        method.getName(), param.getName(), clazz.getSimpleName(), prohibited.getSimpleName())
                                .isNotEqualTo(prohibited);
                    }
                }
            }
        }
    }
}
