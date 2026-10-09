# Phase 4 — Deterministic Release Policy Registration Report

## 1. Executive Summary

This report documents the formal registration of the canonical deterministic **RELEASE** policy:
`FINX_RELEASE_DETERMINISTIC_BASE` version `1.0.0` within the FinX `EnginePolicyVersion` catalog.

Following the verification and signoff of the Release Decision Readiness Audit (Phase 3), the RELEASE policy codifies the authoritative gating logic governing milestone release recommendations across exactly eight prerequisite domain engines without financial side effects or money movement capabilities.

---

## 2. Policy Specification & Identity

| Property | Value | Notes |
| :--- | :--- | :--- |
| **Engine Type** | `RELEASE` | Target engine type |
| **Scope** | `PLATFORM` | System-wide deterministic baseline |
| **Organization ID** | `NULL` | Applicable across all tenant contexts |
| **Policy Key** | `FINX_RELEASE_DETERMINISTIC_BASE` | Stable policy identifier |
| **Version** | `1.0.0` | Immutable version |
| **Schema Version** | `1.0` | Policy JSON schema version |
| **Content Hash (SHA-256)** | `7b30065a46044cf0f41a5835541d48859d4c4a230fb81692aed70b84404366c7` | Deterministic digest over canonical JSON |
| **Target Subject** | `MILESTONE` | Evaluated per milestone |
| **Target Owner** | `BUYER_ORGANIZATION` | Owner of the funded escrow milestone |

---

## 3. Canonical Policy Structure & Rules

The policy JSON definitions enforce:
1. **Required Component Engines (Exactly 8):**
   - `RISK`
   - `DOCUMENT`
   - `MILESTONE_RULES`
   - `APPROVAL`
   - `TRUST`
   - `FRAUD`
   - `DISPUTE`
   - `RECONCILIATION`

2. **Strict Evaluation Precedence:**
   $$\text{BLOCK} \succ \text{REVIEW} \succ \text{ALLOW}$$

3. **Evaluation Rules:**
   - `R01_ANY_BLOCK`: If ANY required component status is `BLOCK`, overall RELEASE is `BLOCK`.
   - `R02_NO_BLOCK_AND_ANY_REVIEW`: If NO component is `BLOCK` and ANY component is `REVIEW`, overall RELEASE is `REVIEW`.
   - `R03_ALL_EIGHT_ALLOW`: If ALL 8 required components are `ALLOW` on fresh evidence, overall RELEASE is `ALLOW` (Reason: `RELEASE_ALL_GATES_ALLOW`).
   - `R04_MISSING_COMPONENT`: Any missing component engine decision evaluates to `REVIEW` (Reason: `RELEASE_COMPONENT_MISSING`).
   - `R05_STALE_COMPONENT`: Any stale component engine decision evaluates to `REVIEW` (Reason: `RELEASE_COMPONENT_STALE`).
   - `R06_COMPONENT_ERROR`: Any engine adapter evaluation failure evaluates to `REVIEW` (Reason: `RELEASE_COMPONENT_ERROR`).

4. **Execution Boundaries:**
   - `consumeNumericScores: false` (Zero score weighting or averaging).
   - `bankExecution: false` (Pure recommendation engine).
   - `financialMutation: false` (Zero side-effects on ledger, escrow, payments, statements).

---

## 4. Bootstrap & Persistence Mechanics

- **Registrar Class:** `com.finx.engine.policy.release.ReleasePolicyRegistrar` (`@Order(25)`).
- **Execution Lifecycle:** Runs upon Spring Boot startup (`ApplicationReadyEvent`), idempotently checking repository existence before persisting.
- **Conflict Prevention:** If a policy record exists with the same `(engineType, policyKey, version, organizationId=null)` but different content hash, bootstrap halts with `IllegalStateException` (fail-closed integrity).
- **Database Schema:** Uses existing `engine_policy_versions` table. **Zero new migrations (V28 remains highest).**

---

## 5. Test Suite Verification

| Test Class | Test Count | Result |
| :--- | :--- | :--- |
| `ReleasePolicyDefinitionTest` | 3 | **PASS** |
| `ReleasePolicyRegistrationTest` | 3 | **PASS** |
| `ReleasePolicyBootstrapSpringTest` | 1 | **PASS** |
| **Total Phase 4 Tests** | **7** | **PASS (100%)** |

All tests pass deterministically in Spring context and standalone unit harnesses.

---

## 6. Safety Verification Summary

| Gate | Status |
| :--- | :--- |
| **Bank Calls** | **0** |
| **Financial Side Effects** | **0** |
| **Ledger / Escrow Writes** | **0** |
| **Production Payout** | **DISABLED** |
| **Ready for Phase 5 (ReleaseDecisionEngine)** | **YES** |
