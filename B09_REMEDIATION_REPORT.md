# B09 Remediation Report

## Blocker

B09 — Beneficiary and verification authority can be misbound

## Root Cause

Prior to B09 remediation, multiple critical security vulnerabilities existed in the beneficiary identity, bank verification, and authority validation pipelines:
1. **Two Unequal Verification Paths**: `OrganizationService.verifyBankAccount` and `VerificationService.verifyBankAccount` existed as dual paths. `OrganizationService` accepted masked-only account strings (`********9012`), lacked PII encryption handling, did not perform canonical name matching or event persistence, and directly invoked a legacy bank provider call.
2. **Masked-Data Weakness**: Bank accounts stored and verified with only masked strings (`accountNumber = ********9012` and `encryptedAccountNumber = null`) could achieve `BankVerificationStatus.VERIFIED`, creating an illusion of trusted financial verification on top of incomplete display metadata.
3. **Active Authority Gap**: While basic organization ownership checks existed, caller membership status was either loosely validated or bypassed. Members in `INVITED`, `SUSPENDED`, or inactive states could potentially add or verify bank accounts or execute legal agreements.
4. **User-ID-as-Org Fallback**: Financial pipelines (such as `InvoiceService` and `SettlementAuthorizationService`) permitted fallback from `sellerOrganizationId` to `sellerId` (User UUID) or `deal.getSellerId()`, confusing corporate legal identities with personal user accounts.
5. **Arbitrary Beneficiary Selection**: When resolving beneficiary bank accounts, pipelines picked the first verified account (`findFirst()`) without detecting multiple verified accounts. A newly added or reordered account could silently retarget settlements without governance consent or authorization.
6. **Missing Sandbox / Mock Provenance**: Sandbox or mock provider verifications were not explicitly identified during eligibility evaluations, allowing simulated verifications to be treated identically to production financial evidence.

## Before

* **Two Unequal Verification Paths**: Callers could bypass canonical `VerificationService` (which verifies identity, decrypts PII, scores name matching, and logs structured audit records) by calling `OrganizationService.verifyBankAccount` with masked account data.
* **Masked-Only Evidence**: Verification could proceed with only `********9012` as account details, persisting `VERIFIED` status without full encrypted account identity.
* **Active Authority Gap**: Agreement signing (`AgreementService.signAgreement`) checked that the user ID matched `buyerId` or `sellerId` on the deal, but failed to verify active membership (`MemberStatus.ACTIVE`) or signing role authority (`OWNER`, `ADMIN`, `AUTHORIZED_SIGNATORY`) within the deal's bound organization.
* **User-ID-as-Org Fallback**: In `InvoiceService.generateInvoice` and related lookups, `deal.getSellerOrganizationId() != null ? deal.getSellerOrganizationId() : deal.getSellerId()` allowed user UUIDs to masquerade as organization IDs.
* **Arbitrary Beneficiary Selection**: `findFirst()` was used on collections of verified accounts, meaning any change in database ordering or addition of a secondary bank account could silently alter payout destination.
* **Ambiguous Beneficiary Handling**: Organizations with multiple verified bank accounts were arbitrarily serviced instead of failing closed until an explicit beneficiary selection was provided.
* **Sandbox / Production Evidence Ambiguity**: No provenance check existed to determine whether verified accounts were validated in a mock/sandbox environment versus production.

## After

* **Canonical Verification Path**: `VerificationService.verifyBankAccount` is established as the single canonical verification service. `OrganizationService.verifyBankAccount` no longer maintains an independent, weaker mutation path; it now delegates directly to `VerificationService` with active organization authority enforcement.
* **Active Authority Enforcement**: Adding or verifying an organization's bank account requires active organization membership (`MemberStatus.ACTIVE`) with administrative or ownership roles (`OWNER`, `ADMIN`), reusing B07 containment logic. Suspended, invited, inactive, or non-member users are strictly rejected with `UnauthorizedException`.
* **Organization / Account Binding**: Before any verification or beneficiary eligibility check, the account is retrieved from the database and validated against the requesting organization ID (`account.getOrganizationId().equals(organizationId)`). Cross-tenant account guessing and mismatches are rejected immediately with `BadRequestException`.
* **Masked-Only Evidence Closed**: Adding a bank account in `OrganizationService.addBankAccount` encrypts the raw account number using `PiiEncryptionService`. Verification strictly requires non-null, non-blank `encryptedAccountNumber`. Any attempt to verify an account having only masked metadata is rejected with `BadRequestException`.
* **User-ID-as-Org Fallback Eliminated**: Direct mapping from `deal.getSellerOrganizationId()` and `deal.getBuyerOrganizationId()` is strictly enforced. The user-ID fallback is removed. If organization IDs are missing or invalid, evaluation fails closed.
* **Explicit Beneficiary Selection & Ambiguity Containment**: `VerificationEligibilityService.evaluateBeneficiaryEligibility(organizationId)` and `requireSingleEligibleBeneficiary(organizationId)` provide a read-only boundary:
  - 0 eligible verified accounts -> Unavailable / Fail closed (`eligible = false`).
  - Exactly 1 eligible verified account -> Deterministic single eligible beneficiary.
  - > 1 eligible verified accounts -> Ambiguous / Fail closed (`eligible = false`, "Multiple verified bank accounts found; explicit beneficiary selection required").
