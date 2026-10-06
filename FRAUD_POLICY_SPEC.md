# Fraud Policy Specification

## Scope

The FinX Fraud Decision Policy defines the deterministic rules, evidence boundaries, signal ownership, and decision contracts for evaluating fraud risk across the FinX Decision Intelligence Platform.

This specification governs:
1. The **canonical ownership of fraud-relevant signals** between the **Risk Engine** and the **Fraud Engine**.
2. The **prevention of double-counting** between raw telemetry, document checks, and composite risk scores.
3. The **versioned policy structure** utilizing the existing common `EnginePolicyVersion` infrastructure without creating redundant policy tables.
4. The **deterministic decision evaluation model** mapping verifiable evidence to `DecisionStatus` (`ALLOW`, `REVIEW`, `BLOCK`).
5. The **read-only decision adapter contract** for the future `FraudDecisionAdapter`.

---

## Existing Signals

Inspection of the FinX codebase reveals candidate fraud indicators across multiple subsystems:

### Fraud Source Inventory

| Signal Name | Source System / Entity | Durable Storage | Timestamped | Tenant-Scoped | Already Consumed by Risk? | Canonical Owner | Safe for Initial Fraud Adapter | Notes / Constraints |
|---|---|---|---|---|---|---|---|---|
| **EXACT_DOCUMENT_HASH_DUPLICATE** | `InvoiceDocumentRepository`, `InvoiceDocument` | Yes (`invoice_documents.content_hash`) | Yes (`created_at`) | Yes (`organization_id`) | Yes (`DUPLICATE_DOCUMENTS` metric) | **FRAUD-OWNED** (Raw Fact) | **YES** | Exact cryptographic SHA-256 collision across distinct invoice records. |
| **DUPLICATE_INVOICE_NUMBER** | `InvoiceMatchingEngine`, `Invoice` | Yes (`invoices.normalized_invoice_number`) | Yes (`created_at`) | Yes (`vendor_organization_id`) | Yes (`DUPLICATE_INVOICES` metric) | **FRAUD-OWNED** (Raw Fact) | **YES** | Same vendor organization reusing identical invoice number on non-revision invoice. |
| **VENDOR_TAX_IDENTITY_MISMATCH** | `InvoiceMatchingEngine`, `KybProfile`, `Organization` | Yes (`invoices.vendor_gstin`, `invoices.vendor_pan`, `kyb_profiles`) | Yes (`created_at`) | Yes (`vendor_organization_id`) | No (Evaluated in Document Match) | **FRAUD-OWNED** (Raw Fact) | **YES** | Mismatch between extracted invoice tax ID and verified corporate registration. |
| **BENEFICIARY_NAME_MISMATCH** | `OrganizationBankAccount` | Yes (`organization_bank_accounts.name_match_status`) | Yes (`verified_at`) | Yes (`organization_id`) | Yes (`DOCUMENT_NAME_MISMATCH` rule) | **SHARED RAW SOURCE** | **YES** | Bank penny-drop verification status explicitly marked `MISMATCH`. |
| **BENEFICIARY_ACCOUNT_CHANGED** | `AuditService`, `OrganizationBankAccount` | Yes (`audit_logs`, `organization_bank_accounts`) | Yes (`created_at`) | Yes (`organization_id`) | Yes (`BANK_ACCOUNT_RECENTLY_CHANGED`) | **SHARED RAW SOURCE** | **PRODUCT POLICY REQUIRED** | Recent change requires authoritative lookback horizon definition (e.g. 24h vs 72h). |
| **PROVIDER_CREDIT_IDENTITY_REUSE** | `PaymentTransaction`, `SettlementInstruction` | Yes (B05 Unique constraints `uk_payment_tx_provider_utr`) | Yes (`created_at`) | Yes (`payment_instructions`) | No | **FRAUD-OWNED** (Raw Fact) | **YES** | Attempted reuse of external bank UTR/reference on separate financial instruction. |
| **MATERIAL_AMOUNT_MISMATCH** | `InvoiceMatchingEngine`, `Milestone` | Yes (`invoices.total_amount`, `milestones.amount`) | Yes (`created_at`) | Yes (`milestones.deal_id`) | Yes (`INVOICE_AMOUNT_MISMATCH`) | **DOCUMENT / FRAUD** | **YES** | Invoice billing exceeds contracted milestone allocation. |
| **PAYMENT_FAILURE_VELOCITY** | `PaymentTransaction` | Yes (`payment_transactions.status`) | Yes (`created_at`) | Yes (`payment_instructions`) | Yes (`PAYMENT_FAILURE_VELOCITY`) | **RISK-OWNED** | **PRODUCT POLICY REQUIRED** | Multi-attempt velocity thresholds require policy threshold agreement. |
| **NEW_DEVICE_HIGH_VALUE** | `RiskSignalObserved` | No (Ephemeral / Audit only) | Yes (`occurred_at`) | Unknown (Session) | Yes (`NEW_DEVICE_HIGH_VALUE`) | **RISK-OWNED** | **NO** | Telemetry lacks durable entity storage outside risk events. |
| **AUTHENTICATION_FAILURE_VELOCITY** | `AuditService` | Yes (`audit_logs`) | Yes (`timestamp`) | No (User-level) | Yes (`AUTH_FAILURE_VELOCITY`) | **RISK-OWNED** | **PRODUCT POLICY REQUIRED** | Session/IP level security signal; owned by platform security/risk. |

