package com.finx.banking.provider.partner;

import com.finx.banking.capability.BankingProviderCapabilities;
import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.capability.BankingProviderCapabilityGuard;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.contract.BankApiContract;
import com.finx.banking.dto.BankStatementPage;
import com.finx.banking.dto.BankStatementQuery;
import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.transport.BankHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Production-grade banking provider adapter for regulated financial institutions.
 * Delegates low-level transport to BankHttpTransport and bank-specific mapping to BankApiContract.
 * <p>
 * STRICT INVARIANTS:
 * 1. Fails closed for any operation not explicitly supported by its capability set or contract.
 * 2. Never fabricates fake remote success, fake UTRs, or fake settled states.
 * 3. Requires and propagates FinX stable provider operation identity: FINX-REL-{releaseInstructionId}.
 * 4. Never logs or leaks PII, credentials, private keys, or raw bank payloads.
 */
public class PartnerBankingProvider implements BankingProvider {

    private static final Logger log = LoggerFactory.getLogger(PartnerBankingProvider.class);

    private final BankingPartnerProperties properties;
    private final BankHttpTransport transport;
    private final BankApiContract contract;
    private final BankingProviderCapabilities capabilities;

    public PartnerBankingProvider(
            BankingPartnerProperties properties,
            BankHttpTransport transport,
            BankApiContract contract,
            BankingProviderCapabilities capabilities
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.contract = Objects.requireNonNull(contract, "contract must not be null");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
    }

    @Override
    public String getProviderName() {
        return properties.getProviderName() != null && !properties.getProviderName().isBlank()
                ? properties.getProviderName()
                : "PARTNER";
    }

    @Override
    public BankingProviderCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public PaymentInstructionResult createPaymentInstruction(PaymentInstructionRequest request) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.PAYMENT_INSTRUCTION_CREATE);
        return contract.createPaymentInstruction(transport, request);
    }

    @Override
    public PaymentStatusResult getPaymentStatus(String providerInstructionId) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.PAYMENT_STATUS_QUERY);
        return contract.getPaymentStatus(transport, providerInstructionId);
    }

    @Override
    public AccountDetailsResult getAccountDetails(String virtualAccountNumber) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.ACCOUNT_DETAILS_QUERY);
        return contract.getAccountDetails(transport, virtualAccountNumber);
    }

    @Override
    public BeneficiaryVerificationResult verifyBeneficiary(BeneficiaryVerificationRequest request) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.BENEFICIARY_VERIFICATION);
        return contract.verifyBeneficiary(transport, request);
    }

    @Override
    public SettlementResult initiateSettlement(SettlementRequest request) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.SETTLEMENT_INITIATE);
        Objects.requireNonNull(request, "SettlementRequest must not be null");

        // Validate stable idempotency key propagation
        String idempotencyKey = request.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("STABLE_OPERATION_ID_REQUIRED: Partner settlement requires non-blank idempotency key");
        }
        if (!idempotencyKey.startsWith("FINX-REL-")) {
            throw new IllegalArgumentException("STABLE_OPERATION_ID_REQUIRED: Partner settlement requires FINX-REL-{id} stable operation identity, got: " + idempotencyKey);
        }

        return contract.initiateSettlement(transport, request);
    }

    @Override
    public SettlementStatusResult getSettlementStatus(String providerSettlementId) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.SETTLEMENT_STATUS_QUERY);
        return contract.getSettlementStatus(transport, providerSettlementId);
    }

    @Override
    public SettlementLookupResult lookupSettlementByOperationKey(String providerOperationKey) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.SETTLEMENT_OPERATION_LOOKUP);
        return contract.lookupSettlementByOperationKey(transport, providerOperationKey);
    }

    @Override
    public List<BankTransactionRecord> fetchTransactions(LocalDate fromDate, LocalDate toDate) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.TRANSACTION_FEED);
        return contract.fetchTransactions(transport, fromDate, toDate);
    }

    @Override
    public BankStatementPage fetchStatementPage(BankStatementQuery query) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.STATEMENT_PAGING);
        return contract.fetchStatementPage(transport, query);
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signature, String timestamp) {
        BankingProviderCapabilityGuard.requireCapability(this, BankingProviderCapability.WEBHOOK_SIGNATURE_VERIFICATION);
        return contract.verifyWebhookSignature(rawPayload, signature, timestamp);
    }

    public BankingPartnerProperties getProperties() {
        return properties;
    }

    public BankHttpTransport getTransport() {
        return transport;
    }

    public BankApiContract getContract() {
        return contract;
    }
}
