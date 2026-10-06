# Fraud + Reconciliation Phase Plan

## Current Engine Coverage

The FinX Decision Intelligence Platform currently has **6 of 8** prerequisite component adapters fully implemented and verified:

| Engine Type | Status | Adapter Class / Implementation | Tests Verified | Durability |
|---|---|---|---|---|
| **1. RISK** | **IMPLEMENTED** | `com.finx.engine.adapter.RiskDecisionAdapter` | Pass (`RiskDecisionAdapterTest`) | Durable (`risk_assessments`, `risk_scores`) |
| **2. DOCUMENT** | **IMPLEMENTED** | `com.finx.engine.adapter.DocumentDecisionAdapter` | Pass (`DocumentDecisionAdapterTest`) | Durable (`invoices`, `invoice_assessments`) |
| **3. MILESTONE_RULES** | **IMPLEMENTED** | `com.finx.engine.adapter.MilestoneRulesDecisionAdapter` | Pass (`MilestoneRulesDecisionAdapterTest`) | Durable (`milestones`, `deals`, `milestone_rules`) |
| **4. APPROVAL** | **IMPLEMENTED READ-ONLY** | `com.finx.engine.adapter.ApprovalDecisionAdapter` | Pass (`ApprovalDecisionAdapterTest`) | Durable (`approvals`, `approval_chains`) |
| **5. TRUST** | **IMPLEMENTED** | `com.finx.engine.adapter.TrustDecisionAdapter` | Pass (`TrustDecisionAdapterTest`) | Durable (`organizations`, `kyb_profiles`, `kyc_profiles`) |
| **6. DISPUTE** | **IMPLEMENTED** | `com.finx.engine.adapter.DisputeDecisionAdapter` | Pass (`DisputeDecisionAdapterTest`) | Durable (`disputes`, `deals`, `milestones`) |
| **7. FRAUD** | **PLANNING COMPLETED / NOT IMPLEMENTED** | Specified in `FRAUD_POLICY_SPEC.md` | Pending implementation | Ready for read-only adapter after policy approval |
| **8. RECONCILIATION** | **REMEDIATION PLANNED / NOT IMPLEMENTED** | Planned in `RECONCILIATION_REMEDIATION_PLAN.md` | Pending remediation | Blocked by defects R01–R16 |
| **9. RELEASE** | **COMPOSITE FINAL / NOT IMPLEMENTED** | Dependent on 8 prerequisite engines | Blocked | Gated until all 8 engines are authoritative |

---

## Fraud Status

- **Architecture Status**: **SPECIFIED & READY FOR IMPLEMENTATION**.
- **Schema Requirements**: **NO MIGRATION REQUIRED** for initial read-only adapter.
  - Utilizes existing `engine_decisions` and `engine_policy_versions` (V25).
  - Evaluates persisted facts from `invoice_documents`, `invoice_checks`, `organization_bank_accounts`, and `payment_transactions`.
- **Double-Counting Strategy**: **DEFINED & ISOLATED**. Fraud evaluates raw entity facts directly and never consumes derived composite risk scores.

---

## Reconciliation Status

- **Architecture Status**: **REMEDIATION FULLY PLANNED / CURRENT RUNS NON-AUTHORITATIVE**.
- **Defects Audited**: 16 confirmed defects (R01–R16) including settlement amount omission, missing outbound detection, currency/direction ignorance, and ephemeral statement storage.
- **Migration Requirements**: **V26 MIGRATION REQUIRED** (`V26__durable_bank_statement_reconciliation.sql`).
- **Read-Only Adapter**: Blocked until statement ingestion and deterministic matching are remediated.

---

## Release Status

- **Status**: **STRICTLY BLOCKED**.
- **Reason**: `ReleaseDecisionEngine` cannot be implemented or authorized to trigger financial release instructions until all eight prerequisite engines evaluate authoritatively with zero unverified assumptions.

---

## Exact Next Commit Sequence

To maintain pristine architectural containment and ensure every step is verifiable in isolation, future implementation must proceed in the following exact sequence of small, atomic commits:

```
+-------------------------------------------------------------------------------+
| PHASE 1: FRAUD DECISION ADAPTER (Schema-Free)                                 |
| Commit F1: Register Fraud Policy in EnginePolicyVersionService                |
| Commit F2: Implement FraudDecisionAdapter + Persistence Tests                 |
+-------------------------------------------------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
| PHASE 2: RECONCILIATION REMEDIATION                                           |
| Commit R1: V26 Flyway Migration + Bank Statement Entities & Repositories      |
| Commit R2: Implement BankStatementIngestionService (Durable Ingestion)        |
| Commit R3: Implement DeterministicReconciliationMatcher (Fixing R01-R15)      |
| Commit R4: Implement ReconciliationExceptionService (Exception Lifecycle)     |
| Commit R5: Implement ReconciliationDecisionAdapter + Persistence Tests        |
+-------------------------------------------------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
| PHASE 3: RELEASE DECISION ENGINE AUDIT & DESIGN                               |
| Commit REL-1: Release Decision Engine Specification & Readiness Audit         |
+-------------------------------------------------------------------------------+
```

### Granular Commit Definitions:

