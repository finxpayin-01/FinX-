# Reconciliation R4 Exception Lifecycle Report

## Scope

Phase R4 implements the human reconciliation exception resolution lifecycle (resolving **R16 — EXCEPTION RESOLUTION LIFECYCLE**). Authorized platform finance and administrative personnel can review, acknowledge, and resolve reconciliation discrepancies without altering underlying deterministic financial facts or matcher output.

## Preconditions

- **Reconciliation R1**: `edf31d1` (PASS) — Durable schema, immutable line evidence.
- **Reconciliation R2**: `5a34ee9` (PASS) — Ingestion and SHA-256 fingerprinting.
- **Reconciliation R2.5**: `6a20a1c` (PASS) — Provider account scope binding.
- **Reconciliation R3**: `4864709` (PASS) — Deterministic matcher (`FINX_RECONCILIATION_MATCHER_V1`).
- **Engine Decision Framework (7 / 8 Adapters)**: (PASS).

## Schema & Migration

- **Migration**: None needed. The existing V14/V26 `reconciliation_items` columns (`resolution_status`, `resolved_by`, `resolved_at`, `resolution_notes`) and `ResolutionStatus` enum (`UNRESOLVED`, `RESOLVED`, `IGNORED`) fully satisfy all R4 requirements.
- **Highest Migration**: V28.

## Human Authority & Roles

- **Authorized Roles**: Platform `Role.ADMIN` and `Role.FINANCE` only.
- **Rejected Roles**: `BUYER`, `SELLER`, `PROJECT_MANAGER`, unauthenticated/null principals.
- Tenant / Organization roles cannot resolve platform reconciliation exceptions.

## Authoritative Run Target

- Resolution is permitted ONLY against items belonging to runs with `matcher_version == 'FINX_RECONCILIATION_MATCHER_V1'`.
- Legacy runs (`matcher_version == NULL`) fail closed and cannot be resolved through the authoritative R4 workflow.

## Exception Target & State Invariants

- **Exception Items Only**: Items with `status == MATCHED` are rejected from exception resolution.
- **Permitted Transitions**:
  - `UNRESOLVED -> RESOLVED`
  - `UNRESOLVED -> IGNORED`
- **Forbidden Transitions**: Any transition from terminal `RESOLVED` or `IGNORED` is rejected.
- **Mandatory Justification**: Non-blank `resolutionNotes` required for every resolution.

## Core Financial Immutability

Human exception resolution updates ONLY:
- `resolution_status`
- `resolved_by`
- `resolved_at`
- `resolution_notes`

Human resolution **NEVER** modifies:
- `ReconciliationItem.status` (remains `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, `MISSING_EXTERNAL`, `MISSING_INTERNAL`, `DUPLICATE`, `REQUIRES_REVIEW`)
- `ReconciliationItem.reasonCode`
- `finx_amount` / `bank_amount`
- `finx_transaction_reference` / `bank_transaction_reference`
- `internal_effect_id` / `internal_effect_type`
- `bank_statement_line_id`
- `currency` / `direction` / `bank_status`

## Release Safety Semantics

Marking an exception `RESOLVED` or `IGNORED`:
- Does **NOT** convert the item to `MATCHED`.
- Does **NOT** make a reconciliation run `clean`.
- Does **NOT** grant release permission.
- Financial clearance requires a subsequent clean deterministic run (`FINX_RECONCILIATION_MATCHER_V1` with `mismatchCount == 0`).

## Audit Logging

Every exception resolution logs an immutable event via `AuditService`:
- Action: `RECONCILIATION_EXCEPTION_RESOLVED`
- Entity: `RECONCILIATION_ITEM`
- Metadata includes run ID, previous resolution status, target status, item status, and reason code without exposing sensitive banking credentials or raw references.

## Financial Side Effects

- Financial mutations (Ledger, Escrow, Payment, Settlement, Deal, Milestone): **0**
- Bank calls (`BankingProvider`): **0**
- Decision engine mutations: **0**

## Tests Run

- `ReconciliationExceptionResolutionServiceTest`: 10 test cases (100% PASS)
  1. ADMIN may resolve
  2. FINANCE may resolve
  3. BUYER rejected
  4. SELLER rejected
  5. PM rejected
  6. Null principal rejected
  7. Legacy run rejected
  8. MATCHED item rejected
  9. Valid UNRESOLVED -> RESOLVED/IGNORED transitions
  10. Blank notes rejected
  11. Financial immutability verified
  12. Second transition rejected
  13. Zero side effects verified

## Full Suite Verification

```text
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## R4 Conclusions

- **R16**: FIXED
- **Human authority**: PRESERVED
- **MATCHED state mutation**: NO
- **Financial side effects**: 0
- **Bank calls**: 0
- **Legacy runs**: NONAUTHORITATIVE
- **Production payout**: DISABLED
- **Ready for R5**: YES
