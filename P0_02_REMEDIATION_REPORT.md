# P0-02 Remediation Report

## Blocker

B02 — Bank webhook authentication and replay control fail open

## Root Cause

1. **In-Memory Replay Cache:** [BankingWebhookController.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/banking/controller/BankingWebhookController.java) maintained process-local deduplication via `Set<String> processedEventIds = ConcurrentHashMap.newKeySet()`. This ephemeral set was lost upon application restart, failed completely across horizontal replicas, and provided zero durable replay guarantees.
2. **Permissive Authentication:** [MockBankingProvider.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/banking/provider/mock/MockBankingProvider.java) implemented `verifyWebhookSignature` with fail-open logic that returned `true` when the signature header was `null` or blank.
3. **Missing Header Validation:** The webhook endpoint did not require `X-FinX-Signature` or `X-FinX-Timestamp` headers, allowing unauthenticated and timestamp-free requests to be accepted.
4. **Unbound Provider Routing:** The route `/api/v1/webhooks/banking/{provider}` accepted any arbitrary string without verifying that it corresponded to the active configured [BankingProvider](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/banking/provider/BankingProvider.java).
5. **No Event Identity or Payload Integrity Enforcement:** Incoming payloads were deserialized without enforcing non-blank event identity or verifying raw payload hashes against replay tampering.
6. **No Durable Lifecycle State:** The system lacked durable lifecycle states (`RECEIVED`, `PROCESSING`, `PROCESSED`, `FAILED`), risking either silent failure drops or inability to safely retry transient business failures.

## Before

* Webhook signatures were optional or failed open in test/mock profiles.
* Replay deduplication lived entirely in JVM memory (`processedEventIds`).
* Any arbitrary provider string in `/api/v1/webhooks/banking/{provider}` was accepted.
* No timestamp validation or replay window was enforced.
* Payloads were deserialized before signature verification.
* The existing Flyway V8 `webhook_events` database table was completely unused by the banking webhook flow.

## After

* **Fail-Closed Signature Authentication:** Missing, blank, or invalid signatures are rejected immediately with HTTP 400 Bad Request before parsing JSON or invoking business logic.
* **Raw Payload Verification:** Cryptographic signatures are verified against the raw HTTP request body string/bytes, preserving whitespace and formatting integrity.
* **Strict Timestamp Windowing:** Webhooks must supply `X-FinX-Timestamp`. Stale timestamps older than 300 seconds or future timestamps exceeding 60 seconds are rejected with HTTP 400.
* **Strict Provider Binding:** The `{provider}` path variable must match the configured `bankingProvider.getProviderName()`. Mismatched or unknown providers are rejected with HTTP 400.
* **Durable Replay Protection via V8 Table:** Replay detection is persisted to the database using [WebhookEvent](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/payment/entity/WebhookEvent.java) and [WebhookEventRepository](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/payment/repository/WebhookEventRepository.java).
* **SHA-256 Payload Hash & Integrity Verification:** Each event stores a hex-encoded SHA-256 hash of the raw payload. Identical events return idempotent 200 OK (`already_processed`), while duplicate IDs with modified payloads are rejected as integrity conflicts (HTTP 400).
* **Safe Concurrent & Restart Handling:** Handled via unique database constraint `uq_provider_event` (`provider`, `provider_event_id`) and `DataIntegrityViolationException`. Survives application restarts and multi-node deployments.
* **Synchronous Inbox Lifecycle:** Transitions from `RECEIVED` to `PROCESSED` on success, or `FAILED` on error. Failed events remain safely retryable.
* **Preservation of P0-01 Containment:** Any webhook event that attempts payment confirmation fails closed with HTTP 403 Forbidden because [PaymentOrchestrationService.confirmPayment](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/paymentorchestration/service/PaymentOrchestrationService.java) enforces `AccessDeniedException`. The event is marked `FAILED` in the database.

## Authentication Boundary

* **Raw Payload Verification:** The raw request body is captured directly as a String in `BankingWebhookController` and passed to `bankingProvider.verifyWebhookSignature(rawPayload, signature)`. Only after signature verification succeeds is the payload parsed into JSON.
* **Signature Handling:** The `X-FinX-Signature` header is mandatory. If absent, blank, or cryptographically invalid according to the provider contract, the request fails closed with HTTP 400.
* **Timestamp Handling:** The `X-FinX-Timestamp` header is mandatory. It is parsed into epoch seconds (supporting epoch seconds, epoch millis, or ISO-8601 instants). Stale timestamps older than 300 seconds and future timestamps greater than 60 seconds are rejected with HTTP 400.
* **Provider Binding:** The route path variable `{provider}` is validated against `bankingProvider.getProviderName()`. Any route calling an unknown or mismatched provider is rejected with HTTP 400.