1. **Commit F1 (`feat: register deterministic fraud policy version`)**:
   - Register default active `EnginePolicyVersion` for `EngineType.FRAUD` (`FINX_FRAUD_DETERMINISTIC_BASE` v1.0.0).
   - Zero schema migrations. Zero business logic alteration.

2. **Commit F2 (`feat: add read-only fraud decision adapter`)**:
   - Implement `FraudDecisionAdapter` extending `ReadOnlyDecisionAdapter`.
   - Implement comprehensive unit tests (`FraudDecisionAdapterTest`) and persistence tests (`FraudDecisionPersistenceTest`).
   - Validate 100% read-only evaluation and deterministic fingerprinting.

3. **Commit R1 (`feat: add durable bank statement schema and entities`)**:
   - Create `V26__durable_bank_statement_reconciliation.sql` (`bank_statement_imports`, `bank_statement_lines`).
   - Create JPA entities and repositories for statement models.
   - Zero alteration of existing reconciliation runs.

4. **Commit R2 (`feat: add durable bank statement ingestion service`)**:
   - Implement `BankStatementIngestionService` with idempotent source hashing and window validation.

5. **Commit R3 (`feat: implement deterministic reconciliation matcher`)**:
   - Implement `DeterministicReconciliationMatcher` fixing defects R01 through R15 (amount, currency, direction, 1-to-1 consumption, bounded window, missing outbound).

6. **Commit R4 (`feat: add reconciliation exception management service`)**:
   - Implement `ReconciliationExceptionService` for operator resolution and audit logging.

7. **Commit R5 (`feat: add read-only reconciliation decision adapter`)**:
   - Implement `ReconciliationDecisionAdapter` reading persisted clean runs.
   - Comprehensive test verification.

---

## Migration Sequence

1. **Current Head**: `V25__engine_policy_versions_and_decisions.sql`.
2. **Phase 1 (Fraud)**: **NO MIGRATION**.
3. **Phase 2 (Reconciliation)**: `V26__durable_bank_statement_reconciliation.sql`.
4. **Phase 3+ (Future)**: Any release instruction or outbox tables will be assigned `V27+`.

---

## Testing Sequence

Every commit in the implementation sequence must pass:
1. **Targeted Unit Tests**: Specific adapter unit tests with mocked repositories.
2. **PostgreSQL Persistence Tests**: Real database integration tests using Testcontainers or embedded PostgreSQL to verify constraints, triggers, and SQL queries.
3. **Architecture & Boundary Tests**:
   - `RestrictedFrameworkBoundaryContainmentTest`
   - `ReadOnlyDecisionAdapterArchitectureTest`
   - `TenantIsolationContainmentTest`
4. **Full Maven Suite**: `.\mvnw.cmd test` resulting in `BUILD SUCCESS` with 0 failures and 0 errors.

---

## Rollback / Containment Rules

1. **Read-Only Invariant**: All decision adapters must remain strictly read-only with respect to business domain state. They write ONLY to `engine_decisions`.
2. **Zero Direct Network I/O**: Decision adapters must NEVER invoke external HTTP services or bank APIs during decision evaluation.
3. **Feature Flag Isolation**: If any new component causes regression, it can be disabled via `engine_policy_versions.is_active = false` without requiring code rollbacks.

---

## No-Execution Boundary

The boundary between **Decision Intelligence** and **Financial Execution** is absolute:

```
+-------------------------------------------------------------------------------+
|                       DECISION INTELLIGENCE LAYER                             |
|                                                                               |
|  [Risk] [Document] [MilestoneRules] [Approval] [Trust] [Dispute] [Fraud]      |
|  [Reconciliation]                                                             |
|                         \                                                     |
|                          v                                                    |
|              [Release Decision Engine] (Decision Only)                        |
+-----------------------------------+-------------------------------------------+
                                    |
                  ==================|================== (AIR GAP / NO DIRECT CALL)
                                    v
+-------------------------------------------------------------------------------+
|                       FINANCIAL EXECUTION LAYER                               |
|                                                                               |
|  [Durable Release Instructions] -> [Outbox Worker] -> [Banking Provider]      |
|  (CURRENTLY UNREACHABLE & DISABLED)                                           |
+-------------------------------------------------------------------------------+
```

---

## Production Payout

**PRODUCTION PAYOUT STATUS: STRICTLY DISABLED**

- `BankingProvider.initiateSettlement` is unreachable from any decision adapter.
- Fail-closed security blocks remain active in `SettlementAuthorizationService` and `VerificationEligibilityService`.

---

## Release Readiness Gate

Before work on `ReleaseDecisionEngine` can commence, all of the following conditions MUST be satisfied:

1. [x] `RISK` adapter implemented and passing.
2. [x] `DOCUMENT` adapter implemented and passing.
3. [x] `MILESTONE_RULES` adapter implemented and passing.
4. [x] `APPROVAL` adapter implemented and passing.
5. [x] `TRUST` adapter implemented and passing.
6. [x] `DISPUTE` adapter implemented and passing.
7. [ ] `FRAUD` adapter implemented, registered in `engine_policy_versions`, and verified passing.
8. [ ] `RECONCILIATION` remediated (V26 schema, ingestion, matcher, exception lifecycle) and adapter verified passing.
9. [ ] All 8 adapters demonstrate 100% test pass rate in the full regression suite.
