package com.finx.banking.provider;

import com.finx.banking.dto.BankingDtos.*;

import java.time.LocalDate;
import java.util.List;

public interface BankingProvider {

    String getProviderName();

    com.finx.banking.capability.BankingProviderCapabilities getCapabilities();

    default boolean supports(com.finx.banking.capability.BankingProviderCapability capability) {
        return getCapabilities() != null && getCapabilities().supports(capability);
    }

    PaymentInstructionResult createPaymentInstruction(PaymentInstructionRequest request);

    PaymentStatusResult getPaymentStatus(String providerInstructionId);

    AccountDetailsResult getAccountDetails(String virtualAccountNumber);

    BeneficiaryVerificationResult verifyBeneficiary(BeneficiaryVerificationRequest request);

    SettlementResult initiateSettlement(SettlementRequest request);

    SettlementStatusResult getSettlementStatus(String providerSettlementId);

    default SettlementLookupResult lookupSettlementByOperationKey(String providerOperationKey) {
        return SettlementLookupResult.unsupported();
    }

    List<BankTransactionRecord> fetchTransactions(LocalDate fromDate, LocalDate toDate);

    com.finx.banking.dto.BankStatementPage fetchStatementPage(com.finx.banking.dto.BankStatementQuery query);

    boolean verifyWebhookSignature(String rawPayload, String signature, String timestamp);
}
