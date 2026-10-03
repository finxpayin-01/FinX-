package com.finx.security;

import com.finx.banking.provider.BankingProvider;
import com.finx.deal.service.DealAccessService;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.reconciliation.service.ReconciliationService;
import com.finx.risk.service.RiskEvidenceReadService;
import com.finx.verification.service.VerificationEligibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture & Dependency Containment Test:
 * Proves that framework-facing evidence and evaluation services do NOT depend on,
 * import, inject, or reference financial execution or mutating components.
 */
public class RestrictedFrameworkArchitectureTest {

    private static final List<Class<?>> EVIDENCE_READ_SERVICES = List.of(
            RiskEvidenceReadService.class,
            ApprovalApplicabilityService.class,
            VerificationEligibilityService.class,
            DealAccessService.class
    );

    private static final List<Class<?>> PROHIBITED_MUTATING_DEPENDENCIES = List.of(
            BankingProvider.class,
            SettlementAuthorizationService.class,
            PaymentOrchestrationService.class,
            LedgerService.class,
            EscrowService.class,
            RazorpayService.class,
            PaymentService.class
    );

    @Test
    @DisplayName("1. Evidence read services must not declare fields of prohibited mutating components")
    void evidenceReadServicesMustNotDeclareFieldsOfProhibitedComponents() {
        for (Class<?> serviceClass : EVIDENCE_READ_SERVICES) {
            for (Field field : serviceClass.getDeclaredFields()) {
                for (Class<?> prohibited : PROHIBITED_MUTATING_DEPENDENCIES) {
                    assertThat(field.getType())
                            .withFailMessage("Service %s must not declare field of type %s",
                                    serviceClass.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }

    @Test
    @DisplayName("2. Evidence read services constructors must not inject prohibited mutating components")
    void evidenceReadServicesConstructorsMustNotInjectProhibitedComponents() {
        for (Class<?> serviceClass : EVIDENCE_READ_SERVICES) {
            for (Constructor<?> ctor : serviceClass.getDeclaredConstructors()) {
                for (Class<?> paramType : ctor.getParameterTypes()) {
                    for (Class<?> prohibited : PROHIBITED_MUTATING_DEPENDENCIES) {
                        assertThat(paramType)
                                .withFailMessage("Service %s constructor must not accept parameter of type %s",
                                        serviceClass.getSimpleName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("3. Evidence read services methods must not return prohibited mutating components")
    void evidenceReadServicesMethodsMustNotReturnProhibitedComponents() {
        for (Class<?> serviceClass : EVIDENCE_READ_SERVICES) {
            for (Method method : serviceClass.getDeclaredMethods()) {
                for (Class<?> prohibited : PROHIBITED_MUTATING_DEPENDENCIES) {
                    assertThat(method.getReturnType())
                            .withFailMessage("Service %s method %s must not return type %s",
                                    serviceClass.getSimpleName(), method.getName(), prohibited.getSimpleName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }

    @Test
    @DisplayName("4. RiskEvidenceReadService must not depend on ReconciliationService (D03 nonauthoritative)")
    void riskEvidenceReadServiceMustNotDependOnReconciliationService() {
        for (Field field : RiskEvidenceReadService.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .withFailMessage("RiskEvidenceReadService must not depend on nonauthoritative ReconciliationService")
                    .isNotEqualTo(ReconciliationService.class);
        }
    }

    @Test
    @DisplayName("5. ApprovalApplicabilityService must not depend on ReconciliationService (D03 nonauthoritative)")
    void approvalApplicabilityServiceMustNotDependOnReconciliationService() {
        for (Field field : ApprovalApplicabilityService.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .withFailMessage("ApprovalApplicabilityService must not depend on nonauthoritative ReconciliationService")
                    .isNotEqualTo(ReconciliationService.class);
        }
    }

    @Test
    @DisplayName("6. VerificationEligibilityService must fail closed on production money movement")
    void verificationEligibilityServiceFailsClosedOnProductionMoneyMovement() {
        VerificationEligibilityService service = new VerificationEligibilityService(
                null, null, null, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(service::validateProductionMoneyMovement)
                .isInstanceOf(com.finx.exception.BadRequestException.class)
                .hasMessageContaining("BANKING_PARTNER_NOT_CONFIGURED")
                .hasMessageContaining("Production money movement is disabled");
    }

    @Test
    @DisplayName("7. SettlementAuthorizationService payout execution remains completely disabled")
    void settlementAuthorizationServicePayoutExecutionRemainsDisabled() {
        SettlementAuthorizationService service = new SettlementAuthorizationService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.authorizeAndInitiateSettlement(null, null))
                .isInstanceOf(com.finx.exception.BadRequestException.class)
                .hasMessageContaining("Mandatory idempotency key is missing or blank");
    }

    @Test
    @DisplayName("8. No blockchain executor is present or referenced by evidence services")
    void noBlockchainExecutorReferencedByEvidenceServices() {
        for (Class<?> serviceClass : EVIDENCE_READ_SERVICES) {
            for (Field field : serviceClass.getDeclaredFields()) {
                assertThat(field.getType().getName().toLowerCase())
                        .withFailMessage("Service %s must not reference blockchain types", serviceClass.getSimpleName())
                        .doesNotContain("blockchain")
                        .doesNotContain("web3")
                        .doesNotContain("ethereum")
                        .doesNotContain("solidity");
            }
        }
    }
}