## Replay Protection

* **Durable Identity:** Webhook identities are keyed by the compound tuple `(provider, provider_event_id)` and backed by the unique database constraint `uq_provider_event` on the `webhook_events` table.
* **Provider Namespace:** Provider names are normalized to uppercase (e.g., `MOCK`, `RAZORPAY`), preventing namespace collision or cross-provider spoofing.
* **Event ID:** The `eventId` field in the payload must be present and non-blank. Auto-generation of bank event IDs is strictly prohibited.
* **Payload Hash Behavior:** The raw payload is hashed with SHA-256 upon receipt. If a record already exists with status `PROCESSED`:
  * If the payload hash matches: return HTTP 200 with `status: "already_processed"` (no second financial effect).
  * If the payload hash differs: reject with HTTP 400 Bad Request due to payload integrity conflict.
* **Concurrent Duplicate Behavior:** Concurrent requests for the same event race to insert into `webhook_events`. The race winner proceeds with processing; losers encounter `DataIntegrityViolationException`, catch the duplicate record, and return idempotent 200 OK `already_processed`.
* **Restart Behavior:** Because state is durably held in the SQL database rather than JVM memory, application restarts and multi-instance deployments correctly identify duplicate deliveries.

## Failure / Retry Semantics

* If business processing of an authenticated webhook throws an exception (such as `AccessDeniedException` from P0-01 or external system failure), the event record is saved with `processing_status = "FAILED"`.
* The system does NOT mark an event `PROCESSED` before processing succeeds.
* When a legitimate retry arrives for an event marked `FAILED`, the controller allows re-attempting business processing rather than discarding the retry.

## Interaction With P0-01

* In P0-01, `PaymentOrchestrationService.confirmPayment(...)` was locked down with `AccessDeniedException("Payment confirmation is disabled pending trusted bank ingestion")`.
* In B02, no bypass or internal flag was added.
* If a webhook delivers a `PAYMENT_CREDITED` event with an `instructionReference`, the controller's call to `orchestrationService.confirmPayment(...)` immediately throws `AccessDeniedException`.
* The controller catches the security exception, marks the webhook event `FAILED` in `WebhookEventRepository`, and returns HTTP 403 Forbidden.
* This proves that webhook authentication does NOT bypass the disabled untrusted payment confirmation path.

## Files Modified

* [BankingWebhookController.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/banking/controller/BankingWebhookController.java): Implemented fail-closed raw payload authentication, header validation, timestamp windowing, provider binding, durable replay control, SHA-256 integrity verification, and failure/retry lifecycle.
* [MockBankingProvider.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/banking/provider/mock/MockBankingProvider.java): Updated `verifyWebhookSignature` to fail closed on missing/blank signatures and verify HMAC-SHA256 signatures or mock test signatures.
* [WebhookEvent.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/payment/entity/WebhookEvent.java): Added `@Table(uniqueConstraints = @UniqueConstraint(name = "uq_provider_event", columnNames = {"provider", "provider_event_id"}))` to align the JPA entity with the Flyway V8 schema constraint across all test profiles.

## Files Created

* [BankingWebhookControllerTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/banking/BankingWebhookControllerTest.java): Comprehensive test suite covering 21 focused security, replay, concurrency, restart, and failure scenarios.
* [P0_02_REMEDIATION_REPORT.md](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/P0_02_REMEDIATION_REPORT.md): This report.

## Database Migration

Expected: **NONE**  
Actual: **NONE**  
Reason: The existing Flyway migration `V8__create_onramp_transactions.sql` already provided the `webhook_events` table with columns `id`, `provider`, `provider_event_id`, `event_type`, `payload_hash`, `processing_status`, `received_at`, and `processed_at`, as well as the unique constraint `uq_provider_event (provider, provider_event_id)`. Reusing this existing infrastructure avoided any schema modification.

## Security Effect

* Eliminates spoofed bank webhooks by failing closed on missing, blank, or invalid signatures.
* Protects against replay attacks through strict timestamp windowing (300s past / 60s future).
* Prevents route hijacking and provider impersonation by validating route paths against registered providers.
* Guarantees payload integrity by detecting and rejecting tampered payloads reusing historical event IDs.

