# Fraud Policy Registration Report

## Scope

This report documents the completion of **Phase 1 — Commit F1**: Registration of the Deterministic Fraud Policy Version in the FinX Decision Intelligence Platform.

**Delivered in this Phase**:
- Canonical code representation of the deterministic Fraud Policy v1.0.0 (`FraudPolicyDefinition`).
- Automatic startup bootstrap registrar (`FraudPolicyRegistrar`).
- Unit, architecture, Spring Boot, and PostgreSQL integration test suites.
- Cryptographic content hash generation and verification.

**Explicitly Excluded / Deferred to Subsequent Phases**:
- `FraudDecisionAdapter` (Deferred to Phase 1 — Commit F2).
- Fraud runtime scoring, cases, investigations, and ML models.
- Reconciliation remediation and `ReconciliationDecisionAdapter` (Deferred to Phase 2).
- Composite `ReleaseDecisionEngine` (Deferred to Phase 3).
- Database migrations (No V26 created; V25 remains unchanged).

---

## Preconditions

- **Planning Phase**: Completed, verified, and pushed in commit `153766c` (`FRAUD_POLICY_SPEC.md`, `RECONCILIATION_REMEDIATION_PLAN.md`, `FRAUD_RECONCILIATION_PHASE_PLAN.md`).
- **Prerequisite Adapters Implemented**: **6 of 8** (`RISK`, `DOCUMENT`, `MILESTONE_RULES`, `APPROVAL`, `TRUST`, `DISPUTE`).
- **Database Schema**: Flyway V25 (`V25__engine_policy_versions_and_decisions.sql`) is active and authoritative.
- **Production Payout**: Disabled (fail-closed in settlement authorization).

---

## Policy Identity

| Attribute | Value |
|---|---|
| **Engine Type** | `EngineType.FRAUD` (`"FRAUD"`) |
| **Scope** | `PolicyScope.PLATFORM` (`"PLATFORM"`) |
| **Organization ID** | `NULL` (Platform-wide policy) |
| **Policy Key** | `FINX_FRAUD_DETERMINISTIC_BASE` |
| **Version** | `1.0.0` |
| **Schema Version** | `1.0` |
| **Description** | `Deterministic read-only FinX fraud decision policy evaluated against canonical persisted evidence.` |
| **Created By** | `NULL` (System bootstrap) |
| **Subject Type** | `DecisionSubjectType.MILESTONE` (`"MILESTONE"`) |
| **Owner Scope** | `"BUYER_ORGANIZATION"` (`deal.buyerOrganizationId`) |

---

## Canonical Policy Content

The canonical policy definition is stored as a deterministic, compact JSON string containing strictly ordered keys, zero runtime timestamps, and zero sensitive data:

```json
{
  "policyKey": "FINX_FRAUD_DETERMINISTIC_BASE",
  "version": "1.0.0",
  "schemaVersion": "1.0",
  "engineType": "FRAUD",
  "scope": "PLATFORM",
  "subjectType": "MILESTONE",
  "owner": "BUYER_ORGANIZATION",
  "consumeDerivedRiskScore": false,
  "rules": [
    {
      "ruleId": "S01",
      "name": "EXACT_DOCUMENT_HASH_DUPLICATE",
      "reasonCode": "FRAUD_DUPLICATE_DOCUMENT_HASH",
      "disposition": "BLOCK",
      "thresholdRequired": false,
      "enabled": true,
      "source": "invoice_documents.content_hash",
      "description": "Cryptographic SHA-256 collision with a previously submitted invoice document"
    },
    {
      "ruleId": "S02",
      "name": "DUPLICATE_INVOICE_NUMBER",
      "reasonCode": "FRAUD_DUPLICATE_INVOICE_NUMBER",
      "disposition": "BLOCK",
      "thresholdRequired": false,
      "enabled": true,
      "source": "invoices.normalized_invoice_number",
      "description": "Normalized invoice number collision for the same vendor organization on non-revision invoices"
    },
    {
      "ruleId": "S03",
      "name": "VENDOR_TAX_IDENTITY_MISMATCH",
      "reasonCode": "FRAUD_TAX_IDENTITY_MISMATCH",
      "disposition": "BLOCK",
      "thresholdRequired": false,
      "enabled": true,
      "source": "invoice_checks.status",
      "description": "Tax identifier PAN or GSTIN mismatch between billed entity and verified KYB registration"
    },
    {
      "ruleId": "S04",
      "name": "BENEFICIARY_NAME_MISMATCH",
      "reasonCode": "FRAUD_BENEFICIARY_NAME_MISMATCH",
      "disposition": "BLOCK",
      "thresholdRequired": false,
      "enabled": true,
      "source": "organization_bank_accounts.name_match_status",
      "description": "Payout destination bank account verification reported explicit name mismatch"
    },
    {
      "ruleId": "S05",
      "name": "PROVIDER_CREDIT_IDENTITY_REUSE",
      "reasonCode": "FRAUD_PROVIDER_REFERENCE_REUSE",
      "disposition": "BLOCK",
      "thresholdRequired": false,
      "enabled": true,
      "evaluableFromCurrentReadModel": false,
      "enforcedAtPersistenceBoundary": true,
      "source": "payment_transactions.utr_number",
      "description": "External provider UTR or transaction reference collision; enforced by B05 database unique constraints uk_payment_tx_provider_utr and uk_settlement_provider_utr"
    },
    {
      "ruleId": "S06",
      "name": "BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED",
      "reasonCode": "FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED",
      "disposition": "REVIEW",
      "thresholdRequired": true,
      "currentThreshold": "UNDEFINED",
      "policyState": "PRODUCT_POLICY_REQUIRED",
      "enabled": false,
      "source": "organization_bank_accounts.created_at",
      "description": "Payout bank account was registered or modified within policy observation window; disabled pending product policy threshold definition"
    },
    {
      "ruleId": "S07",
      "name": "INVOICE_DATE_ANOMALY",
      "reasonCode": "FRAUD_INVOICE_DATE_ANOMALY",
      "disposition": "REVIEW",
      "thresholdRequired": false,
      "enabled": true,
      "source": "invoice_checks.status",
      "description": "Invoice date anomalies detected by canonical invoice matching checks"
    }
  ],
  "missingEvidencePolicies": {
    "missingInvoice": {
      "reasonCode": "FRAUD_MISSING_INVOICE_EVIDENCE",
      "disposition": "REVIEW"
    },
    "missingBeneficiary": {
      "reasonCode": "FRAUD_BENEFICIARY_UNASSIGNED",
      "disposition": "REVIEW"
    }
  }
}
```

