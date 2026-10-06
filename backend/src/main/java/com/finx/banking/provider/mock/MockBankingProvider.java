package com.finx.banking.provider.mock;

import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.dto.BankStatementDirection;
import com.finx.banking.dto.BankStatementEntry;
import com.finx.banking.dto.BankStatementFinality;
import com.finx.banking.dto.BankStatementPage;
import com.finx.banking.dto.BankStatementQuery;
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

    public static final String MOCK_ESCROW_ACCOUNT = "MOCK_ESCROW_ACCOUNT";

    private static final Logger log = LoggerFactory.getLogger(MockBankingProvider.class);

    private final String webhookSecret;
    private final Map<String, PaymentInstructionResult> virtualAccounts = new ConcurrentHashMap<>();
    private final Map<String, SettlementResult> settlements = new ConcurrentHashMap<>();
    private final List<BankTransactionRecord> simulatedBankFeed = Collections.synchronizedList(new ArrayList<>());
    private final List<BankStatementEntry> durableStatementEntries = Collections.synchronizedList(new ArrayList<>());

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
                "{\"provider\": \"MOCK\", \"status\": \"CREATED\", \"mode\": \"SANDBOX\"}",
                MOCK_ESCROW_ACCOUNT
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
                "{\"provider\": \"MOCK\", \"status\": \"SETTLED\", \"utr\": \"" + utr + "\"}",
                MOCK_ESCROW_ACCOUNT
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

    @Override
    public BankStatementPage fetchStatementPage(BankStatementQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("BankStatementQuery cannot be null");
        }
        if (query.getProviderAccountId() == null || query.getProviderAccountId().isBlank()) {
            throw new IllegalArgumentException("providerAccountId must not be blank");
        }
        if (query.getProviderAccountId().length() > 128) {
            throw new IllegalArgumentException("providerAccountId length cannot exceed 128 chars");
        }
        if (query.getWindowStart() == null || query.getWindowEnd() == null) {
            throw new IllegalArgumentException("windowStart and windowEnd must not be null");
        }
        if (query.getWindowEnd().isBefore(query.getWindowStart())) {
            throw new IllegalArgumentException("windowEnd cannot be before windowStart");
        }
        if (query.getPageSize() <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }

        // Filter simulated durable evidence
        List<BankStatementEntry> filtered = new ArrayList<>();
        synchronized (durableStatementEntries) {
            for (BankStatementEntry entry : durableStatementEntries) {
                if (entry.getTransactionTime() != null
                        && !entry.getTransactionTime().isBefore(query.getWindowStart())
                        && !entry.getTransactionTime().isAfter(query.getWindowEnd())) {
                    filtered.add(entry);
                }
            }
        }

        // Deterministic sorting: transactionTime asc, then providerLineId asc
        filtered.sort(Comparator.comparing(BankStatementEntry::getTransactionTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BankStatementEntry::getProviderLineId, Comparator.nullsLast(Comparator.naturalOrder())));

        int offset = 0;
        if (query.getCursor() != null && !query.getCursor().isBlank()) {
            try {
                offset = Integer.parseInt(query.getCursor().trim());
                if (offset < 0) {
                    throw new IllegalArgumentException("Cursor offset cannot be negative: " + query.getCursor());
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid cursor format: " + query.getCursor(), e);
            }
        }

        int fromIndex = Math.min(offset, filtered.size());
        int toIndex = Math.min(offset + query.getPageSize(), filtered.size());
        List<BankStatementEntry> pageEntries = new ArrayList<>(filtered.subList(fromIndex, toIndex));

        String nextCursor = null;
        if (toIndex < filtered.size()) {
            nextCursor = String.valueOf(toIndex);
        }

        String currency = "INR";
        if (!pageEntries.isEmpty() && pageEntries.get(0).getCurrency() != null) {
            currency = pageEntries.get(0).getCurrency();
        }

        return BankStatementPage.builder()
                .providerAccountId(query.getProviderAccountId())
                .providerImportReference("MOCK-IMP-REF-" + query.getProviderAccountId())
                .currency(currency)
                .openingBalance(BigDecimal.valueOf(50000000))
                .closingBalance(BigDecimal.valueOf(50000000))
                .entries(pageEntries)
                .nextCursor(nextCursor)
                .build();
    }

    public void addDurableStatementEntry(BankStatementEntry entry) {
        if (entry != null) {
            durableStatementEntries.add(entry);
        }
    }

    public void clearDurableStatementEntries() {
        durableStatementEntries.clear();
    }

    public List<BankStatementEntry> getDurableStatementEntries() {
        return new ArrayList<>(durableStatementEntries);
    }
}
