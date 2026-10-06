# Trust + Dispute Decision Adapters Report

## Scope

Implemented:
- `TRUST` (read-only counterparty verification decision adapter)
- `DISPUTE` (read-only dispute state decision adapter)

Read-only only.

Not implemented:
- `FRAUD` (Fraud decision adapter deferred; lacks independent scoring and fraud policy)
- `RECONCILIATION` (Reconciliation decision adapter deferred; MATCHED is non-authoritative)
- `RELEASE` (Release decision engine deferred; composite multi-domain authorization not implemented)

## Preconditions

- B01–B13: PASS
- Restricted framework boundary: PASS (`b605858`)
- Common EngineDecision framework: PASS (`c8137f8`)
- Existing domain adapters:
  - `RISK`: IMPLEMENTED (`60ad399`)
  - `DOCUMENT`: IMPLEMENTED (`60ad399`)
  - `MILESTONE_RULES`: IMPLEMENTED (`2144571`)
  - `APPROVAL`: IMPLEMENTED READ-ONLY (`2144571`)
- Remaining Engine Readiness Audit: PASS (`c7e97cd`)

## TrustDecisionAdapter

- Class: `com.finx.engine.adapter.TrustDecisionAdapter`
- Interface: Implements `com.finx.engine.adapter.ReadOnlyDecisionAdapter`
- EngineType: `EngineType.TRUST`
- Transaction Isolation: `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`

## Trust Evaluation Owner

- Organization: `context.organizationId()` (evaluated organization UUID)
- User UUIDs are never treated as organization UUIDs.

## Trust Subject

- Subject Type: `DecisionSubjectType.ORGANIZATION` (`"ORGANIZATION"`)
- Subject ID: Evaluated organization UUID (`organization.getId()`)

## Authoritative Trust Sources

1. `Organization` / `OrganizationRepository`
2. `KybProfile` / `KybProfileRepository`
3. `AuthorizedRepresentative` / `AuthorizedRepresentativeRepository`
4. `OrganizationMember` / `OrganizationMemberRepository`
5. `KycProfile` / `KycProfileRepository`
6. `OrganizationBankAccount` / `OrganizationBankAccountRepository`
7. `VerificationEligibilityService` (B09 beneficiary evaluation)

## KYB Semantics

- Evaluates canonical KYB verification status across:
  - Business PAN: required for all business entities (`panStatus == VERIFIED`).
  - GSTIN: required for `PRIVATE_LIMITED`, `PUBLIC_LIMITED`, `LLP` (`gstStatus == VERIFIED`); optional for `PROPRIETORSHIP` if not provided.
  - Corporate Registration (CIN / LLPIN / Udyam): required for registered corporations/LLPs (`registrationStatus == VERIFIED`); optional MSME/Udyam for proprietorships if not provided.
- Hard failure (`FAILED`, `REJECTED`) maps to `DecisionStatus.BLOCK` with `TRUST_KYB_FAILED`.
- Incomplete / pending / in-progress verification maps to `DecisionStatus.REVIEW` with `TRUST_KYB_PENDING`.

## KYC / Representative Semantics

- Inspects all candidate representatives for the organization (`AuthorizedRepresentative`).
- For each representative with verified authority (`authorityStatus == VERIFIED`), individual KYC (`KycProfile`) is evaluated.
- Representative individual KYC must be `status == VERIFIED` and unexpired.
- If KYC is missing, pending, or unverified: maps to `DecisionStatus.REVIEW` with `TRUST_SIGNATORY_UNVERIFIED`.

## Signing Authority

- Evaluates `AuthorizedRepresentative.authorityStatus == AuthorityStatus.VERIFIED`.
- Valid authority requires a matching active organization member (`OrganizationMember`).

## Membership Semantics

