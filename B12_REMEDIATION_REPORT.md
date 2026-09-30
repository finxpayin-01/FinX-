# B12 Remediation Report

## Blocker

B12 — Legacy execution and evidence have no enforced boundary

## Root Cause

`PaymentRailPolicy` is configured by default to `BANK` mode, but neither `PaymentController` nor `PaymentService` consulted the policy. Consequently:
1. Direct Razorpay order creation (`POST /api/payments/create-order`) remained accessible.
2. Direct manual signature verification (`POST /api/payments/verify`) remained accessible and could mutate deal escrow balances.
3. Razorpay webhook callbacks (`POST /api/payments/webhook`) could accept unknown order IDs and implicitly fund escrow without verifying that an existing historical obligation was pre-registered.
4. `RiskEvidenceService` executed a `UNION ALL` between `payments` (legacy) and `payment_instructions` (bank) without preserving provenance, conflating legacy Razorpay completions with bank-confirmed volume.
5. While legacy blockchain code remained archived in `legacy/backend-blockchain/`, there was no explicit architectural boundary test proving that backend core services (`PaymentOrchestrationService`, `SettlementAuthorizationService`) have zero runtime coupling to Razorpay or blockchain dependencies.

## Before

* **Razorpay create order:** Active and reachable via `POST /api/payments/create-order`.
* **Manual verify:** Active and reachable via `POST /api/payments/verify`, capable of triggering `EscrowService.fundEscrow`.
* **Webhook funding:** Razorpay webhook handler directly funded escrow and accepted payloads without requiring an existing, known legacy payment obligation.
* **Unused PaymentRailPolicy:** Ignored across `PaymentController` and `PaymentService`.
* **Mixed risk evidence:** `RiskEvidenceService` merged legacy `payments` and bank `payment_instructions` indiscriminately without recording source rail or distinguishing bank-confirmed settlement from legacy fiat flows.
* **No active blockchain executor:** Archived in `legacy/backend-blockchain/`, not part of the active backend build, but lacking an explicit automated regression guard.

## After

* **BANK-Mode Service & Controller Boundary:** `PaymentService.createPaymentOrder(...)` and `PaymentService.verifyPayment(...)` fail closed immediately with `org.springframework.security.access.AccessDeniedException` when `paymentRailPolicy.isBank()`. `PaymentController` mirrors this containment, returning HTTP 403 Forbidden.
* **Quarantined Historical Webhook Callback:** In BANK mode, incoming Razorpay webhooks require cryptographic signature verification, mandatory presence of `order_id`, and exact matching against an existing legacy `Payment` record with `FundingRail.RAZORPAY` or provider `RAZORPAY`. Unknown order IDs are rejected with `BadRequestException` (HTTP 400).
* **Historical Read Continuity:** All read endpoints (`GET /api/payments/{id}`, `GET /api/payments/deal/{dealId}`, `GET /api/payments/buyer`, and `GET /api/payments/config`) remain operational, non-mutating, and preserve legacy records as `FundingRail.RAZORPAY`.
* **Risk Evidence Segregation:** Segregated queries for bank instructions and legacy payments. Preserves discrete source records with `rail`, `authoritativeBank`, `sourceId`, and aggregate metrics (`bankVolume`, `legacyVolume`, `bankPaymentCount`, `legacyPaymentCount`, `authoritativeBankVolume`, `hasMixedFundingEvidence`).
* **No Heuristic Deduplication:** No heuristic cross-rail deduplication based on amount, date, deal, or milestone was introduced; provenance is strictly tracked per source record.

## BANK-Mode Legacy Write Boundary

The following write operations return HTTP 403 Forbidden (`AccessDeniedException`) when `PaymentRailPolicy.isBank()`:
- `POST /api/payments/create-order`
- `POST /api/payments/verify`

The rejection occurs before any call to:
- `RazorpayService.createOrder`
- `RazorpayService.verifyPaymentSignature`
- `PaymentRepository.save` / `update`
- `EscrowService.fundEscrow`
- `AuditService.logEvent`
- `NotificationService.sendNotification`

No role bypass, admin bypass, test mode bypass, or fallback to Razorpay exists.

## Historical Webhook Boundary

