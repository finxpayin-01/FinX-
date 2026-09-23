# FINX — Banking Abstraction & Partner Integration Architecture

## 1. Design Rationale
FinX connects businesses to regulated banking rails without coupling core business logic to any single bank's proprietary API specifications. All financial interactions pass through the `BankingProvider` contract.

---

## 2. The `BankingProvider` Interface

Located at `com.finx.banking.provider.BankingProvider`:

```java
public interface BankingProvider {
    String getProviderName();

    PaymentInstructionResult createPaymentInstruction(PaymentInstructionRequest request);

    PaymentStatusResult getPaymentStatus(String providerPaymentRef);

    AccountDetailsResult getAccountDetails(String accountNumber);

    BeneficiaryVerificationResult verifyBeneficiary(BeneficiaryVerificationRequest request);

    SettlementResult initiateSettlement(SettlementRequest request);

    SettlementStatusResult getSettlementStatus(String providerSettlementRef);

    List<BankTransactionRecord> fetchTransactions(Instant from, Instant to);
}
```

---

## 3. Provider Selection & Configuration

The active banking provider is selected at runtime via the `finx.banking.provider` environment configuration:

- `BANK_PROVIDER=mock`: Activates `MockBankingProvider`, which performs in-memory virtual account generation, simulated penny-drop account validation, and deterministic webhook testing without real money movement.
- `BANK_PROVIDER=partner_name`: Activates the production adapter implementing secure MTLS, API keys, and asymmetric webhook signatures.

### Provider Factory & Auto-Configuration
In `BankingConfig.java`, Spring creates the appropriate `@Bean`:
```java
@Bean
@ConditionalOnProperty(name = "finx.banking.provider", havingValue = "mock", matchIfMissing = true)
public BankingProvider mockBankingProvider(BankingProperties properties) {
    return new MockBankingProvider(properties);
}
```

---

## 4. Mock Banking Provider Capabilities

For local development and testing, `MockBankingProvider` supports:
1. **Virtual Account Generation**: Generates deterministic Virtual Account Numbers (`FINX...`) and IFSC codes (`FINX0001001`).
2. **Penny-Drop Verification**: Validates IFSC format and simulates account holder name matching (returning match score 98% and mock bank reference `MOCK-PENNY-...`).
3. **Settlement Execution**: Generates unique mock UTR numbers (`UTR-MOCK-STL-...`) and records settled payouts.
4. **Statement Generation**: Generates external statement records for automated reconciliation testing.

---

## 5. Webhook Security & Ingestion

Banking partners post asynchronous updates to:
`POST /api/v1/webhooks/banking/{provider}`

Security controls implemented:
- **HMAC Signature Validation**: Verified using `X-FinX-Signature` or `X-Bank-Signature` against `BANK_WEBHOOK_SECRET`.
- **Timestamp / Replay Attack Defense**: Rejects requests older than 300 seconds.
- **Idempotency Protection**: Ensures duplicate callbacks for the same event ID do not post duplicate journal entries.
- **Audit Trails**: Logs all raw inbound payloads with sensitive credentials redacted.
