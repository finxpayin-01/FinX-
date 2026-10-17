package com.finx.release.certification;

import com.finx.banking.capability.BankingProviderCapabilities;
import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.contract.BankApiContract;
import com.finx.banking.contract.UnsupportedBankApiContract;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.banking.readiness.BankingProviderReadinessService;
import com.finx.banking.transport.BankHttpTransport;
import com.finx.release.execution.gate.ProductionSettlementDispatchGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionCertificationGateTest {

    /**
     * Test-only certified bank API contract used strictly in isolated unit testing to prove
     * mathematical gate evaluation without external network calls or credentials.
     */
    private static class CertifiedTestBankApiContract implements BankApiContract {
        @Override
        public String getBankName() {
            return "CERTIFIED_TEST_BANK";
        }

        @Override
        public com.finx.banking.dto.BankingDtos.PaymentInstructionResult createPaymentInstruction(BankHttpTransport transport, com.finx.banking.dto.BankingDtos.PaymentInstructionRequest request) {
            return new com.finx.banking.dto.BankingDtos.PaymentInstructionResult("TX-1", "VA-1", "IFSC01", "SUBMITTED", "url", null);
        }

        @Override
        public com.finx.banking.dto.BankingDtos.PaymentStatusResult getPaymentStatus(BankHttpTransport transport, String providerInstructionId) {
            return new com.finx.banking.dto.BankingDtos.PaymentStatusResult(providerInstructionId, "COMPLETED", "UTR-1", BigDecimal.TEN, Instant.now(), null);
        }

        @Override
        public com.finx.banking.dto.BankingDtos.AccountDetailsResult getAccountDetails(BankHttpTransport transport, String virtualAccountNumber) {
            return new com.finx.banking.dto.BankingDtos.AccountDetailsResult(virtualAccountNumber, "IFSC01", "Holder", BigDecimal.TEN, "ACTIVE");
        }

        @Override
        public com.finx.banking.dto.BankingDtos.BeneficiaryVerificationResult verifyBeneficiary(BankHttpTransport transport, com.finx.banking.dto.BankingDtos.BeneficiaryVerificationRequest request) {
            return new com.finx.banking.dto.BankingDtos.BeneficiaryVerificationResult(true, "Holder", "ref", "OK", null);
        }

        @Override
        public com.finx.banking.dto.BankingDtos.SettlementResult initiateSettlement(BankHttpTransport transport, com.finx.banking.dto.BankingDtos.SettlementRequest request) {
            return new com.finx.banking.dto.BankingDtos.SettlementResult("CERT-SETTLE-1", "SUBMITTED", "UTR-123456789012", Instant.now(), null);
        }

        @Override
        public com.finx.banking.dto.BankingDtos.SettlementStatusResult getSettlementStatus(BankHttpTransport transport, String providerSettlementId) {
            return new com.finx.banking.dto.BankingDtos.SettlementStatusResult(providerSettlementId, "SETTLED", "UTR-123456789012", Instant.now(), null);
        }

        @Override
        public com.finx.banking.dto.BankingDtos.SettlementLookupResult lookupSettlementByOperationKey(BankHttpTransport transport, String providerOperationKey) {
            return com.finx.banking.dto.BankingDtos.SettlementLookupResult.found(
                    new com.finx.banking.dto.BankingDtos.SettlementStatusResult("CERT-SETTLE-1", "SETTLED", "UTR-123456789012", Instant.now(), null)
            );
        }

        @Override
        public java.util.List<com.finx.banking.dto.BankingDtos.BankTransactionRecord> fetchTransactions(BankHttpTransport transport, java.time.LocalDate fromDate, java.time.LocalDate toDate) {
            return Collections.emptyList();
        }

        @Override
        public com.finx.banking.dto.BankStatementPage fetchStatementPage(BankHttpTransport transport, com.finx.banking.dto.BankStatementQuery query) {
            return new com.finx.banking.dto.BankStatementPage("ACC-1", "REF-1", "INR", BigDecimal.ZERO, BigDecimal.ZERO, Collections.emptyList(), null);
        }

        @Override
        public boolean verifyWebhookSignature(String rawPayload, String signature, String timestamp) {
            return true;
        }
    }

    private ProductionCertificationProperties createFullCertifiedProperties() {
        ProductionCertificationProperties props = new ProductionCertificationProperties();
        props.setTechnicalCertified(true);
        props.setBankContractCertified(true);
        props.setBankUatCertified(true);
        props.setSecurityCertified(true);
        props.setComplianceCertified(true);
        props.setExecutiveSignoff(true);
        props.setIncidentRunbookCertified(true);
        props.setReconciliationCertified(true);
        return props;
    }

    private BankingPartnerProperties createProductionPartnerProperties() {
        BankingPartnerProperties props = new BankingPartnerProperties();
        props.setEnabled(true);
        props.setProviderName("CERTIFIED_TEST_BANK");
        props.setSandbox(false);
        props.setProductionExecutionEnabled(true);
        props.getMtls().setEnabled(true);
        props.getSigning().setEnabled(true);
        return props;
    }

    private PartnerBankingProvider createCertifiedPartnerProvider(BankingPartnerProperties props, BankApiContract contract, Set<BankingProviderCapability> caps) {
        return new PartnerBankingProvider(
                props,
                new BankHttpTransport(Mockito.mock(WebClient.class), "CERTIFIED_TEST_BANK", "c1"),
                contract,
                BankingProviderCapabilities.of(caps)
        );
    }

    @Test
    @DisplayName("Master production flag false: Gate CLOSED")
    void testMasterFlagFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, false);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, false, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(false, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireProductionDispatchAllowed).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("E12 certified flag false: Gate CLOSED")
    void testE12False_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, false);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, false, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Mock provider: Gate CLOSED")
    void testMockProvider_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        MockBankingProvider mock = new MockBankingProvider("secret");

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(mock, null, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, mock, null, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Partner in sandbox mode: Gate CLOSED")
    void testPartnerSandbox_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        partnerProps.setSandbox(true);
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("UnsupportedBankApiContract: Gate CLOSED")
    void testUnsupportedContract_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new UnsupportedBankApiContract("PARTNER"), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Missing mTLS: Gate CLOSED")
    void testMissingMtls_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        partnerProps.getMtls().setEnabled(false);
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Missing request signing: Gate CLOSED")
    void testMissingSigning_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        partnerProps.getSigning().setEnabled(false);
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Missing SETTLEMENT_OPERATION_LOOKUP capability: Gate CLOSED")
    void testMissingOperationLookup_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        Set<BankingProviderCapability> caps = EnumSet.of(
                BankingProviderCapability.SETTLEMENT_INITIATE,
                BankingProviderCapability.SETTLEMENT_STATUS_QUERY,
                BankingProviderCapability.WEBHOOK_SIGNATURE_VERIFICATION,
                BankingProviderCapability.STATEMENT_PAGING
        );
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), caps);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Missing STATEMENT_PAGING capability: Gate CLOSED")
    void testMissingStatementPaging_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        Set<BankingProviderCapability> caps = EnumSet.of(
                BankingProviderCapability.SETTLEMENT_INITIATE,
                BankingProviderCapability.SETTLEMENT_STATUS_QUERY,
                BankingProviderCapability.SETTLEMENT_OPERATION_LOOKUP,
                BankingProviderCapability.WEBHOOK_SIGNATURE_VERIFICATION
        );
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), caps);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Bank contract certification false: Gate CLOSED")
    void testBankContractCertificationFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        certProps.setBankContractCertified(false);
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Bank UAT certification false: Gate CLOSED")
    void testBankUatCertificationFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        certProps.setBankUatCertified(false);
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Security certification false: Gate CLOSED")
    void testSecurityCertificationFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        certProps.setSecurityCertified(false);
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Compliance certification false: Gate CLOSED")
    void testComplianceCertificationFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        certProps.setComplianceCertified(false);
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Executive signoff false: Gate CLOSED")
    void testExecutiveSignoffFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        certProps.setExecutiveSignoff(false);
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Incident runbook certified false: Gate CLOSED")
    void testRunbookCertifiedFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        certProps.setIncidentRunbookCertified(false);
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Reconciliation certified false: Gate CLOSED")
    void testReconciliationCertifiedFalse_GateClosed() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        certProps.setReconciliationCertified(false);
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Isolated unit test: ALL conditions certified with test partner contract evaluates gate OPEN")
    void testAllConditionsCertified_GateEvaluatesOpen() {
        ProductionCertificationProperties certProps = createFullCertifiedProperties();
        BankingPartnerProperties partnerProps = createProductionPartnerProperties();
        PartnerBankingProvider partner = createCertifiedPartnerProvider(partnerProps, new CertifiedTestBankApiContract(), BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES);

        BankingProviderReadinessService readinessService = new BankingProviderReadinessService(partner, partnerProps, true);
        ProductionReadinessCertificationService certService = new ProductionReadinessCertificationService(readinessService, certProps, true, true);
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(true, partner, partnerProps, true, certService);

        ProductionCertificationResult result = certService.evaluateCertification();
        assertThat(result.gateOpen()).isTrue();
        assertThat(result.blockers()).isEmpty();
        assertThat(gate.isProductionDispatchAllowed()).isTrue();
    }
}