In BANK mode, `PaymentService.processWebhook(...)` enforces the following quarantine rules:
1. **Cryptographic Verification:** Webhook payload must pass `razorpayService.verifyWebhookSignature(rawPayload, signature)` before any database query or processing.
2. **Mandatory Identity:** `order_id` must be present and non-empty in the webhook payload; missing order IDs are rejected immediately with `BadRequestException`.
3. **Binding to Existing Historical Payment:** The webhook must safely bind to an already-existing `Payment` record via `paymentRepository.findByProviderOrderIdForUpdate(orderId)`.
4. **Legacy Rail Enforcement:** The existing record must have `fundingRail == FundingRail.RAZORPAY` or `provider.equalsIgnoreCase("RAZORPAY")`.
5. **Unknown Orders Rejected:** If no existing payment record matches the provider `order_id`, the callback fails closed with `BadRequestException("Webhook rejected in BANK mode: unknown order_id " + orderId)`.
6. **No Implicit Payment Creation:** Callbacks cannot create new payment rows.
7. **Legacy Effect Scope:** A valid historical callback may update ONLY its existing legacy `Payment` lifecycle to `PaymentStatus.SUCCESS` and execute `EscrowService.fundEscrow(...)` for that specific legacy payment. It does NOT generate bank payment instructions, bank settlement, or bank double-entry ledger lineage.

## Historical Read Compatibility

All existing read endpoints remain functional and non-mutating:
- `GET /api/payments/{id}`
- `GET /api/payments/deal/{dealId}`
- `GET /api/payments/buyer`
- `GET /api/payments/config`

Existing payment rows are not deleted and their `FundingRail.RAZORPAY` enum label is retained.

## Payment Rail Provenance

`RiskEvidenceService` records explicit provenance for each financial item:
- **`BANK`**: Derived strictly from `payment_instructions` (`authoritativeBank = true`).
- **`RAZORPAY`**: Derived from legacy `payments` where `funding_rail = 'RAZORPAY'` or `provider = 'RAZORPAY'` (`authoritativeBank = false`).
- **`UNKNOWN`**: Derived from legacy `payments` with missing or unrecognized rail/provider values (`authoritativeBank = false`).

## Risk Evidence Changes

- Replaced ambiguous `UNION ALL` aggregation with segregated queries:
  1. Bank instructions queried directly from `payment_instructions` (filtered by buyer deal relationship and `COMPLETED`/`CONFIRMED` status).
  2. Legacy payments queried directly from `payments` (filtered by buyer deal relationship and `SUCCESS` status).
- Added explicit metrics to `RiskEvidenceService.Snapshot`:
  - `bankVolume`: BigDecimal
  - `legacyVolume`: BigDecimal
  - `bankPaymentCount`: long
  - `legacyPaymentCount`: long
  - `authoritativeBankVolume`: BigDecimal
  - `hasMixedFundingEvidence`: boolean
  - `fundingProvenances`: List<FundingProvenance>
- Retained backward-compatible aggregate fields (`totalPaidVolume`, `successfulPaymentCount`) for existing consumers without breaking downstream contracts.
- **Critical Policy:** No heuristic cross-rail deduplication was introduced.

## Mixed Evidence Policy

When an account or deal exhibits both legacy payment records and bank payment instructions (`hasMixedFundingEvidence == true`), legacy volume is never counted toward `authoritativeBankVolume`. Downstream risk assessments, credit limits, or future engine adapters treat mixed/unresolved legacy evidence as non-authoritative.

## Razorpay Startup Configuration

- `RazorpayProperties` and `RazorpayService` do not perform network calls or block Spring application context initialization when Razorpay credentials (`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`) are absent.
- Verified in `LegacyBoundaryContainmentTest.testApplicationStartsWithoutRazorpayCredentials`.

## Blockchain Classification

- `ACTIVE BACKEND BLOCKCHAIN EXECUTOR = NO`
- Verified: `backend/pom.xml` contains no `web3j` dependency.
- Verified: No active main-source blockchain executor or smart-contract bridge exists in `com.finx`. The legacy Node.js/blockchain code remains safely archived in `legacy/backend-blockchain/` outside the Maven build.

## Architecture Boundary

Added automated architecture regression checks in `LegacyBoundaryContainmentTest`:
- `PaymentOrchestrationService` has zero fields or dependencies of type `RazorpayService`.
- `SettlementAuthorizationService` has zero fields or dependencies of type `RazorpayService`.
- `BankingProvider` (e.g., `MockBankingProvider`) has zero fields or dependencies of type `RazorpayService`.

## Interaction With B01

B01 contained manual/untrusted bank payment confirmation (`PaymentOrchestrationService.confirmPayment` fails closed with `AccessDeniedException`). B12 preserves B01 containment while ensuring that legacy Razorpay channels cannot be used to bypass bank confirmation controls.

## Interaction With B02