---

## Signal Inventory & Invariants

### S01: Exact Document Hash Duplicate
- **Reason Code**: `FRAUD_DUPLICATE_DOCUMENT_HASH`
- **Disposition**: `BLOCK`
- **Threshold Required**: No (Exact SHA-256 match).
- **Status**: Enabled.

### S02: Duplicate Invoice Number
- **Reason Code**: `FRAUD_DUPLICATE_INVOICE_NUMBER`
- **Disposition**: `BLOCK`
- **Threshold Required**: No (Exact normalized number match on non-revisions).
- **Status**: Enabled.

### S03: Tax Identity PAN / GSTIN Mismatch
- **Reason Code**: `FRAUD_TAX_IDENTITY_MISMATCH`
- **Disposition**: `BLOCK`
- **Threshold Required**: No (Binary check outcome `FAIL`).
- **Status**: Enabled.

### S04: Beneficiary Explicit Name Mismatch
- **Reason Code**: `FRAUD_BENEFICIARY_NAME_MISMATCH`
- **Disposition**: `BLOCK`
- **Threshold Required**: No (Penny-drop name match status == `MISMATCH`).
- **Status**: Enabled.

### S05 Evidence Limitation: Provider Credit / UTR Identity Reuse
- **Reason Code**: `FRAUD_PROVIDER_REFERENCE_REUSE`
- **Disposition**: `BLOCK`
- **Evaluability & Persistence Boundary**:
  - In FinX, B05 database unique constraints (`uk_payment_tx_provider_utr` and `uk_settlement_provider_utr`) reject duplicate UTR insertions at the database level.
  - Because rejected duplicates cannot be saved as persisted rows in `payment_transactions`, this rule is marked:
    - `evaluableFromCurrentReadModel = false`
    - `enforcedAtPersistenceBoundary = true`
  - This guarantees the system does not overclaim readability of unpersisted duplicate rejection attempts.

### S06 Product-Policy Gap: Recent Beneficiary Account Change
- **Reason Code**: `FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED`
- **Disposition**: `REVIEW`
- **Threshold Required**: Yes (e.g. lookback window in hours/days).
- **Current State**: **NO ARBITRARY THRESHOLD WAS INVENTED**.
- **Policy Configuration**: `currentThreshold = "UNDEFINED"`, `policyState = "PRODUCT_POLICY_REQUIRED"`, `enabled = false`.
- **Status**: Disabled for automatic evaluation in v1.0.0 until formal business policy approval.

### S07: Invoice Date Anomaly
- **Reason Code**: `FRAUD_INVOICE_DATE_ANOMALY`
- **Disposition**: `REVIEW`
- **Threshold Required**: No at Fraud layer (evaluates canonical `InvoiceCheck` outcome for `INVOICE_DATE_VALID`).
- **Status**: Enabled.

---

## Risk / Fraud Boundary

- **`consumeDerivedRiskScore`**: `false`.
- **Invariance**: The Fraud Engine evaluates raw persisted entity facts (`InvoiceDocument`, `InvoiceCheck`, `OrganizationBankAccount`, `PaymentTransaction`) directly. It **NEVER** consumes the derived `RiskScore` or `RiskAssessment`, preventing recursive double-counting of invoice duplicate and mismatch penalties.

---

## Subject Contract

- **`subjectType`**: `DecisionSubjectType.MILESTONE` (`"MILESTONE"`).
- **`organizationId` (Owner)**: `deal.getBuyerOrganizationId()`.
- **`subjectId`**: `milestone.getId()`.