- Signatory must have an active organization membership record (`OrganizationMember.status == MemberStatus.ACTIVE`).
- Member role must be an authorized signing role: `OrgRole.OWNER`, `OrgRole.AUTHORIZED_SIGNATORY`, or `OrgRole.ADMIN`.
- Inactive, suspended, or missing membership maps to `DecisionStatus.REVIEW` with `TRUST_MEMBERSHIP_INACTIVE`.

## Beneficiary Verification

- Reuses canonical B09 semantics via `VerificationEligibilityService.evaluateBeneficiaryEligibility(organizationId)`.
- Valid eligible beneficiary must have:
  - `verificationStatus == BankVerificationStatus.VERIFIED`
  - Encrypted executable account number present (`encryptedAccountNumber != null && !isBlank()`)
  - `verifiedAt != null`
  - `nameMatchStatus != "MISMATCH"`
  - Belongs strictly to evaluated organization.
- Masked-only accounts without ciphertext cannot produce `ALLOW` (fails B09 eligibility -> `TRUST_BENEFICIARY_UNVERIFIED`).

## Name Match

- If any bank account evidence has explicit `nameMatchStatus == "MISMATCH"`:
  - Maps to `DecisionStatus.BLOCK`
  - Reason code: `TRUST_BENEFICIARY_MISMATCH`.

## Beneficiary Ambiguity

- If multiple eligible verified bank accounts exist without unambiguous selection:
  - Maps to `DecisionStatus.REVIEW`
  - Reason code: `TRUST_BENEFICIARY_AMBIGUOUS`.
- The adapter never arbitrarily selects the first account.

## Expiry / Freshness

- Evaluates `expiresAt` timestamps on `KybProfile` and `KycProfile`.
- If `expiresAt != null && !expiresAt.isAfter(context.evaluationTime())`:
  - Maps to `DecisionStatus.REVIEW`
  - Reason code: `TRUST_VERIFICATION_EXPIRED`.
- Null expiry behavior: If `expiresAt` is null, it represents no configured expiry under existing native schema semantics and is preserved without synthetic expiration periods.

## D06 Metrics

- **EXCLUDED**: `OrganizationService.getVerifiedBusinessProfile` and all broken D06 metric paths (global milestone counts, global dispute counts, cross-tenant performance percentages) are strictly excluded from `TrustDecisionAdapter`.
- Enforced by architecture test `ReadOnlyDecisionAdapterArchitectureTest.trustAdapterMustNotDependOnD06OrganizationService`.

## Numeric Trust Score

- **NONE**: `EngineDecision.score = null`.
- No synthetic 0–100 scores, credit scores, or weighted index formulas were invented.

## Missing Transaction History

- New companies with zero historical transaction data are treated as neutral/informational (`INSUFFICIENT_TRANSACTION_HISTORY`).
- Zero transaction history does NOT produce `BLOCK`.

## Trust Decision Mapping

- `DecisionStatus.BLOCK`:
  - `TRUST_ORGANIZATION_NOT_FOUND`
  - `TRUST_KYB_FAILED`
  - `TRUST_BENEFICIARY_MISMATCH`
- `DecisionStatus.REVIEW`:
  - `TRUST_KYB_PENDING`
  - `TRUST_SIGNATORY_UNVERIFIED`
  - `TRUST_MEMBERSHIP_INACTIVE`
  - `TRUST_VERIFICATION_EXPIRED`
  - `TRUST_BENEFICIARY_UNVERIFIED`
  - `TRUST_BENEFICIARY_AMBIGUOUS`
- `DecisionStatus.ALLOW`:
  - `TRUST_COUNTERPARTY_VERIFIED` (all verification requirements satisfied; does NOT authorize payout).

## Trust Evidence References