* **Account Switch Safety**: Repository reordering or secondary account creation cannot silently redirect payouts because multiple verified accounts automatically transition the organization into an ambiguous fail-closed state.
* **Agreement Signing Authority**: `AgreementService.signAgreement` enforces that when deals are bound to organizations, the signing user must have `MemberStatus.ACTIVE` with role `OWNER`, `ADMIN`, or `AUTHORIZED_SIGNATORY` in that organization. Suspended, invited, or unauthorized users cannot sign agreements.
* **Sandbox / Mock Provenance**: `VerificationEligibilityService.evaluateBeneficiaryEligibility` identifies and records whether verification was performed via mock/sandbox providers (`isSandbox = true`), ensuring downstream systems can distinguish sandbox evidence from production bank verifications.
* **Payout Execution Disabled**: `SettlementAuthorizationService.authorizeAndInitiateSettlement` remains unconditionally fail-closed under B04 (`AccessDeniedException`). Zero calls to `BankingProvider.initiateSettlement` are permitted or made.

## Canonical Verification Path

Authoritative Service:
- `com.finx.verification.service.VerificationService.verifyBankAccount(UUID organizationId, UUID accountId, UserPrincipal currentUser)`
- Performs active membership check (`MemberStatus.ACTIVE`), bank account organization binding check, PII decryption, verification provider call (`BankingVerificationProvider`), name matching validation, and immutable audit/event recording.

## Weak Verification Path

- `OrganizationService.verifyBankAccount(UUID organizationId, UUID accountId, UserPrincipal currentUser)`
- **DELEGATED**: Re-implemented to delegate directly to canonical `VerificationService.verifyBankAccount(...)`. It no longer executes independent provider calls or accepts masked-only data.

## Organization / Account Binding

- Evaluated strictly in both `VerificationService` and `VerificationEligibilityService`.
- Account is loaded from `OrganizationBankAccountRepository` and checked:
  `if (!account.getOrganizationId().equals(organizationId)) { throw new BadRequestException("Bank account does not belong to the specified organization"); }`
- Cross-tenant account UUID guessing is completely blocked.

## Active Membership / Authority

- Bank account creation (`addBankAccount`) and verification (`verifyBankAccount`) require the caller to possess an active organization membership (`MemberStatus.ACTIVE`) and role `OWNER` or `ADMIN` (or platform `ADMIN` where authorized).
- `INVITED` members: Rejected with `UnauthorizedException`.
- `SUSPENDED` members: Rejected with `UnauthorizedException`.
- Unrelated users / non-members: Rejected with `UnauthorizedException`.

## Masked-Only Evidence

- Masked account numbers (`********1234`) are recognized solely as display metadata.
- When creating a bank account, `OrganizationService` encrypts the plain-text account number via `PiiEncryptionService` and stores it in `encryptedAccountNumber`.
- Canonical verification checks:
  `if (account.getEncryptedAccountNumber() == null || account.getEncryptedAccountNumber().isBlank()) { throw new BadRequestException("Cannot verify bank account: full account details are missing (masked-only record)"); }`
- Masked-only accounts cannot achieve or maintain trusted eligibility.

## Verification Expiry / Revocation

- **Current Model Support**: The existing `organization_bank_accounts` schema possesses `verified_at` (`Instant`), `name_match_status` (`String`), and `verification_status` (`BankVerificationStatus`). It lacks explicit `expires_at` or `revoked_at` columns and does not contain `EXPIRED` or `REVOKED` enum constants.
- **Enforced Signals**: Eligibility evaluation requires:
  1. `verificationStatus == BankVerificationStatus.VERIFIED`
  2. `verifiedAt != null`
  3. `nameMatchStatus` is not `"MISMATCH"`
  4. `encryptedAccountNumber` is present and non-blank
- Accounts not meeting these signals are excluded from beneficiary eligibility.

## Sandbox / Mock Provenance

- Evaluated via `VerificationEligibilityService.evaluateBeneficiaryEligibility(organizationId)`.
- If the account provider or reference indicates `MOCK` or the active verification provider is marked sandbox (`provider.isSandbox() == true`), `BeneficiaryEligibilityResult.isSandbox()` is explicitly set to `true`.
- Prevents mock sandbox verifications from being silently conflated with production-grade verification.

