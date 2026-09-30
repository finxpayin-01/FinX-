package com.finx.banking.provider.mock;

import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.provider.BankingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "finx.banking.provider", havingValue = "mock", matchIfMissing = true)
public class MockBankingProvider implements BankingProvider {

    private static final Logger log = LoggerFactory.getLogger(MockBankingProvider.class);

    private final String webhookSecret;
    private final Map<String, PaymentInstructionResult> virtualAccounts = new ConcurrentHashMap<>();
    private final Map<String, SettlementResult> settlements = new ConcurrentHashMap<>();
    private final List<BankTransactionRecord> simulatedBankFeed = Collections.synchronizedList(new ArrayList<>());

    public MockBankingProvider(@Value("${finx.banking.webhook-secret:mock-banking-webhook-secret-key-32chars}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public PaymentInstructionResult createPaymentInstruction(PaymentInstructionRequest request) {
        String providerInstructionId = "MOCK-INS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String virtualAccountNumber = "FINX" + Math.abs(request.instructionReference().hashCode() % 10000000000L);
        String ifscCode = "FINX0001001";

        PaymentInstructionResult result = new PaymentInstructionResult(
                providerInstructionId,
                virtualAccountNumber,
                ifscCode,
                "CREATED",
                "https://sandbox.finx.bank/pay/" + providerInstructionId,
                "{\"provider\": \"MOCK\", \"status\": \"CREATED\", \"mode\": \"SANDBOX\"}"
        );

        virtualAccounts.put(providerInstructionId, result);
        log.info("[SANDBOX / TEST MODE] Created mock payment instruction: id={} VA={} IFSC={}",
                providerInstructionId, virtualAccountNumber, ifscCode);
        return result;
    }

    @Override
    public PaymentStatusResult getPaymentStatus(String providerInstructionId) {
        String utr = "UTR-MOCK-PAY-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        return new PaymentStatusResult(
                providerInstructionId,
                "SUCCESS",
                utr,
                BigDecimal.valueOf(100000),
                Instant.now(),
                "{\"provider\": \"MOCK\", \"status\": \"SUCCESS\", \"utr\": \"" + utr + "\"}"
        );
    }

    @Override
    public AccountDetailsResult getAccountDetails(String virtualAccountNumber) {
        return new AccountDetailsResult(
                virtualAccountNumber,
                "FINX0001001",
                "FINX ESCROW NODAL POOL (MOCK)",
                BigDecimal.valueOf(50000000),
                "ACTIVE"
        );
    }

    @Override
    public BeneficiaryVerificationResult verifyBeneficiary(BeneficiaryVerificationRequest request) {
        if (request.accountNumber() == null || request.accountNumber().isBlank() ||
                request.ifscCode() == null || request.ifscCode().isBlank()) {
            return new BeneficiaryVerificationResult(
                    false,
                    null,
                    null,
                    "Invalid account number or IFSC code",
                    "{\"status\": \"FAILED\"}"
            );
        }

        String ref = "MOCK-PENNY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[SANDBOX / TEST MODE] Simulated penny drop verification for account {} IFSC {}: SUCCESS",
                request.accountNumber(), request.ifscCode());

        return new BeneficiaryVerificationResult(
                true,
                request.accountHolderName().toUpperCase(Locale.ROOT),
                ref,
                "Penny drop verified successfully (Mock Bank Nodal Gateway)",
                "{\"status\": \"VERIFIED\", \"pennyDropAmount\": 1.00, \"ref\": \"" + ref + "\"}"
        );
    }

    @Override
    public SettlementResult initiateSettlement(SettlementRequest request) {
        String providerSettlementId = "MOCK-SETTL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String utr = "UTR-MOCK-STL-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        SettlementResult result = new SettlementResult(
                providerSettlementId,
                "SETTLED",
                utr,
                Instant.now(),
                "{\"provider\": \"MOCK\", \"status\": \"SETTLED\", \"utr\": \"" + utr + "\"}"
        );

        settlements.put(providerSettlementId, result);

        // Record in simulated bank feed for reconciliation
        simulatedBankFeed.add(new BankTransactionRecord(
                request.settlementReference(),
                utr,
                request.beneficiaryAccountNumber(),
                request.amount(),
                request.currency(),
                "DEBIT",
                Instant.now(),
                request.beneficiaryAccountNumber(),
                "Settlement payout for deal " + request.dealId()
        ));

        log.info("[SANDBOX / TEST MODE] Mock bank settlement initiated and settled: id={} utr={} amount={}",
                providerSettlementId, utr, request.amount());

        return result;
    }

    @Override
    public SettlementStatusResult getSettlementStatus(String providerSettlementId) {
        SettlementResult existing = settlements.get(providerSettlementId);
        if (existing != null) {
            return new SettlementStatusResult(
                    existing.providerSettlementId(),
                    existing.status(),
                    existing.utrNumber(),
                    existing.initiatedAt(),
                    null
            );
        }
        return new SettlementStatusResult(
                providerSettlementId,
                "SETTLED",
                "UTR-MOCK-SET-" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now(),
                null
        );
    }

    @Override
    public List<BankTransactionRecord> fetchTransactions(LocalDate fromDate, LocalDate toDate) {
        return new ArrayList<>(simulatedBankFeed);
    }

    public void recordSimulatedInboundCredit(String txnRef, String utr, String va, BigDecimal amount, String currency) {
        simulatedBankFeed.add(new BankTransactionRecord(
                txnRef,
                utr,
                va,
                amount,
                currency,
                "CREDIT",
                Instant.now(),
                "BUYER-BANK-ACC",
                "Inbound escrow funding"
        ));
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signature, String timestamp) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        // In mock mode, allow test signature, secret match, or cryptographic HMAC-SHA256 match
        if (signature.equals("mock-valid-signature") || signature.equals(webhookSecret)) {
            return true;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                    webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            String signedData = (timestamp != null && !timestamp.isBlank()) ? timestamp + "." + rawPayload : rawPayload;
            byte[] hmac = mac.doFinal(signedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String expected = java.util.HexFormat.of().formatHex(hmac);
            return java.security.MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    signature.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return false;
        }
    }
}