- `ORGANIZATION`: `org.getId()`, `org.getUpdatedAt()`
- `KYB_PROFILE`: `kyb.getId()`, `kyb.getVerifiedAt()`
- `AUTHORIZED_REPRESENTATIVE`: `rep.getId()`, `rep.getVerifiedAt()`
- `ORGANIZATION_MEMBER`: `member.getId()`, `member.getJoinedAt()`
- `KYC_PROFILE`: `kyc.getId()`, `kyc.getVerifiedAt()`
- `ORGANIZATION_BANK_ACCOUNT`: `acc.getId()`, `acc.getVerifiedAt()`
- Sensitive secrets (PAN, GSTIN plaintext, decrypted account numbers) are strictly excluded from JSON explanations and evidence references.

## Trust Fingerprint

- Deterministic SHA-256 hash computed over ordered scalar inputs:
  - Organization ID, legal name, entity type, verification status, updatedAt
  - KYB ID, status, sub-statuses, verifiedAt, expiresAt
  - Representative IDs, relationship types, authority statuses, verifiedAt
  - Member IDs, roles, statuses, joinedAt
  - KYC IDs, statuses, verifiedAt, expiresAt
  - Bank account IDs, verification statuses, nameMatchStatuses, verifiedAt, encryption flags.

---

## DisputeDecisionAdapter

- Class: `com.finx.engine.adapter.DisputeDecisionAdapter`
- Interface: Implements `com.finx.engine.adapter.ReadOnlyDecisionAdapter`
- EngineType: `EngineType.DISPUTE`
- Transaction Isolation: `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`

## Dispute Evaluation Owner

- Buyer Organization: `deal.getBuyerOrganizationId()` (or participant organization)

## Deal / Milestone Subject

- Deal evaluation: `subjectType = "DEAL"`, `subjectId = deal.getId()`
- Milestone evaluation: `subjectType = "MILESTONE"`, `subjectId = milestone.getId()`

## Open Dispute Mapping

- Any relevant dispute with `status == DisputeStatus.OPEN` or `status == DisputeStatus.UNDER_REVIEW`:
  - Deal-level dispute (`milestoneId == null`) -> `DecisionStatus.BLOCK`, reason `DISPUTE_ACTIVE_ON_DEAL`
  - Milestone-level dispute (`milestoneId == target`) -> `DecisionStatus.BLOCK`, reason `DISPUTE_ACTIVE_ON_MILESTONE`
  - Both present -> `DecisionStatus.BLOCK`, reasons `[DISPUTE_ACTIVE_ON_DEAL, DISPUTE_ACTIVE_ON_MILESTONE]`

## Resolved Dispute Mapping

- Any relevant historical dispute with `status == DisputeStatus.RESOLVED`:
  - Maps to `DecisionStatus.REVIEW`
  - Reason code: `DISPUTE_RECENTLY_RESOLVED`
  - Never automatically produces `ALLOW` for payout release.

## Rejected Dispute Mapping

- `DisputeStatus.REJECTED`: Represents an invalid/rejected complaint closed by administrator.
- Neutral historical input; does not trigger `BLOCK` or `REVIEW` on its own.

## Human Authority Boundary

- AI and decision adapters NEVER:
  - Resolve disputes
  - Reject disputes
  - Modify resolution notes
  - Modify `resolvedBy` or `resolvedAt`
  - Mutate deal status
  - Release funds
- Administrative dispute resolution via `DisputeService` remains strictly human/admin-controlled.

## Dispute Evidence Limitations

- No separate `dispute_evidence` table exists.
- Evidence references point exclusively to durable persisted `Dispute`, `Deal`, and `Milestone` rows. No synthetic attachment evidence hashes or AI confidence metrics are claimed.

## Dispute Evidence References

- `DEAL`: `deal.getId()`, `deal.getUpdatedAt()`
- `MILESTONE`: `milestone.getId()`, `milestone.getUpdatedAt()` (if milestone-scoped)
- `DISPUTE`: `dispute.getId()`, `dispute.getCreatedAt()`

## Dispute Fingerprint

- Deterministic SHA-256 hash computed over ordered scalar inputs:
  - Deal ID, status, buyerOrganizationId, sellerOrganizationId, updatedAt
  - Milestone ID, status, amount, updatedAt
  - Dispute rows (sorted by ID and createdAt): id, dealId, milestoneId, raisedBy, status, createdAt, resolvedAt, resolvedBy.

