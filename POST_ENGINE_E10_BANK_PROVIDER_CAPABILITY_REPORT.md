# FinX E10 Production Bank Provider Capability Report

## Scope
Design, specify, implement, and certify the FinX E10 Production Bank Provider Capability Adapter Framework (`backend`).
Provide a secure, production-grade provider architecture that can integrate with regulated banks without weakening the certified E1-E9 execution model.
E10 introduces an explicit capability model, type-safe partner configuration, mTLS WebClient factory, canonical HMAC-SHA256 request signing abstraction, fail-closed `UnsupportedBankApiContract`, provider capability guards, readiness diagnostics, and a fail-closed production dispatch gate.

## Baseline
- **E9 Commit**: `9d39a4c`
- **Working Tree**: Clean baseline verified
- **E1-E9 Adversarial Certification**: PASS
- **PostgreSQL**: 16.15 on port 5432 PASS

## Migration
- **Status**: NONE
- **Highest Migration**: `V35__add_release_instruction_reconciliation_effect.sql`
- **V36**: ABSENT (Confirmed via `FlywayCleanMigrationTest` and filesystem inspection)

## BankingProvider Capabilities
FinX defines an explicit, immutable capability enum `BankingProviderCapability`:
1. `PAYMENT_INSTRUCTION_CREATE`
2. `PAYMENT_STATUS_QUERY`
3. `ACCOUNT_DETAILS_QUERY`
4. `BENEFICIARY_VERIFICATION`
5. `SETTLEMENT_INITIATE`
6. `SETTLEMENT_STATUS_QUERY`
7. `SETTLEMENT_OPERATION_LOOKUP`
8. `TRANSACTION_FEED`
9. `STATEMENT_PAGING`
10. `WEBHOOK_SIGNATURE_VERIFICATION`

The `BankingProviderCapabilities` value object encapsulates an immutable set of capabilities. The `BankingProvider` contract exposes `getCapabilities()` and `supports(BankingProviderCapability capability)`. Capabilities are never inferred through exception-catching.

## Minimum Production Capability Set
For production readiness, FinX strictly mandates five non-negotiable capabilities:
1. `SETTLEMENT_INITIATE`: Atomic payout dispatch.
2. `SETTLEMENT_STATUS_QUERY`: Remote status polling.
3. `SETTLEMENT_OPERATION_LOOKUP`: Idempotent query via stable client operation identity.
4. `WEBHOOK_SIGNATURE_VERIFICATION`: Cryptographic verification of asynchronous notifications.
5. `STATEMENT_PAGING`: Authoritative statement retrieval for closed-loop reconciliation.

A provider missing any of these five capabilities is rejected as `readyForProduction = false`.

## Mock Provider
`MockBankingProvider` declares all 10 capabilities in an immutable container. It provides high-fidelity in-memory sandbox execution, deterministic UTR generation, stateful settlement status transitions, duplicate idempotency detection, and operation-key lookups. All E1-E9 tests continue to execute against `MockBankingProvider`.

## Partner Provider Framework
`PartnerBankingProvider` implements `BankingProvider` behind `@ConditionalOnProperty(name = "finx.banking.provider", havingValue = "partner")`.
- It fails closed if required partner configuration is missing or invalid.
- It requires an explicit capability guard check before every operation via `BankingProviderCapabilityGuard.requireCapability(...)`.
- It delegates bank-specific wire encoding and decoding to `BankApiContract`.
- It delegates network I/O, timeouts, request signing, and mTLS to `BankHttpTransport`.

## Bank-Specific Contract Availability
**OFFICIAL BANK-SPECIFIC API CONTRACT NOT PRESENT — NO ENDPOINTS INVENTED**

In accordance with FinX Critical Safety Rules, no Axis, HDFC, ICICI, YES Bank, or DBS endpoints, URLs, field names, or signatures were fabricated. The framework provides the generic SPI `BankApiContract` and defaults to `UnsupportedBankApiContract`, which immediately throws `BankingCapabilityNotConfiguredException` for any uncertified bank-specific operation.

## mTLS
`BankingWebClientFactory` builds a production-grade Reactor Netty `WebClient` with:
- Dedicated `SslContext` built from configured PKCS12 / JKS keystore and truststore.
- Strict protocol enforcement: TLS 1.2 and TLS 1.3 only.
- Strict hostname verification enabled.
- Zero trust-all `TrustManager` or insecure SSL context allowed.
- Memory buffer bounded to 1 MB to prevent memory-exhaustion attacks.

