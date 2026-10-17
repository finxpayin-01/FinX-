package com.finx.banking.contract;

import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.dto.BankStatementPage;
import com.finx.banking.dto.BankStatementQuery;
import com.finx.banking.transport.BankHttpTransport;

import java.time.LocalDate;
import java.util.List;

/**
 * Service Provider Interface (SPI) defining the contract for bank-specific payload mapping,
 * endpoint path resolution, status normalization, and webhook payload extraction.
 * <p>
 * CRITICAL RULE:
 * Bank-specific API contracts must be based on official partner specifications.
 * When official specifications are absent, implementations must fail closed without
 * guessing endpoints, payload fields, or status mappings.
 */
public interface BankApiContract {

    String getBankName();

    PaymentInstructionResult createPaymentInstruction(BankHttpTransport transport, PaymentInstructionRequest request);

    PaymentStatusResult getPaymentStatus(BankHttpTransport transport, String providerInstructionId);

    AccountDetailsResult getAccountDetails(BankHttpTransport transport, String virtualAccountNumber);

    BeneficiaryVerificationResult verifyBeneficiary(BankHttpTransport transport, BeneficiaryVerificationRequest request);

    SettlementResult initiateSettlement(BankHttpTransport transport, SettlementRequest request);

    SettlementStatusResult getSettlementStatus(BankHttpTransport transport, String providerSettlementId);

    SettlementLookupResult lookupSettlementByOperationKey(BankHttpTransport transport, String providerOperationKey);

    List<BankTransactionRecord> fetchTransactions(BankHttpTransport transport, LocalDate fromDate, LocalDate toDate);

    BankStatementPage fetchStatementPage(BankHttpTransport transport, BankStatementQuery query);

    boolean verifyWebhookSignature(String rawPayload, String signature, String timestamp);
}
