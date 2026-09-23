package com.finx.banking;

import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.provider.mock.MockBankingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockBankingProviderTest {

    private MockBankingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockBankingProvider("test-webhook-secret-key-12345");
    }

    @Test
    @DisplayName("Should create virtual account payment instruction in sandbox mode")
    void shouldCreatePaymentInstruction() {
        PaymentInstructionRequest req = new PaymentInstructionRequest(
                UUID.randomUUID(),
                null,
                "FINX-INS-2026-0001",
                new BigDecimal("100000.00"),
                "INR",
                "Buyer Corp Pvt Ltd",
                "Vendor Soln Pvt Ltd"
        );

        PaymentInstructionResult result = provider.createPaymentInstruction(req);

        assertThat(result).isNotNull();
        assertThat(result.providerInstructionId()).startsWith("MOCK-INS-");
        assertThat(result.virtualAccountNumber()).startsWith("FINX");
        assertThat(result.ifscCode()).isEqualTo("FINX0001001");
        assertThat(result.status()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("Should successfully simulate penny-drop beneficiary verification")
    void shouldVerifyBeneficiaryPennyDrop() {
        BeneficiaryVerificationRequest req = new BeneficiaryVerificationRequest(
                "Vendor Enterprise Pvt Ltd",
                "987654321012",
                "HDFC0001234",
                "PRIVATE_LIMITED",
                "AABBA5678B"
        );

        BeneficiaryVerificationResult result = provider.verifyBeneficiary(req);

        assertThat(result.verified()).isTrue();
        assertThat(result.registeredName()).isEqualTo("VENDOR ENTERPRISE PVT LTD");
        assertThat(result.verificationReference()).startsWith("MOCK-PENNY-");
    }

    @Test
    @DisplayName("Should reject beneficiary verification if account or IFSC is blank")
    void shouldRejectInvalidBeneficiary() {
        BeneficiaryVerificationRequest req = new BeneficiaryVerificationRequest(
                "Vendor Enterprise Pvt Ltd",
                "",
                "",
                "PRIVATE_LIMITED",
                "AABBA5678B"
        );

        BeneficiaryVerificationResult result = provider.verifyBeneficiary(req);

        assertThat(result.verified()).isFalse();
        assertThat(result.message()).contains("Invalid account number or IFSC code");
    }

    @Test
    @DisplayName("Should initiate settlement and return UTR number")
    void shouldInitiateSettlement() {
        SettlementRequest req = new SettlementRequest(
                "FINX-SET-2026-0001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.00"),
                "INR",
                "Vendor Enterprise Pvt Ltd",
                "987654321012",
                "HDFC0001234",
                "IDEMP-SET-101"
        );

        SettlementResult result = provider.initiateSettlement(req);

        assertThat(result.status()).isEqualTo("SETTLED");
        assertThat(result.providerSettlementId()).startsWith("MOCK-SETTL-");
        assertThat(result.utrNumber()).startsWith("UTR-MOCK-");
    }

    @Test
    @DisplayName("Should validate webhook signatures in sandbox")
    void shouldValidateWebhookSignature() {
        String payload = "{\"event\":\"payment.success\",\"amount\":100000}";
        String timestamp = String.valueOf(System.currentTimeMillis());

        boolean isValid = provider.verifyWebhookSignature(payload, "mock-valid-signature", timestamp);
        assertThat(isValid).isTrue();

        boolean isValidSecret = provider.verifyWebhookSignature(payload, "test-webhook-secret-key-12345", timestamp);
        assertThat(isValidSecret).isTrue();

        boolean isInvalid = provider.verifyWebhookSignature(payload, "wrong-signature", timestamp);
        assertThat(isInvalid).isFalse();
    }
}