## Beneficiary Selection

Implemented deterministically in `VerificationEligibilityService`:
- **0 eligible verified accounts**: Eligibility = `false`. Fails closed with reason `"No eligible verified bank accounts found for organization"`.
- **1 eligible verified account**: Eligibility = `true`. Deterministically returned as the sole eligible beneficiary.
- **> 1 eligible verified accounts**: Eligibility = `false`. Ambiguous / Fails closed with reason `"Multiple verified bank accounts found for organization (...); explicit beneficiary selection required"`.

## User ID vs Organization ID

- Removed all user-ID-as-org fallbacks.
- `deal.getSellerOrganizationId()` must represent an authentic `Organization` entity.
- If `sellerOrganizationId` is missing or resolves to a non-organization entity, beneficiary lookup fails closed.
- No auto-mapping or inferring organization IDs from user UUIDs.

## Agreement Signing Authority

- Updated `AgreementService.signAgreement(UUID dealId, UserPrincipal signer)`.
- When deal is bound to an organization (`buyerOrganizationId` or `sellerOrganizationId`), verifies that `signer` has `MemberStatus.ACTIVE` with role `OWNER`, `ADMIN`, or `AUTHORIZED_SIGNATORY`.
- Suspended or invited members cannot sign agreements.

## Account Switch Safety

- Silent retargeting is prevented:
  1. Adding a new verified account immediately causes candidate count to exceed 1, triggering the AMBIGUOUS fail-closed boundary.
  2. Invalidation of an existing verified account causes eligible count to become 0, failing closed.
  3. Settlement execution remains disabled by B04.

## Interaction With B01

B01 (Strict Webhook Replay and State Mutation Protection) remains completely intact. Payment webhooks enforce signature validation, replay checks, and strict state transitions. All B01 tests pass.

## Interaction With B02

B02 (Escrow and Banking Webhook Signature Verification) remains completely intact. Strict signature checks and replay defenses remain enforced. All B02 tests pass.

## Interaction With B03

B03 (Escrow Auto-Release and Premature Transition Prevention) remains completely intact. Escrow release cannot be triggered automatically or prematurely. All B03 tests pass.

## Interaction With B04

