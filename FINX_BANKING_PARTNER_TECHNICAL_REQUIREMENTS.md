# FinX Banking Integration Requirements

## 1. Company / Product Overview

FinX is an enterprise B2B milestone-controlled settlement orchestration platform. In the FinX architecture, buyers fund commercial contracts into a designated nodal or escrow account, deliverables are cryptographically verified and approved by authorized stakeholders, and funds are disbursed to vendor beneficiaries only after deterministic evaluation across 9 business, risk, and fraud decision engines.

FinX integrates with regulated banking institutions via an isolated banking provider boundary (`PartnerBankingProvider`). The platform manages all internal authorization, state machine transitions, and double-entry ledger journals. The banking partner provides the underlying payments rail (e.g., IMPS, NEFT, RTGS) and authoritative settlement evidence for funds held in the dedicated nodal/escrow pool account.

---

## 2. Required Bank Capabilities

To support milestone payouts and closed-loop reconciliation, FinX requires the following institutional banking capabilities:

1. **Outbound Settlement / Payout API**:
   - Single immediate and batch credit transfers to external beneficiary bank accounts (IMPS, NEFT, RTGS).
   - Support for customer reference identifiers and client-provided idempotency keys.
2. **Settlement Status Inquiry API**:
   - Query endpoint accepting either the bank's internal transaction ID or FinX's client operation reference to fetch real-time transfer status.
3. **Real-Time Webhook Notifications**:
   - Asynchronous HTTP callbacks from the bank to notify FinX of final transfer lifecycle events (`SETTLED`, `FAILED`, `REVERSED`).
4. **Beneficiary Verification (Penny Drop / Name Match)** *(Optional / Recommended)*:
   - Account number and IFSC validation API before release instruction creation.
5. **Nodal / Escrow Pool Account Information**:
   - Real-time balance and intraday transaction monitoring on the dedicated provider account (`providerAccountId`).
6. **Transaction Statement / History API**:
   - End-of-day or intraday statement feed (JSON, CAMT.053, MT940, or CSV) for deterministic three-way ledger reconciliation.
7. **Unique Transaction Reference (UTR)**:
   - Provision of the central clearing UTR (Unique Transaction Reference) for every successful and reversed transaction.

---

## 3. Authentication & Security Requirements

FinX enforces strict transport and payload security standards. The partner bank is requested to specify their required authentication and network security mechanisms:

- **Transport Layer**: Mutual TLS (mTLS) with client and server X.509 certificates.
- **API Authentication Scheme**:
  - [ ] OAuth 2.0 (Client Credentials grant with Bearer tokens)
  - [ ] API Key + Secret in request headers
  - [ ] HMAC-SHA256 HTTP Request Signing (Headers: Timestamp, Nonce, Digest, Signature)
  - [ ] RSA-SHA256 / Asymmetric Digital Signatures
- **Network Security**:
  - Source IP whitelisting for FinX outbound API calls.
  - Inbound IP range whitelisting for bank webhook dispatch.

*Bank Must Confirm*: Exact authentication scheme, certificate authority (CA) requirements, key length/algorithm, and IP whitelisting procedure.

---

## 4. Required Sandbox Credentials & Setup

To initiate joint sandbox integration and UAT testing, FinX requests the banking partner to provide:

1. **Sandbox API Base URL**: (e.g., `https://sandbox.bank.com/api/v1`)
2. **Client Credentials**: Client ID and Client Secret / API Key.
3. **mTLS Certificates**: Client certificate and private key (or CSR submission instructions), plus the bank's sandbox CA root certificate.
4. **Provider Account ID**: The simulated nodal/escrow pool account identifier to be configured in FinX.
5. **Test Beneficiary Data**: Whitelisted test account numbers, IFSC codes, and beneficiary names for simulation of:
   - Successful transfers
   - Failed transfers (invalid account / bank offline)
   - Delayed transfers (settlement pending)
6. **Webhook Signing Secret**: Shared secret or public key used to verify incoming webhook signatures.

---

## 5. Idempotency & Operation Keys

### FinX Idempotency Contract
FinX generates a stable, deterministic operation key for every settlement instruction:
```
FINX-REL-{releaseInstructionId}
```
Example: `FINX-REL-5549adcd-8332-40a1-8faa-7c981c81bfba`

- This key is uniquely bound to the specific `ReleaseInstruction` and persisted in the local database (`settlement_execution_attempts`) *before* any network dispatch attempt is made.
- If network timeouts or transport failures occur, subsequent retry attempts send the **exact same idempotency key**.

### Bank Must Confirm
- Header name or JSON payload field used for client idempotency (e.g., `X-Idempotency-Key` or `clientReferenceId`).
- Bank idempotency retention window (minimum required: 24 to 72 hours).
- Behavior upon replaying an existing idempotency key (must return the original transaction status without initiating a second payout).

---

## 6. Settlement Lifecycle & Status Mapping

FinX requires an unambiguous mapping between the bank's internal statuses and FinX settlement lifecycle states:

| FinX Lifecycle State | FinX Meaning | Expected Bank Status Terminology |
| :--- | :--- | :--- |
| `ACKNOWLEDGED` / `SUBMITTED` | Transfer accepted by bank gateway for processing | e.g. `ACCEPTED`, `RECEIVED`, `IN_PROCESS` |
| `PENDING_SETTLEMENT` | Transfer forwarded to clearing house (NEFT/RTGS/IMPS) | e.g. `PROCESSING`, `PENDING`, `DISPATCHED` |
| `SETTLED` | Funds successfully credited to beneficiary account | e.g. `SUCCESS`, `SETTLED`, `COMPLETED` |
| `FAILED` | Transfer definitively rejected prior to settlement | e.g. `FAILED`, `REJECTED`, `DECLINED` |
| `REVERSED` | Transfer settled but subsequently returned by recipient bank | e.g. `RETURNED`, `REVERSED` |
| `UNKNOWN` | Ambiguous gateway response, HTTP 5xx, or network timeout | Gateway timeout / connection reset |

*Bank Must Confirm*: Comprehensive dictionary of all possible transaction status strings, rejection reason codes, and reversal codes.

---

## 7. Settlement Finality Rules

FinX enforces a non-negotiable financial invariant:
> **An HTTP 200 OK gateway response or initial payout ACK does NOT constitute settlement finality.**

FinX only transitions a release instruction to `SETTLED` and mutates the double-entry escrow ledger when **authoritative settlement evidence** is ingested:
1. An authentic, signed webhook indicating final success with UTR, OR
2. An authoritative status API response indicating final success with UTR, OR
3. A confirmed entry on the bank statement feed with matching UTR and amount.

*Bank Must Confirm*: What constitutes definitive legal and operational finality under the bank's service level agreement (SLA).

---

## 8. Webhook Notification Requirements

FinX exposes an inbound HTTPS webhook listener to receive real-time updates. The banking partner must adhere to or confirm the following:

- **Payload Structure**: JSON format containing:
  - Bank Transaction Reference
  - Client Operation Key (`FINX-REL-...`)
  - Clearing UTR
  - Amount and Currency
  - Final Status (`SUCCESS`, `FAILURE`, `REVERSAL`)
  - Timestamp (ISO 8601 UTC)
  - Error / Return Code (for failures/reversals)
- **Signature & Integrity Verification**:
  - Cryptographic signature included in headers (e.g., `X-Bank-Signature: t=1792331580,v1=hash`).
  - Supported algorithms: HMAC-SHA256 (shared secret) or RSA-SHA256 (bank public key).
  - Replay protection via timestamp and unique webhook event ID.
- **Delivery Semantics & Retries**:
  - At-least-once delivery with exponential backoff on HTTP 5xx responses.
  - Expected FinX ACK: HTTP 200/204 within 3000 ms.

---

## 9. Statement & Reconciliation Data Feed

FinX features an automated `DeterministicReconciliationMatcher` that reconciles internal ledger entries against bank statement records. The partner bank must provide daily or intraday statement files with the following line-level attributes:

1. **Provider Account Scope**: Bank Account Number of the nodal/escrow pool.
2. **Transaction Timestamp**: Clearing date and timestamp.
3. **Direction**: `DEBIT` (disbursement to vendor) or `CREDIT` (buyer deposit).
4. **Amount & Currency**: Precise decimal amount (e.g., `50000.00`) and currency code (`INR`).
5. **UTR / Central Clearing Reference**: Unique transaction reference across payment networks.
6. **Client Reference / Description**: The original `FINX-REL-...` reference in transaction narration.
7. **Running Balance**: Opening and closing ledger balance of the nodal account.

*Supported Ingestion Formats*: JSON API, CAMT.053 XML, MT940, or encrypted CSV via SFTP/S3.

---

## 10. Joint Bank UAT Test Cases

During sandbox integration, the following 12 core scenarios must be jointly executed and verified:

1. **UAT-001**: Successful end-to-end payout (Dispatch -> Webhook -> Settled -> UTR verified).
2. **UAT-002**: Pending payout with status inquiry polling recovery.
3. **UAT-003**: Immediate payout rejection (invalid IFSC / closed account).
4. **UAT-004**: Gateway timeout before ACK (idempotent status check without duplicate payment).
5. **UAT-005**: Same idempotency key replayed across multiple HTTP requests (deduplication verified).
6. **UAT-006**: Duplicate webhook event delivery (idempotency verified).
7. **UAT-007**: Webhook with tampered payload or invalid signature (rejected with HTTP 401/403).
8. **UAT-008**: Outbound payout initiated during bank maintenance window (fail-closed handling).
9. **UAT-009**: Post-settlement return / reversal processing.
10. **UAT-010**: End-of-day bank statement ingestion and automatic reconciliation matching.
11. **UAT-011**: Bank statement with deliberate amount mismatch (flagged as exception, no ledger mutation).
12. **UAT-012**: High-concurrency dispatch test against sandbox limits.

---

## 11. Production Onboarding & Governance Requirements

Before production live traffic enablement, the following institutional prerequisites must be satisfied:

- [ ] Executed commercial tripartite or bilateral escrow/nodal agreement.
- [ ] Completed FinX KYB and corporate treasury onboarding with the bank.
- [ ] Approved authorized signatories and dual-control operational mandates.
- [ ] Exchange of production mTLS client/server certificates.
- [ ] IP whitelisting of FinX production NAT gateways on bank firewalls.
- [ ] Delivery of production API credentials and webhook secrets via secure key exchange.
- [ ] Joint signoff on the UAT Test Matrix by FinX Engineering and Bank Integration Team.
- [ ] Agreed operational SLAs, rate limits, per-transaction velocity limits, and 24/7 incident escalation runbooks.