---

## Signal Ownership Matrix

```
+-------------------------------------------------------------------------------+
|                       RAW DOMAIN EVIDENCE SOURCES                             |
|  [InvoiceDocument]   [InvoiceCheck]   [OrganizationBankAccount]   [PaymentTx] |
+---------------------------------------+---------------------------------------+
                                        |
                   +--------------------+--------------------+
                   |                                         |
                   v                                         v
        +----------------------+                  +----------------------+
        |     FRAUD ENGINE     |                  |     RISK ENGINE      |
        |                      |                  |                      |
        |  Deterministic Facts |                  |  Behavioral Scoring  |
        |  - Duplicate Hash    |                  |  - Historical Trends |
        |  - Tax ID Mismatch   |                  |  - Performance Ratios|
        |  - Beneficiary Mis-  |                  |  - Exposure Velocity |
        |    match             |                  |                      |
        +----------+-----------+                  +----------+-----------+
                   |                                         |
                   +--------------------+--------------------+
                                        |
                                        v
                    +---------------------------------------+
                    |        RELEASE DECISION ENGINE        |
                    |   (Consumes orthogonal evaluations)   |
                    +---------------------------------------+
```

### Classification Details:

| Signal Name | Classification | Rationale |
|---|---|---|
| Exact Duplicate File Hash (`content_hash`) | **FRAUD-OWNED** | A duplicate document hash is an immutable fact of asset reuse. Fraud owns primary determination. |
| Normalized Invoice Number Collision | **FRAUD-OWNED** | Invoice number duplication is a primary indicator of duplicate financing/fraud. |
| Vendor Tax ID / PAN / GSTIN Mismatch | **FRAUD-OWNED** | Legal entity tax forgery / billing under another entity's identity is a fraud violation. |
| Beneficiary Account Name Mismatch (`MISMATCH`) | **SHARED RAW SOURCE** | Canonical source is `OrganizationBankAccount`. Fraud flags as hard prohibition; Risk may score as counterparty risk. |
| Bank Account Changed within Window | **SHARED RAW SOURCE** | Audit event. Evaluated by Fraud for diversion risk; evaluated by Risk for security score. |
| Provider Credit / UTR Reuse Attempt | **FRAUD-OWNED** | External settlement identifier reuse indicates credit fabrication. |
| Payment Failure Velocity | **RISK-OWNED** | High failure rate reflects technical or liquidity insolvency (Risk), not necessarily malicious fraud. |
| Milestone Rejection Frequency | **RISK-OWNED** | Performance and fulfillment quality. |
| Dispute Ratio / Adverse Disputes | **RISK-OWNED** | Platform relationship quality. |
| High-Value Velocity Anomaly | **RISK-OWNED** | Transactional volume deviation. |

---

## Risk vs Fraud Boundary