B04 (Unchecked Settlement Execution Fail-Closed) remains completely intact. `SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed unconditionally throwing `AccessDeniedException`. `BankingProvider.initiateSettlement` calls remain at zero. All B04 tests pass.

## Interaction With B12

B12 (Legacy Endpoint and Open Parameter Security) remains completely intact. Legacy paths are neutralized and reject open parameter hijacking. All B12 tests pass.

## Interaction With B07

B07 (Tenant and Organization Isolation) remains completely intact. Active membership verification and organization access control rules are reused and reinforced. All B07 tests pass.

## Interaction With B08

B08 (Role Escalation and Authority Partitioning) remains completely intact. Public registration cannot grant elevated platform or finance authority. All B08 tests pass.

## Files Modified

- [OrganizationBankAccountRepository.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/org/repository/OrganizationBankAccountRepository.java)
- [VerificationService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/verification/service/VerificationService.java)
- [OrganizationService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/org/service/OrganizationService.java)
- [VerificationEligibilityService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/verification/service/VerificationEligibilityService.java)
- [AgreementService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/agreement/service/AgreementService.java)
- [SettlementAuthorizationService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/paymentorchestration/service/SettlementAuthorizationService.java)
- [InvoiceService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/invoice/service/InvoiceService.java)

## Files Created

- [BeneficiaryVerificationContainmentTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/security/BeneficiaryVerificationContainmentTest.java)
- [B09_REMEDIATION_REPORT.md](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/B09_REMEDIATION_REPORT.md)

## Database Migration

NONE

## API Compatibility

Weak, ambiguous, or unbound bank verifications are now denied with appropriate HTTP 400 (`BadRequestException`) or HTTP 403/401 (`UnauthorizedException`) responses. Valid canonical verification workflows operate without disruption.

## Tests Added

`com.finx.security.BeneficiaryVerificationContainmentTest`:
1. `test01_correctOrg_ownAccount_allowedToVerify`: Normal verification path reachable with valid org and account.
2. `test02_correctOrg_otherOrgAccount_rejected`: Cross-tenant verification rejected with `BadRequestException`.
3. `test03_forgedOrgId_rejected`: Non-existent org ID rejected.
4. `test04_crossTenantAccountUuidGuessing_rejected`: Account belonging to Org B cannot be accessed via Org A.
5. `test05_activeAuthorizedOrgMember_allowedToVerify`: Active owner/admin can verify bank accounts.
6. `test06_invitedMember_deniedVerification`: Invited member verification rejected with `UnauthorizedException`.
7. `test07_suspendedMember_deniedVerification`: Suspended member verification rejected with `UnauthorizedException`.
8. `test08_unrelatedUser_deniedVerification`: Non-member verification rejected with `UnauthorizedException`.
9. `test09_suspendedMember_deniedAddBankAccount`: Suspended member adding bank account rejected.
10. `test10_maskedOnlyAccount_cannotBecomeTrustedBeneficiary`: Verification of account with only masked number rejected.
11. `test11_weakerLegacyVerificationPath_delegatesToCanonical`: `OrganizationService.verifyBankAccount` delegates to canonical verification.
12. `test12_canonicalVerificationPath_preservesProvenance`: Canonical path saves verification check record with timestamp and provider details.
13. `test13_mockSandboxVerification_notLabelledProductionTrusted`: Mock/sandbox verification explicitly flags `isSandbox = true`.
14. `test14_zeroEligibleAccounts_beneficiaryFailsClosed`: Zero eligible accounts fails closed.
15. `test15_exactlyOneEligibleAccount_deterministicIdentity`: Single eligible account returns deterministic eligibility.
16. `test16_multipleEligibleAccounts_ambiguousFailsClosed`: Multiple eligible verified accounts fails closed as ambiguous.
17. `test17_repositoryOrdering_cannotSilentlySelectAnotherAccount`: Repository ordering changes do not result in arbitrary beneficiary selection.
18. `test18_invalidNameMatchAccount_excludedFromEligibility`: Accounts with `nameMatchStatus = MISMATCH` excluded.
19. `test19_unverifiedAccount_excludedFromEligibility`: Accounts with `PENDING` status excluded.
20. `test20_sellerUserUuid_notAcceptedAsSellerOrgUuid`: User UUID rejected as organization UUID in beneficiary lookup.
21. `test21_missingSellerOrgId_doesNotFallbackToSellerId`: Null org ID does not fall back to user ID; fails closed.
22. `test22_legacyAmbiguousRow_notAutoMapped`: Legacy ambiguous records are not auto-mapped.
23. `test23_currentTrustedBeneficiaryLookup_usesActualOrgIdentity`: Trusted lookup requires authentic organization record.
24. `test24_suspendedSigner_cannotSignAgreement`: Suspended member signing rejected with `UnauthorizedException`.
25. `test25_invitedSigner_cannotSignAgreement`: Invited member signing rejected with `UnauthorizedException`.
26. `test26_signerWithoutSigningRole_cannotSignAgreement`: Member without signing role rejected with `UnauthorizedException`.
27. `test27_activeAuthorizedSigner_canSignAgreement`: Active owner/signatory successfully signs agreement.
28. `test28_accountSwitchSafety_addingSecondAccountCreatesAmbiguityFailsClosed`: Adding a second account triggers ambiguity fail-closed state.
29. `test29_accountSwitchSafety_accountBecomesInvalid_noSilentSwitch`: Invalidation of the only account causes eligibility to fail closed.
30. `test30_changingVerificationState_doesNotCreateExecutionSideEffect`: State transitions do not trigger banking execution.
31. `test31_bankingProvider_initiateSettlementCallsRemainZero`: Banking provider initiate settlement calls strictly remain zero.

## Tests Run

- `BeneficiaryVerificationContainmentTest` (31 tests, 0 failures, 0 errors)
- Prior blocker regression suite:
  - `PaymentConfirmationContainmentTest` (B01)
  - `BankingWebhookControllerTest` (B02)
  - `EscrowReleaseContainmentTest` (B03)
  - `SettlementExecutionContainmentTest` (B04)
  - `LegacyBoundaryContainmentTest` (B12)
  - `TenantIsolationContainmentTest` (B07)
  - `PlatformRoleProvisioningContainmentTest` (B08)
  - `SettlementAuthorizationServiceTest`
  - `VerificationServiceIntegrationTest`
  - Total: 165 tests, 0 failures, 0 errors
- Domain tests:
  - `VerificationEligibilityServiceTest` (4 tests, 0 failures, 0 errors)
- Full backend suite:
  - `.\mvnw.cmd test`

## Test Results

Exact:
- Tests run: 504
- Failures: 0
- Errors: 0
- Skipped: 1
- **BUILD SUCCESS**

## Known Limitations

- Payout remains disabled by B04 (`SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed unconditionally).
- Persistent beneficiary snapshot/fingerprint table is still required before payout restoration can be considered.
- `organization_bank_accounts` schema does not currently maintain persistent expiry timestamps or revocation records; eligibility is enforced using available state signals (`verifiedAt`, `nameMatchStatus`, `verificationStatus`, `encryptedAccountNumber`).
- B10 will handle stale approval applicability.
- B11 will handle locking and race condition containment.
- B05/B06 will subsequently address financial effect identity and accounting lineage.
- Real production bank verification provider integration remains deferred.

## B09 Status

PASS

## Ready for B10

YES
