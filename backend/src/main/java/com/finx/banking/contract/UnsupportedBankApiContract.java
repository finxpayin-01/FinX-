package com.finx.banking.contract;

import com.finx.banking.capability.BankingCapabilityNotConfiguredException;
import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.dto.BankStatementPage;
import com.finx.banking.dto.BankStatementQuery;
import com.finx.banking.transport.BankHttpTransport;

import java.time.LocalDate;
import java.util.List;

/**
 * Fail-closed implementation of BankApiContract used when official partner bank specifications
 * are absent from the repository. Strictly refuses to invent endpoints, payloads, or signatures.
 */
public class UnsupportedBankApiContract implements BankApiContract {

    private static final String ERROR_MESSAGE =
            "OFFICIAL_BANK_API_CONTRACT_NOT_PRESENT: Official partner API contract is not present in repository. " +
            "No bank-specific endpoints, payloads, or signing formats are invented.";

    private final String bankName;

    public UnsupportedBankApiContract(String bankName) {
        this.bankName = (bankName != null && !bankName.isBlank()) ? bankName : "GENERIC_PARTNER";
    }

    @Override
    public String getBankName() {
        return bankName;
    }

    @Override
    public PaymentInstructionResult createPaymentInstruction(BankHttpTransport transport, PaymentInstructionRequest request) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public PaymentStatusResult getPaymentStatus(BankHttpTransport transport, String providerInstructionId) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public AccountDetailsResult getAccountDetails(BankHttpTransport transport, String virtualAccountNumber) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public BeneficiaryVerificationResult verifyBeneficiary(BankHttpTransport transport, BeneficiaryVerificationRequest request) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public SettlementResult initiateSettlement(BankHttpTransport transport, SettlementRequest request) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public SettlementStatusResult getSettlementStatus(BankHttpTransport transport, String providerSettlementId) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public SettlementLookupResult lookupSettlementByOperationKey(BankHttpTransport transport, String providerOperationKey) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public List<BankTransactionRecord> fetchTransactions(BankHttpTransport transport, LocalDate fromDate, LocalDate toDate) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public BankStatementPage fetchStatementPage(BankHttpTransport transport, BankStatementQuery query) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signature, String timestamp) {
        throw new BankingCapabilityNotConfiguredException(ERROR_MESSAGE);
    }
}