## Financial Safety Effect

* Guarantees that duplicate bank events can never trigger duplicate financial mutations or double crediting.
* Prevents premature marking of events as processed, ensuring that transient errors leave events safely retryable.
* Prevents authenticated webhooks from unilaterally confirming payments while B05/B06 financial controls remain pending.

## API Compatibility

* Route `/api/v1/webhooks/banking/{provider}` now strictly requires:
  * HTTP Header `X-FinX-Signature`: Non-blank cryptographic signature.
  * HTTP Header `X-FinX-Timestamp`: Valid timestamp string within tolerance.
  * Request Body: Non-empty JSON object containing a non-blank `eventId` field.

## Tests Added

Test Class: [BankingWebhookControllerTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/banking/BankingWebhookControllerTest.java)
1. `missingSignature_rejectedWithBadRequest`: Missing `X-FinX-Signature` header rejected (HTTP 400).
2. `blankSignature_rejectedWithBadRequest`: Blank `X-FinX-Signature` header rejected (HTTP 400).
3. `invalidSignature_rejectedWithBadRequest`: Invalid signature rejected (HTTP 400).
4. `validSignature_passesAuthenticationBoundary`: Valid signature accepted and processed (HTTP 200).
5. `missingTimestamp_rejectedWithBadRequest`: Missing `X-FinX-Timestamp` header rejected (HTTP 400).
6. `blankTimestamp_rejectedWithBadRequest`: Blank `X-FinX-Timestamp` header rejected (HTTP 400).
7. `malformedTimestamp_rejectedWithBadRequest`: Malformed timestamp rejected (HTTP 400).
8. `staleTimestamp_rejectedWithBadRequest`: Stale timestamp (>300s) rejected (HTTP 400).
9. `futureTimestampOutsideTolerance_rejectedWithBadRequest`: Future timestamp (>60s) rejected (HTTP 400).
10. `unknownProvider_rejectedWithBadRequest`: Unknown provider in path rejected (HTTP 400).
11. `providerMismatch_rejectedWithBadRequest`: Provider mismatch in path rejected (HTTP 400).
12. `missingEventId_rejectedWithBadRequest`: Payload without `eventId` rejected (HTTP 400).
13. `blankEventId_rejectedWithBadRequest`: Payload with blank `eventId` rejected (HTTP 400).
14. `malformedJson_rejectedWithBadRequest`: Malformed JSON payload rejected (HTTP 400).
15. `sameEventDeliveredTwice_businessEffectAtMostOnce`: Duplicate event recognized and returned as `already_processed` (HTTP 200).
16. `sameEventIdWithChangedPayload_rejectedAsConflict`: Reused event ID with altered payload rejected as integrity conflict (HTTP 400).
17. `concurrentDuplicateDeliveries_atMostOneProcessingPath`: 8 concurrent threads sending identical event result in exactly 1 processed record.
18. `restartSimulation_duplicateRecognizedDurablyFromRepository`: Event pre-populated in database recognized after application restart simulation.
19. `webhookCannotBypassP01Containment_returnsHttp403AndMarksFailed`: Webhook attempting payment confirmation fails with HTTP 403 and marks event `FAILED`.
20. `retryAfterProcessingFailure_safelyReprocessable`: Failed event remains safely reprocessable upon retry.
21. `p01ManualConfirmationRemainsBlocked`: Verifies service-level P0-01 containment remains active.

## Tests Run

* `BankingWebhookControllerTest` (21 tests)
* `MockBankingProviderTest` (5 tests)
* `PaymentConfirmationContainmentTest` (19 tests)
* `BankIntegratedEndToEndTest` (1 test)
* Full backend test suite (`mvnw test`) across all 365 repository tests

## Test Results

* Tests Run: 365
* Failures: 0
* Errors: 0
* Skipped: 1
* Build Result: **BUILD SUCCESS**

## Known Limitations

* Automatic bank confirmation of payments and escrow release remain disabled pending the implementation of the B05 (Transactional Idempotency) and B06 (Outbox / Event Dispatching) controls.
* Production banking providers (e.g. Yes Bank, ICICI) are not yet integrated; `MockBankingProvider` is active under sandbox/test configurations.

## P0-02 Status

**PASS**

## Ready for P0-03

**YES**