## Request Signing
FinX provides the canonical `BankRequestSigner` abstraction:
- Inputs: `BankSigningRequest(method, path, timestampEpochMs, requestId, bodySha256)`.
- Canonical payload: deterministic concatenation `METHOD\nPATH\nTIMESTAMP\nREQUEST_ID\nBODY_HASH`.
- `HmacSha256BankRequestSigner`: Calculates HMAC-SHA256 over exact canonical UTF-8 bytes using secret key bytes.
- Zero reliance on `Object.hashCode()` or `toString()`.
- Signing secrets are masked in all logs and outputs.

## Secret Management Boundary
- Secrets are accepted strictly through environment variables, external configuration files, or future vault providers (`finx.banking.partner.*`).
- Zero secret fields persisted in PostgreSQL.
- Zero secret fields logged: `BankingPartnerProperties.toString()` masks `clientSecret`, `keyStorePassword`, `trustStorePassword`, `signing.secretKey`, and `webhook.secret`.
- Verified by automated static security audit test `NoRealCredentialTest`.

## HTTP Timeouts
Configured with finite, non-zero timeouts via `BankingPartnerProperties`:
- Connect timeout: default 5,000 ms (`finx.banking.partner.connect-timeout-ms`).
- Read / response timeout: default 15,000 ms (`finx.banking.partner.read-timeout-ms`).
- Zero infinite HTTP blocking permitted.

## Automatic POST Retry
**DISABLED**

`BankHttpTransport` and `PartnerBankingProvider` strictly forbid automatic retry on settlement initiation POST requests. Network timeouts or HTTP 5xx responses fail directly into `BankTimeoutException` or `BankTransportException`, passing control to `SettlementDispatchHandler` to maintain the certified E5 UNKNOWN-outcome safety boundary.

## Stable Provider Operation Identity
FinX enforces deterministic external identity propagation:
- Operation Key: `FINX-REL-{releaseInstructionId}`.
- Re-used across all initial dispatches, retries, and status queries.
- Zero random UUIDs generated for bank settlement requests.

## Status Recovery Requirement
A partner provider must support `SETTLEMENT_STATUS_QUERY` to allow polling recovery of in-flight settlement instructions.

## Operation-Key Lookup Requirement
E9 proves operation-key recovery is critical during crash recovery when provider settlement ID is unknown. A partner lacking `SETTLEMENT_OPERATION_LOOKUP` is explicitly flagged as `NOT SAFE FOR BLIND-RETRY RECOVERY` and rejected from production readiness.

## Statement/Reconciliation Requirement
Because E8 closed-loop reconciliation requires durable statement evidence, production readiness strictly mandates `STATEMENT_PAGING` or an authoritative transaction feed. Reconciliation is never waived.

## Webhook Verification Requirement
Asynchronous webhook ingestion requires `WEBHOOK_SIGNATURE_VERIFICATION`. If a provider does not provide an official cryptographic webhook contract, the capability is absent and webhooks fail closed.

## Production Readiness Service
`BankingProviderReadinessService` inspects active provider configuration and evaluates operational readiness without exposing secrets.
Reports:
- `providerName`
- `configured`
- `sandbox`
- `productionExecutionEnabled`
- `mtlsConfigured`
- `signingConfigured`
- `capabilities`
- `readyForSandbox`
- `readyForProduction`
- `blockers`

In E10, `readyForProduction` is unconditionally `false`.

## Production Execution Gate
**DISABLED**

- Master production flag `finx.release.production-execution.enabled: false`.
- `ProductionSettlementDispatchGate` strictly denies execution if:
  - Active provider is `MOCK`.
  - Provider is configured in `sandbox: true` mode.
  - `productionExecutionEnabled` is `false`.
  - Master flag is `false`.
  - mTLS is not configured.
  - Critical capabilities are missing.
  - E12 certification gate is absent or false.
- Zero live releases routed to real money movements.

## Real Bank Calls
**0** (Zero external HTTP requests to real banks; all tests execute against Mock or local WireMock/Netty servers).

## Credentials Committed
**0** (Verified via `NoRealCredentialTest` across all project files).

## PostgreSQL
**PASS** (All tests certified under PostgreSQL 16.15 `finx_postgres`).

## Flyway
**V1 -> V35 PASS**; `V36: ABSENT`.

## Production Payout
**DISABLED**

## Ready For E11/E12
**YES** (Provider capability adapter framework fully operational, tested, and fail-closed).