B02 contained bank webhook authentication and replay control (mandatory HMAC-SHA256 signature verification, strict replay defense, safe parsing). B12 ensures that Razorpay webhooks are quarantined to known historical obligations only and cannot create or confirm bank payment instructions.

## Interaction With B03

B03 contained direct escrow release (`EscrowService.releaseEscrow` fails closed with `AccessDeniedException`). B12 preserves B03 containment; test fixtures establish funded state via explicit test-only methods (`TestEscrowReleaseFixture`).

## Interaction With B04

B04 contained combined settlement authorization and execution (`SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed with `AccessDeniedException`). B12 confirms zero calls to `BankingProvider.initiateSettlement` and proves zero coupling between settlement orchestration and Razorpay.

## Files Modified

- [PaymentRailPolicy.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/payment/config/PaymentRailPolicy.java)
- [PaymentController.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/payment/controller/PaymentController.java)
- [PaymentService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/payment/service/PaymentService.java)
- [RiskEvidenceService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/risk/service/RiskEvidenceService.java)
- [TestEscrowReleaseFixture.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/escrow/TestEscrowReleaseFixture.java)
- [PaymentServiceTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/payment/PaymentServiceTest.java)
- [TransactionEngineEndToEndTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/e2e/TransactionEngineEndToEndTest.java)
- [SecurityHardenedTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/e2e/SecurityHardenedTest.java)

## Files Created

- [LegacyBoundaryContainmentTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/payment/LegacyBoundaryContainmentTest.java)
- [B12_REMEDIATION_REPORT.md](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/B12_REMEDIATION_REPORT.md)

## Database Migration

NONE

## API Compatibility

All existing DTOs, endpoint signatures, and routes remain intact. Write endpoints for legacy Razorpay return HTTP 403 Forbidden under the active BANK policy. `RiskEvidenceService.Snapshot` retains a backward-compatible constructor for any callers expecting the 3-argument signature.

## Tests Added

`com.finx.payment.LegacyBoundaryContainmentTest` (14 comprehensive test methods covering 20 requirement points):
1. `testBankMode_createPaymentOrder_denied_zeroRazorpayCalls`
2. `testBankMode_verifyPayment_denied_zeroEscrowAndRazorpayCalls`
3. `testHistoricalPaymentRead_preservesRailAndReturnsDetails`
4. `testHistoricalPayment_retainsRazorpayFundingRail`
5. `testRiskEvidence_segregatesBankAndLegacyEvidence`
6. `testRiskEvidence_unknownAndMissingRail_treatedAsNonauthoritative`
7. `testWebhook_unknownOrder_rejectedInBankMode`
8. `testWebhook_knownHistoricalObligation_processedIfSafelyBound`
9. `testWebhook_missingOrder_rejectedInBankMode`
10. `testApplicationStartsWithoutRazorpayCredentials`
11. `testArchitecture_paymentOrchestrationService_hasNoRazorpayDependency`
12. `testArchitecture_settlementAuthorizationService_hasNoRazorpayDependency`
13. `testArchitecture_noWeb3jDependency`
14. `testArchitecture_noActiveBlockchainExecutor`
15. `testPriorContainments_b01_to_b04_stillHold`

## Tests Run

1. `LegacyBoundaryContainmentTest`
2. `PaymentConfirmationContainmentTest`
3. `BankingWebhookControllerTest`
4. `EscrowReleaseContainmentTest`
5. `SettlementExecutionContainmentTest`
6. `PaymentServiceTest`
7. `RazorpayServiceTest`
8. `RiskIntegrationTest`
9. `RiskScoringComprehensiveTest`
10. `TransactionEngineEndToEndTest`
11. `SecurityHardenedTest`
12. Complete Maven Test Suite (`.\mvnw.cmd test`)

## Test Results

- Tests run: 414
- Failures: 0
- Errors: 0
- Skipped: 1
- Result: **BUILD SUCCESS**

## Known Limitations

- **B05:** Scoped financial effect identity, unique idempotency keys, and milestone settlement constraint binding will be formalized in B05.
- **B06:** Double-entry journal financial source lineage, bank statement reconciliation, and deposit-settlement symmetry will be established in B06.
- **B07:** Historical read access tightening, tenant isolation, and audit log protection will be addressed in B07.
- **Legacy Frontend Cleanup:** Frontend UI migration to bank-only rail deferred.
- **Real Bank Adapter:** Transition from sandbox `MockBankingProvider` to live banking integration deferred.

## B12 Status

PASS

## Ready for B07

YES