To prevent overlap, conflicting judgments, and architectural circularity, FinX enforces a strict separation of concerns between **Risk** and **Fraud**:

### Architectural Responsibilities:
- **Risk Engine (`EngineType.RISK`)**:
  - Measures **statistical uncertainty, behavioral deviation, counterparty creditworthiness, and cumulative platform exposure**.
  - Evaluates probabilistic factors, velocity trends, payment history ratios, dispute frequencies, and fulfillment timeliness.
  - Outputs a calibrated numeric score (0–100) representing risk bands (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
- **Fraud Engine (`EngineType.FRAUD`)**:
  - Detects **deliberate bad-faith deception, identity spoofing, duplicate asset monetization, collateral reuse, and unauthorized fund diversion**.
  - Evaluates verifiable binary facts and deterministic fraud patterns.
  - Does NOT output a fuzzy credit score; outputs deterministic decision dispositions (`ALLOW`, `REVIEW`, `BLOCK`) accompanied by discrete reason codes.

---

## Canonical Signal Sources

The canonical persisted entities providing evidence to the Fraud Engine are:
1. `InvoiceDocument` (`invoice_documents` table): Cryptographic content hash (`SHA-256`), file size, MIME type.
2. `Invoice` (`invoices` table): Normalized invoice number, vendor tax identifiers (GSTIN/PAN), invoice total, invoice date, revision lineage.
3. `InvoiceCheck` (`invoice_checks` table): Deterministic rule outcomes from `InvoiceMatchingEngine` (`DOCUMENT_HASH_CHECK`, `DUPLICATE_CHECK`, `GSTIN_MATCH`, `PAN_MATCH`, `INVOICE_DATE_VALID`).
4. `OrganizationBankAccount` (`organization_bank_accounts` table): Penny-drop verification status, name match status, account age/modification timestamp.
5. `PaymentTransaction` & `SettlementInstruction` (`payment_transactions`, `settlement_instructions` tables): External provider UTR, provider transaction reference, financial direction, settled amount.

---

## Double-Counting Prevention

> **INVARIANT F01 (Strict Double-Counting Prevention)**:
> The `FraudDecisionAdapter` MUST NEVER consume a derived `RiskScore` or `RiskAssessment` as input evidence if that risk score already incorporates invoice duplicate or mismatch penalties. Fraud MUST evaluate raw persisted entity facts (`InvoiceDocument`, `InvoiceCheck`, `OrganizationBankAccount`, `PaymentTransaction`) directly.
>
> Conversely, when `ReleaseDecisionEngine` evaluates composite eligibility, it treats `FRAUD == ALLOW` as a prerequisite gate and `RISK` as an exposure gate, ensuring no single penalty is amplified exponentially.

---

## Policy Versioning

Fraud policies are fully versioned, immutable configurations identified by:
- `policyKey`: Unique policy domain identifier (e.g. `FINX_FRAUD_DETERMINISTIC_BASE`).
- `version`: Semantic version string (e.g. `1.0.0`).
- `schemaVersion`: Schema structural version (`1`).
- `contentHash`: Cryptographic SHA-256 hash of the canonical JSON policy definition.

---

## EnginePolicyVersion Reuse

### Verdict on New Tables:
- **`fraud_policies` table**: **NOT REQUIRED**. Reuse existing `EnginePolicyVersion` with `engineType = EngineType.FRAUD`.
- **`fraud_signals` table**: **NOT REQUIRED** for initial read-only adapter. Read persisted canonical domain tables directly.
- **`fraud_cases` table**: **DEFERRED**. Human investigation case workflows are deferred to subsequent operations iterations.
- **`fraud_scores` table**: **NOT REQUIRED**. Fraud is deterministic policy decisioning, not arbitrary scoring.

### Initial Policy Registration:
The initial Fraud Policy will be registered in `engine_policy_versions` under:
- `engine_type`: `FRAUD`
- `policy_key`: `FINX_FRAUD_DETERMINISTIC_BASE`
- `version`: `1.0.0`
- `schema_version`: `1`
- `content_hash`: SHA-256 of policy configuration JSON
- `is_active`: `true`

---

## Initial Subject / Tenant Contract

### Subject Definition:
The initial `FraudDecisionAdapter` evaluates fraud in the context of a **Milestone Release Request**.

- **`subjectType`**: `DecisionSubjectType.MILESTONE`
- **`subjectId`**: `milestone.getId()`
- **`organizationId` (Tenant Owner)**: `deal.getBuyerOrganizationId()` (the funding organization whose escrow/capital is protected).

### Rationale:
- Financial release authorization happens per-milestone.
- The milestone links directly to:
  1. The underlying **Deal** (buyer organization, seller organization, currency).
  2. The approved **Invoices** (`invoices.milestone_id`).
  3. The associated **Invoice Documents** (`invoice_documents.invoice_id`).
  4. The registered **Beneficiary Bank Account** (`sellerOrganization.bankAccounts`).
  5. Associated **Escrow and Payment Transactions** (`payment_transactions.payment_instruction_id`).

---

## Signal Definitions

Every signal evaluated in the initial policy is strictly classified:

### S01: Exact Document Cryptographic Collision
- **Source**: `InvoiceDocumentRepository.findByContentHash(hash)`
- **Source Identity**: `invoice_documents.content_hash`
- **Dedupe Key**: `DOCUMENT_HASH_DUPLICATE::<content_hash>`
- **Freshness**: Immutable document hash.
- **Threshold Required?**: **NO** (Exact binary match).
- **Current Value Available?**: **YES** (`InvoiceDocument.contentHash`).
- **Policy Severity**: **`BLOCK`** (`FRAUD_DUPLICATE_DOCUMENT_HASH`).

### S02: Vendor Invoice Number Duplication
- **Source**: `InvoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(...)`
- **Source Identity**: `invoices.normalized_invoice_number` + `invoices.vendor_organization_id`
- **Dedupe Key**: `INVOICE_NUM_DUPLICATE::<vendor_org_id>::<normalized_number>`
- **Freshness**: Evaluated across non-revision, active invoices.
- **Threshold Required?**: **NO** (Exact binary collision for non-revisions).
- **Current Value Available?**: **YES** (`Invoice.normalizedInvoiceNumber`).
- **Policy Severity**: **`BLOCK`** (`FRAUD_DUPLICATE_INVOICE_NUMBER`).

### S03: Tax Identity (PAN / GSTIN) Forgery / Mismatch
- **Source**: `InvoiceCheckRepository` where `check_type IN ('GSTIN_MATCH', 'PAN_MATCH')` AND `status == 'FAIL'`
- **Source Identity**: `invoice_checks.id`
- **Dedupe Key**: `TAX_ID_MISMATCH::<invoice_id>::<check_type>`
- **Freshness**: Latest invoice check run on active invoice revision.
- **Threshold Required?**: **NO** (Exact binary mismatch between billed entity and seller registration).
- **Current Value Available?**: **YES** (`InvoiceCheck.status == CheckStatus.FAIL`).
- **Policy Severity**: **`BLOCK`** (`FRAUD_TAX_IDENTITY_MISMATCH`).

### S04: Beneficiary Bank Account Explicit Name Mismatch
- **Source**: `OrganizationBankAccount.getNameMatchStatus()`
- **Source Identity**: `organization_bank_accounts.id`
- **Dedupe Key**: `BENEFICIARY_NAME_MISMATCH::<bank_account_id>`
- **Freshness**: Verified bank account attached to seller.
- **Threshold Required?**: **NO** (Explicit string enum `"MISMATCH"` returned by penny-drop verification).
- **Current Value Available?**: **YES** (`OrganizationBankAccount.nameMatchStatus`).
- **Policy Severity**: **`BLOCK`** (`FRAUD_BENEFICIARY_NAME_MISMATCH`).

### S05: Provider Bank Credit / UTR Collision
- **Source**: `PaymentTransactionRepository` / `SettlementInstructionRepository`
- **Source Identity**: `payment_transactions.utr_number` / `settlement_instructions.utr_number`
- **Dedupe Key**: `PROVIDER_UTR_COLLISION::<provider>::<utr_number>`
- **Freshness**: Real-time transaction persistence.
- **Threshold Required?**: **NO** (Enforced by B05 uniqueness constraints).
- **Current Value Available?**: **YES** (`uk_payment_tx_provider_utr`).
- **Policy Severity**: **`BLOCK`** (`FRAUD_PROVIDER_REFERENCE_REUSE`).

### S06: Recent Beneficiary Bank Account Change
- **Source**: `OrganizationBankAccount.getCreatedAt()` / `AuditService`
- **Source Identity**: `organization_bank_accounts.id`
- **Dedupe Key**: `BENEFICIARY_RECENTLY_MODIFIED::<bank_account_id>`
- **Freshness**: Comparison between account creation time and milestone release request time.
- **Threshold Required?**: **YES** (Observation window horizon).
- **Policy Value Available?**: **PRODUCT POLICY REQUIRED** (e.g. 72 hours lookback).
- **Proposed Severity**: **`REVIEW`** (`FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED`).

### S07: Suspicious Future / Antiquated Invoice Date
- **Source**: `InvoiceCheckRepository` where `check_type == 'INVOICE_DATE_VALID'` AND `status == 'FAIL'`
- **Source Identity**: `invoice_checks.id`
- **Dedupe Key**: `INVOICE_DATE_ANOMALY::<invoice_id>`
- **Freshness**: Extracted date vs issuance date.
- **Threshold Required?**: **NO** (Date anomaly check already executed in invoice pipeline).
- **Current Value Available?**: **YES** (`InvoiceCheck.checkType == InvoiceCheckType.INVOICE_DATE_VALID`).
- **Proposed Severity**: **`REVIEW`** (`FRAUD_INVOICE_DATE_ANOMALY`).

---

## Proposed Decision Mapping

*(PROPOSED ONLY — NOT IMPLEMENTED IN THIS PHASE)*

```
                       +-----------------------------------+
                       |    Evaluate Persisted Signals     |
                       +-----------------+-----------------+
                                         |
           +-----------------------------+-----------------------------+
           |                             |                             |
           v                             v                             v
  Any Hard S01-S05 Violations?    Any S06-S07 Soft Signals?    No Fraud Indicators Detected
           |                             |                             |
           v                             v                             v
      +---------+                   +----------+                  +---------+
      |  BLOCK  |                   |  REVIEW  |                  |  ALLOW  |
      +---------+                   +----------+                  +---------+
```

---

## Proposed Reason Codes

*(PROPOSED ONLY — NOT IMPLEMENTED IN THIS PHASE)*

| Decision Status | Reason Code | Description |
|---|---|---|
| **BLOCK** | `FRAUD_DUPLICATE_DOCUMENT_HASH` | Cryptographic SHA-256 hash identical to previously submitted document. |
| **BLOCK** | `FRAUD_DUPLICATE_INVOICE_NUMBER` | Normalized invoice number already utilized by vendor organization. |
| **BLOCK** | `FRAUD_TAX_IDENTITY_MISMATCH` | Invoice GSTIN/PAN does not match verified KYB corporate registry. |
| **BLOCK** | `FRAUD_BENEFICIARY_NAME_MISMATCH` | Payout destination bank account verification reported name mismatch. |
| **BLOCK** | `FRAUD_PROVIDER_REFERENCE_REUSE` | External bank UTR / transaction reference collision detected. |
| **REVIEW** | `FRAUD_BENEFICIARY_RECENTLY_MODIFIED` | Payout bank account was registered/modified within policy observation window. |
| **REVIEW** | `FRAUD_INVOICE_DATE_ANOMALY` | Invoice date is future-dated or significantly predates deal contract. |
| **REVIEW** | `FRAUD_MISSING_INVOICE_EVIDENCE` | Milestone requires invoice verification but no assessment record exists. |
| **REVIEW** | `FRAUD_BENEFICIARY_UNASSIGNED` | Milestone requires release but seller organization has no eligible verified bank account. |
| **ALLOW** | `FRAUD_EVALUATION_PASSED` | All deterministic fraud checks evaluated with zero detected anomalies. |

---

## Evidence References

Each decision rendered will record discrete, durable `EvidenceReference` entries:
- `INVOICE_DOCUMENT` -> `invoice_documents.id` (with content hash)
- `INVOICE_ASSESSMENT` -> `invoice_assessments.id`
- `INVOICE_CHECK` -> `invoice_checks.id`
- `ORGANIZATION_BANK_ACCOUNT` -> `organization_bank_accounts.id`
- `PAYMENT_TRANSACTION` -> `payment_transactions.id`
- `SETTLEMENT_INSTRUCTION` -> `settlement_instructions.id`

---

## Input Fingerprint

$$\text{Fingerprint} = \text{SHA-256}(\text{Ordered Raw Source IDs} \parallel \text{Canonical Hashes} \parallel \text{Policy Key} \parallel \text{Policy Version})$$

### Fingerprint Material (Strictly Ordered):
1. `milestoneId` (UUID)
2. `dealId` (UUID)
3. `buyerOrganizationId` (UUID)
4. `sellerOrganizationId` (UUID)
5. `invoiceId` (UUID, if present)
6. `invoiceDocumentHash` (String, if present)
7. `normalizedInvoiceNumber` (String, if present)
8. `sellerBankAccountId` (UUID, if present)
9. `sellerBankAccountNameMatchStatus` (String, if present)
10. `policyKey` (`FINX_FRAUD_DETERMINISTIC_BASE`)
11. `policyVersion` (`1.0.0`)

---

## Missing Evidence

- **Missing Invoice on Invoice-Required Milestone**:
  - Evaluates to `DecisionStatus.REVIEW` with reason `FRAUD_MISSING_INVOICE_EVIDENCE`.
  - Does NOT evaluate to `ALLOW` (fail closed).
- **Missing Beneficiary Bank Account**:
  - Evaluates to `DecisionStatus.REVIEW` with reason `FRAUD_BENEFICIARY_UNASSIGNED`.

---

## Error Semantics

- **Unknown Policy Version**:
  - Throws `IllegalStateException` / results in `DecisionStatus.REVIEW` (engine fail closed).
- **Cross-Tenant Access Attempt**:
  - Throws `SecurityException` / `AccessDeniedException`.
- **Database Connection Failure**:
  - Propagates transaction rollback without producing corrupted decision records.

---

## Schema Requirements

- **Initial Read-Only Adapter**: **NO MIGRATION REQUIRED**.
  - Persists decisions in existing `engine_decisions` table (V25).
  - Uses existing `engine_policy_versions` table (V25).
  - Reads existing `invoices`, `invoice_documents`, `invoice_checks`, `organization_bank_accounts`, `payment_transactions`, `settlement_instructions`.

---

## Deferred Fraud Capabilities

1. Dynamic ML fraud scoring and graph neural network entity clustering.
2. Investigator case assignment, note-taking, and fraud case queue (`fraud_cases`).
3. External fraud consortium telemetry sharing (e.g. device fingerprinting vendor SDKs).

---

## Test Plan

Future unit and persistence tests for `FraudDecisionAdapter` will verify:
1. **Canonical Signal Deduplication**: An identical duplicate document hash is evaluated once and not multiplied.
2. **Double-Counting Isolation**: Changes in composite `RiskScore` do not alter deterministic `FRAUD` evaluation.
3. **Exact Reproducibility**: Given identical evidence records, input fingerprint and decision status match identically across repeated executions.
4. **Hard Prohibitions**: Exact document collision or explicit beneficiary name mismatch always triggers `BLOCK`.
5. **Fail-Closed on Missing Data**: Missing invoice or beneficiary evidence produces `REVIEW`, never `ALLOW`.
6. **Tenant Isolation**: Evaluation cannot read invoices or bank accounts belonging to foreign tenant organizations.
7. **Zero Side Effects**: Decision evaluation performs zero financial writes and causes zero external banking API calls.

---

## Implementation Readiness

**FRAUD INITIAL ADAPTER: READY AFTER POLICY APPROVAL**

- All raw facts exist in durable storage today.
- Ownership matrix resolves double-counting risks.
- Zero schema migrations required for read-only adapter.