## Tenant Isolation

- Tenant boundary verified via `DealAccessService.requireDealAccess(deal, caller)` when caller is provided.
- Direct organization context checks require caller organization to match `deal.getBuyerOrganizationId()` or `deal.getSellerOrganizationId()`. Cross-tenant evaluations fail with `UnauthorizedException`.

## Side Effects

- Bank calls (`BankingProvider.initiateSettlement`): 0
- Bank feed calls (`BankingProvider.fetchTransactions`): 0
- Ledger writes (`JournalEntry`, `EscrowLedger`): 0
- Escrow writes (`EscrowAccount` mutations): 0
- Settlement writes (`SettlementInstruction`, `PaymentInstruction`): 0
- Dispute writes (`Dispute` mutations): 0

## RELEASE Decisions Created

- `0` (Zero RELEASE decisions generated or persisted)

## Database Migration

- **NONE**: No Flyway migrations added (NO V26). Existing V3, V11/V16, and V25 schemas used unchanged.

## Files Modified

- `backend/src/main/java/com/finx/engine/adapter/DecisionEvidence.java`
- `backend/src/test/java/com/finx/engine/ReadOnlyDecisionAdapterArchitectureTest.java`

## Files Created

- `backend/src/main/java/com/finx/engine/adapter/TrustDecisionAdapter.java`
- `backend/src/main/java/com/finx/engine/adapter/DisputeDecisionAdapter.java`
- `backend/src/test/java/com/finx/engine/TrustDecisionAdapterTest.java`
- `backend/src/test/java/com/finx/engine/DisputeDecisionAdapterTest.java`
- `backend/src/test/java/com/finx/engine/TrustDisputeDecisionPersistenceTest.java`
- `TRUST_DISPUTE_DECISION_ADAPTERS_REPORT.md`

## Tests Added

- `TrustDecisionAdapterTest`: 20 unit tests covering all verification branches, negative cases, expiry, and fingerprinting
- `DisputeDecisionAdapterTest`: 14 unit tests covering deal/milestone subjects, open/resolved/rejected states, tenant isolation, and fingerprinting
- `TrustDisputeDecisionPersistenceTest`: 1 Spring Boot integration test covering idempotent persistence, durable DB references, and zero financial side effects
- Architecture test expansions in `ReadOnlyDecisionAdapterArchitectureTest`: 2 new architecture assertions verifying contract adherence and D06 metric exclusion.

## Tests Run

- Focused adapter tests: 40 tests (0 failures, 0 errors)
- Domain, framework, and security regressions: 432 tests (0 failures, 0 errors, 23 skipped)
- Full Maven test suite: 810 tests (0 failures, 0 errors, 32 skipped)

## Test Results

```
[INFO] Results:
[INFO] Tests run: 810, Failures: 0, Errors: 0, Skipped: 32
[INFO] BUILD SUCCESS
```

## Engine Adapter Coverage

- `RISK`: IMPLEMENTED
- `DOCUMENT`: IMPLEMENTED
- `MILESTONE_RULES`: IMPLEMENTED
- `APPROVAL`: IMPLEMENTED (READ-ONLY)
- `TRUST`: IMPLEMENTED (READ-ONLY)
- `DISPUTE`: IMPLEMENTED (READ-ONLY)
- `FRAUD`: NOT READY / NOT IMPLEMENTED
- `RECONCILIATION`: NOT READY / NOT IMPLEMENTED
- `RELEASE`: NOT IMPLEMENTED

Total: 6 / 8 component adapters implemented.

## Production Payout

- **DISABLED** (`BankingProvider.initiateSettlement` unreachable).

## Ready For Next Phase

- **YES**

## Recommended Next Phase

- **FRAUD POLICY + RECONCILIATION REMEDIATION PLANNING/AUDIT**