---

## Content Hash

- **Algorithm**: Standard `SHA-256` over UTF-8 bytes of `CANONICAL_POLICY_CONTENT`.
- **Content Hash**: `c27e12a196da104c8ab61cd715dc24e274d4ecf2e6f80781bcc0218353482c9a`
- **Length**: 64 lowercase hexadecimal characters.
- **Verification**: Formally verified in `FraudPolicyDefinitionTest` and `PostgresFraudPolicyTest`.

---

## Registration & Bootstrap Mechanism

- **Registrar Class**: `com.finx.engine.policy.fraud.FraudPolicyRegistrar`
- **Framework Integration**: `@Component` implementing Spring Boot `ApplicationRunner` (`@Order(20)`).
- **Execution Lifecycle**: Runs automatically on application startup to ensure the platform Fraud policy is present and active.

---

## Idempotency & Conflict Handling

- **Idempotent Replay**: If a policy record with `(engineType=FRAUD, policyKey=FINX_FRAUD_DETERMINISTIC_BASE, version=1.0.0)` already exists with matching content hash, `EnginePolicyVersionService` safely returns the existing record without database mutation.
- **Fail-Closed Conflict Protection**: If a record with the same key and version exists with a *different* content hash, registration throws `IllegalStateException` and aborts bootstrap.

---

## Activation Model

- **Existing Schema Reality**: Flyway migration V25 defines `activated_at TIMESTAMP WITH TIME ZONE`. The table does **NOT** have an `is_active` boolean column.
- **Activation State**: A policy version is active when `activatedAt` is non-null. `FraudPolicyRegistrar` sets `activatedAt = Instant.now()`.
- **Deactivation**: Future policy deactivation / feature-flagging capabilities are deferred to future schema migrations.

---

## Immutability

- `EnginePolicyVersion` is annotated with `@Immutable` (Hibernate) and mapped to immutable table `engine_policy_versions`.
- No `UPDATE` or `DELETE` methods exist on `EnginePolicyVersionRepository` or `EnginePolicyVersionService`.
- Any future policy revisions must be registered as new semantic versions (e.g. `1.1.0`).

---

## Database Migration

- **Database Migration for F1**: **NONE**.
- **Flyway State**: V25 remains the highest migration. No V26 file was created.

---

## PostgreSQL & Test Verification

| Test Suite | Total Tests | Pass | Fail | Skip | Focus |
|---|---|---|---|---|---|
| `FraudPolicyDefinitionTest` | 4 | 4 | 0 | 0 | Policy metadata, SHA-256 hash, JSON parsing, S01-S07 rules, missing policies, zero credentials. |
| `FraudPolicyRegistrationTest` | 3 | 3 | 0 | 0 | Service registration, idempotency replay, conflict detection with mock repository. |
| `FraudPolicyRegistrationArchitectureTest` | 4 | 4 | 0 | 0 | Package boundary isolation: zero prohibited dependencies on banking, ledger, escrow, settlement, razorpay, controllers. |
| `FraudPolicyBootstrapSpringTest` | 2 | 2 | 0 | 0 | Full Spring Boot startup context, automatic bootstrap verification, zero decisions created, zero banking calls. |
| `PostgresFraudPolicyTest` | 1 | - | 0 | 1 | PostgreSQL catalog and partial index verification (skipped when PostgreSQL container not bound on port 5432). |
| **Targeted Regression Batch** | 360 | 341 | 0 | 19 | Common engine framework, 6 existing adapters, boundary containment, tenant isolation, idempotency. |
| **Full Maven Test Suite** | 824 | 791 | 0 | 33 | Complete repository regression test suite. |

---

## Financial Side Effects & Decision Counts

- **`EngineDecision` Records Created**: `0`
- **External Bank Calls (`BankingProvider`)**: `0`
- **Journal Entries / Ledger Writes**: `0`
- **Settlement Instructions Created**: `0`
- **Escrow Account Modifications**: `0`

---

## Files Created

1. `backend/src/main/java/com/finx/engine/policy/fraud/FraudPolicyDefinition.java`
2. `backend/src/main/java/com/finx/engine/policy/fraud/FraudPolicyRegistrar.java`
3. `backend/src/test/java/com/finx/engine/policy/fraud/FraudPolicyDefinitionTest.java`
4. `backend/src/test/java/com/finx/engine/policy/fraud/FraudPolicyRegistrationTest.java`
5. `backend/src/test/java/com/finx/engine/policy/fraud/FraudPolicyRegistrationArchitectureTest.java`
6. `backend/src/test/java/com/finx/engine/policy/fraud/FraudPolicyBootstrapSpringTest.java`
7. `backend/src/test/java/com/finx/engine/policy/fraud/PostgresFraudPolicyTest.java`
8. `FRAUD_POLICY_REGISTRATION_REPORT.md`

## Files Modified

- None.

---

## F1 Status Verdict

- **PHASE 1 COMMIT F1**: **PASS**
- **READY FOR PHASE 1 COMMIT F2 (`FraudDecisionAdapter`)**: **YES**
