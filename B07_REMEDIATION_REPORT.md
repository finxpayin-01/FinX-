# B07 Remediation Report

## Blocker

B07 — Tenant binding, membership and IDOR checks are incomplete

## Root Cause

1. `DealService.createDeal(...)` accepted `buyerOrganizationId` and `sellerOrganizationId` directly from the request payload without validating caller membership in the buyer organization, verifying existence of the seller organization as a distinct registered entity, or preventing self-dealing between identical organizations.
2. `OrganizationService.validateOrgMembership(...)` and `validateOrgRole(...)` only checked that a membership record was present, ignoring `MemberStatus` entirely and allowing non-ACTIVE states (`INVITED`, `SUSPENDED`) to exercise organizational authority.
3. Financial and dispute read endpoints (`PaymentOrchestrationService.getInstructionsForDeal`, `SettlementAuthorizationService.getSettlementsForDeal`, `DisputeController.getDisputesForDeal`, `DisputeService.getDisputesForDeal`) failed to authorize the caller against the actual stored `Deal` entity, enabling cross-tenant IDOR access via deal UUID guesses. In particular, `DisputeController.getDisputesForDeal` received `@AuthenticationPrincipal UserPrincipal actor` but discarded it when calling `DisputeService`.
4. Global automated reconciliation endpoints (`triggerReconciliation`, `getRuns`, `getItemsForRun`) in `ReconciliationController` and `ReconciliationService` had no platform role enforcement, exposing multi-tenant internal and bank transaction feeds to any authenticated user.
5. `OrganizationService.getVerifiedBusinessProfile` lacked caller authorization, exposing financial and transactional evidence of an organization to unrelated callers.
6. `PaymentOrchestrationService.createPaymentInstruction` populated `null, null` for `buyerOrganizationId` and `sellerOrganizationId` on new `PaymentInstruction` records rather than propagating the validated organization bindings from the authoritative `Deal`.

## Before

* Any authenticated user could create a deal asserting ownership of any arbitrary `buyerOrganizationId` without having active membership in that organization.
* Passing a valid User UUID as an organization UUID succeeded in persisting unbound or forged relationships because no organization existence check was performed.
* Suspended or invited members retained full organizational authorization.
* Any user could query payment instructions, historical settlements, or dispute items for any deal simply by providing the `dealId`.
* `DisputeController` discarded the actor principal on list reads.
* Any authenticated user (including `BUYER`, `SELLER`, `PROJECT_MANAGER`) could trigger global transaction reconciliation runs and inspect internal ledger vs bank feed discrepancies.
* New payment instructions stored `null` for both `buyer_organization_id` and `seller_organization_id`.
* Organization business profiles containing completed deal and dispute metrics were open to arbitrary callers.

## After

* `DealAccessService` provides a centralized, fail-closed authorization boundary for all deal access:
  * Platform `Role.ADMIN` and `Role.FINANCE` are granted privileged platform access.
  * Explicitly assigned Project Managers (`deal.getProjectManagerId() == user.getId()`) are permitted access to their assigned deal; unassigned Project Managers are denied.
  * For deals bound to a buyer organization (`deal.getBuyerOrganizationId() != null`), caller must have an active membership (`MemberStatus.ACTIVE`) in that organization; legacy unbound deals permit direct user equality (`deal.getBuyerId() == user.getId()`).
  * For deals bound to a seller organization (`deal.getSellerOrganizationId() != null`), caller must have an active membership (`MemberStatus.ACTIVE`) in that organization; legacy unbound deals permit direct user equality (`deal.getSellerId() == user.getId()`).
  * Unrelated callers, inactive members, and cross-tenant users are denied with `UnauthorizedException`.
* `OrganizationService.validateOrgMembership` and `validateOrgRole` strictly enforce `member.getStatus() == MemberStatus.ACTIVE`.
* `DealService.createDeal` validates buyer organization existence, verifies caller's active membership in buyer organization, validates seller organization existence, and rejects identical buyer/seller organization pairings.
* Valid User UUIDs passed as organization UUIDs are rejected via `OrganizationRepository` lookup failure.
* `PaymentOrchestrationService.createPaymentInstruction` populates `deal.getBuyerOrganizationId()` and `deal.getSellerOrganizationId()` into new `PaymentInstruction` records, and fails closed with `BadRequestException` if a new instruction is requested for an unbound/null-org deal.
* `PaymentOrchestrationService.getInstructionsForDeal` and `SettlementAuthorizationService.getSettlementsForDeal` load the stored `Deal` from the database and call `dealAccessService.requireDealAccess(deal, currentUser)` before returning records.
* `DisputeController` forwards the actor principal to `DisputeService.getDisputesForDeal(dealId, actor)`, and `DisputeService` loads the stored `Deal` and verifies authorization via `dealAccessService.requireDealAccess(deal, currentUser)`.
* `ReconciliationController` and `ReconciliationService` enforce platform-only access (`ADMIN` or `FINANCE`) via both `@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")` and service-level checks (`requirePlatformAdminOrFinance`). Organization-level finance members with non-platform roles (`BUYER`, `SELLER`, `PROJECT_MANAGER`) are denied with `AccessDeniedException`.
* `OrganizationController.getVerifiedBusinessProfile` accepts `@AuthenticationPrincipal UserPrincipal currentUser` and restricts access to active members of the organization or platform `ADMIN`/`FINANCE`.

## DealAccessService

`DealAccessService` implements a centralized, service-level access model enforcing authoritative boundary checks before any deal-related resource can be retrieved or mutated. It evaluates:
1. Privileged platform oversight: callers with platform `Role.ADMIN` or `Role.FINANCE` are granted administrative access.
2. Explicit Project Manager assignment: caller must match `deal.getProjectManagerId()`.
3. Verified buyer tenant membership: if `deal.getBuyerOrganizationId()` is populated, caller must have active membership in that organization. Direct user identity (`deal.getBuyerId()`) is permitted only on legacy deals lacking buyer organization binding.
4. Verified seller tenant membership: if `deal.getSellerOrganizationId()` is populated, caller must have active membership in that organization. Direct user identity (`deal.getSellerId()`) is permitted only on legacy deals lacking seller organization binding.
5. Pending Project Manager invitation: callers holding pending invitations (`ProjectManagerInvitationRepository.existsByDealIdAndProjectManagerIdAndStatus(..., PENDING)`) are permitted read-only access for onboarding.

All unassigned callers, cross-tenant users, inactive members, and forged identities fail closed with `UnauthorizedException`.

## Active Membership Enforcement

`OrganizationService.validateOrgMembership` and `OrganizationService.validateOrgRole` strictly enforce `MemberStatus.ACTIVE`.

Accepted status:
* `MemberStatus.ACTIVE` is the only accepted authority status.

Denied statuses:
* `MemberStatus.INVITED` -> denied with `UnauthorizedException("Organization membership is not active (status: INVITED)")`
* `MemberStatus.SUSPENDED` -> denied with `UnauthorizedException("Organization membership is not active (status: SUSPENDED)")`
* Any other non-ACTIVE status -> denied.

No membership records are modified automatically.

## Deal Creation Organization Binding

In `DealService.createDeal(CreateDealRequest request, UserPrincipal currentUser)`:
1. `request.getBuyerOrganizationId()` is checked:
   - Validated against `OrganizationRepository.findById(buyerOrgId)`.
   - Verified that caller has active membership in buyer organization via `organizationService.validateOrgMembership(buyerOrgId, currentUser)`.
   - Caller cannot forge another buyer organization.
2. No user-to-org UUID fallback is permitted. Passing a User UUID as an organization UUID fails the `organizationRepository.findById` lookup.
3. No guessed legacy org mapping exists; IDs are validated strictly against existing registered organizations.

## Buyer / Seller Organization Validation

In `DealService.createDeal(...)`:
1. Buyer organization must exist and caller must be an ACTIVE member.
2. Seller organization must exist in `OrganizationRepository`.
3. If both organizations are present, `buyerOrganizationId.equals(sellerOrganizationId)` is rejected with `BadRequestException("Buyer and Seller organization cannot be the same")`.
4. Domain validation ensures that self-dealing and unregistered tenant IDs fail closed immediately.

## Project Manager Assignment Boundary

The `PROJECT_MANAGER` role alone does NOT grant global access.

`DealAccessService.canUserAccessDeal` checks:
```java
if (deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(userId)) {
    return true;
}
```
An unassigned Project Manager attempting to access any deal where they are not explicitly designated in `deal.getProjectManagerId()` is strictly denied with `UnauthorizedException`.

## Payment Instruction Authorization

In `PaymentOrchestrationService.getInstructionsForDeal(UUID dealId, UserPrincipal currentUser)`:
1. Loads the stored `Deal` entity from `dealRepository.findById(dealId)`.
2. Authorizes caller via `dealAccessService.requireDealAccess(deal, currentUser)`.
3. Only upon successful authorization returns records from `instructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)`.
4. Own buyer / seller / assigned PM: permitted. Unassigned PM / cross-tenant / random caller: denied with `UnauthorizedException`.

## Settlement History Authorization

In `SettlementAuthorizationService.getSettlementsForDeal(UUID dealId, UserPrincipal currentUser)`:
1. Loads the stored `Deal` entity from `dealRepository.findById(dealId)`.
2. Authorizes caller via `dealAccessService.requireDealAccess(deal, currentUser)`.
3. Only upon successful authorization returns records from `settlementRepository.findByDealIdOrderByCreatedAtDesc(dealId)`.
4. Own buyer / seller / assigned PM: permitted. Unassigned PM / cross-tenant / random caller: denied with `UnauthorizedException`.
5. B04 settlement payout EXECUTION remains disabled.

## PaymentInstruction Organization Population

In `PaymentOrchestrationService.createPaymentInstruction(...)`:
1. Verifies that the authoritative Deal contains organization bindings:
   ```java
   if (deal.getBuyerOrganizationId() == null || deal.getSellerOrganizationId() == null) {
       throw new BadRequestException("Cannot create payment instruction for deal without verified buyer and seller organizations");
   }
   ```
2. Instantiates new `PaymentInstruction` with:
   - `buyerOrganizationId = deal.getBuyerOrganizationId()`
   - `sellerOrganizationId = deal.getSellerOrganizationId()`
3. New payment instructions are no longer passed as `null, null`.
4. Unbound legacy deals fail closed on new instruction creation; organization IDs are not inferred.

## Dispute Authorization

1. `DisputeController.getDisputesForDeal` preserves caller identity by passing `@AuthenticationPrincipal UserPrincipal actor` to `disputeService.getDisputesForDeal(dealId, actor)`.
2. In `DisputeService.getDisputesForDeal` and `DisputeService.createDispute`, authorization is enforced at the SERVICE level against the stored `Deal` entity using `dealAccessService.requireDealAccess(deal, currentUser)`.
3. Participant access:
   - Buyer participant -> permitted
   - Seller participant -> permitted
   - Assigned PM -> permitted
   - Unassigned PM -> denied
   - Cross-tenant user -> denied

## Reconciliation Authorization

Reconciliation operations (`triggerReconciliation`, `getRuns`, `getItemsForRun`) in `ReconciliationController` and `ReconciliationService` require platform oversight:
* Protected by controller `@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")`.
* Protected by service-level verification `requirePlatformAdminOrFinance(currentUser)`.
* **Explicit Distinction**:
  - Platform `Role.ADMIN` and platform `Role.FINANCE` are authorized.
  - Organization-level membership role `OrgRole.FINANCE` does NOT grant platform reconciliation authority if the user's platform role is `Role.BUYER`, `Role.SELLER`, or `Role.PROJECT_MANAGER`. Such users are denied with `AccessDeniedException`.

## Verified Business Profile Scope

In `OrganizationController.getVerifiedBusinessProfile` and `OrganizationService.getVerifiedBusinessProfile(UUID orgId, UserPrincipal currentUser)`:
* Access is restricted to active members of the organization (`validateOrgMembership(orgId, currentUser)`) or platform `ADMIN`/`FINANCE`.
* Cross-tenant and unrelated users are denied with `UnauthorizedException`.
* Verified profile access is now scoped, but metric correctness remains non-authoritative pending D06.

## Legacy / Null Organization Handling

* No organization IDs were guessed, inferred or synthesized.
* Legacy/null organization IDs are not auto-mapped.
* Unbound legacy deals fail closed for new trusted financial creation (`createPaymentInstruction`).
* Historical read authorization falls back to direct user equality (`buyerId`, `sellerId`) only when the stored deal's organization ID is `null`.

## Stored Resource Authorization

Authorization is performed against the actual stored `Deal` and associated resources fetched from the database, rather than client-supplied payload parameters or unverified URL path variables. In `createPaymentInstruction`, idempotency key verification also checks `existingInstruction.getDealId().equals(deal.getId())` to avoid cross-tenant caching disclosures.

## Interaction With B01

B01 manual payment confirmation containment remains intact. `PaymentOrchestrationService.confirmPayment(...)` immediately throws `AccessDeniedException` prior to lookup or execution.

## Interaction With B02

B02 banking webhook authentication and replay control remain intact. Webhook requests require valid signatures, enforce idempotent processing, and log deterministic event records.

## Interaction With B03

B03 direct escrow release containment remains intact. `EscrowController` and `EscrowService.releaseEscrowForSettlement` fail closed with `AccessDeniedException`.

## Interaction With B04

B04 settlement payout execution remains intact. `SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed with `AccessDeniedException`. Only historical settlement reading is authorized for deal parties.

## Interaction With B12

B12 legacy intake containment remains intact. Razorpay order creation and direct escrow funding remain disabled.

## Files Modified

* `backend/src/main/java/com/finx/deal/service/DealService.java`
* `backend/src/main/java/com/finx/dispute/controller/DisputeController.java`
* `backend/src/main/java/com/finx/dispute/service/DisputeService.java`
* `backend/src/main/java/com/finx/org/controller/OrganizationController.java`
* `backend/src/main/java/com/finx/org/service/OrganizationService.java`
* `backend/src/main/java/com/finx/paymentorchestration/service/PaymentOrchestrationService.java`
* `backend/src/main/java/com/finx/paymentorchestration/service/SettlementAuthorizationService.java`
* `backend/src/main/java/com/finx/reconciliation/controller/ReconciliationController.java`
* `backend/src/main/java/com/finx/reconciliation/service/ReconciliationService.java`
* `backend/src/test/java/com/finx/escrow/TestEscrowReleaseFixture.java`
* `backend/src/test/java/com/finx/paymentorchestration/SettlementAuthorizationServiceTest.java`
* `backend/src/test/java/com/finx/reconciliation/ReconciliationServiceTest.java`

## Files Created

* `backend/src/main/java/com/finx/deal/service/DealAccessService.java`
* `backend/src/test/java/com/finx/security/TenantIsolationContainmentTest.java`
* `B07_REMEDIATION_REPORT.md`

## Database Migration

NONE

## API Compatibility

Unauthorized, inactive, and cross-tenant requests now fail closed with `UnauthorizedException` (401) or `AccessDeniedException` (403). Valid requests from authorized active participants retain existing contracts.

## Tests Added

`com.finx.security.TenantIsolationContainmentTest` (40 tests):
* `test01_validateOrgMembership_activeMember_allowed`
* `test02_validateOrgMembership_invitedMember_denied`
* `test03_validateOrgMembership_suspendedMember_denied`
* `test04_validateOrgMembership_nonMember_denied`
* `test05_createDeal_validActiveBuyerOrgMember_allowed`
* `test06_createDeal_callerNotBuyerOrgMember_denied`
* `test07_createDeal_callerInactiveBuyerOrgMember_denied`
* `test08_createDeal_nonExistentBuyerOrg_denied`
* `test09_createDeal_nonExistentSellerOrg_denied`
* `test10_createDeal_sameBuyerAndSellerOrg_denied`
* `test11_getInstructionsForDeal_ownBuyerOrgMember_allowed`
* `test12_getInstructionsForDeal_ownSellerOrgMember_allowed`
* `test13_getInstructionsForDeal_assignedProjectManager_allowed`
* `test14_getInstructionsForDeal_unassignedProjectManager_denied`
* `test15_getInstructionsForDeal_crossTenantCaller_denied`
* `test16_getInstructionsForDeal_platformAdmin_allowed`
* `test17_getInstructionsForDeal_platformFinance_allowed`
* `test18_createPaymentInstruction_populatesBuyerAndSellerOrgFromDeal`
* `test19_createPaymentInstruction_unboundDeal_failsClosed`
* `test20_createPaymentInstruction_idempotentKeyMatchingDifferentDeal_denied`
* `test21_getSettlementsForDeal_dealParty_allowed`
* `test22_getSettlementsForDeal_crossTenantCaller_denied`
* `test23_getSettlementsForDeal_unassignedProjectManager_denied`
* `test24_getDisputesForDeal_actorPassedAndAuthorized_allowed`
* `test25_getDisputesForDeal_crossTenantCaller_denied`
* `test26_getDisputesForDeal_unassignedProjectManager_denied`
* `test27_createDispute_dealParty_allowed`
* `test28_createDispute_crossTenantCaller_denied`
* `test29_createDispute_unassignedProjectManager_denied`
* `test30_triggerReconciliation_platformAdmin_allowed`
* `test31_triggerReconciliation_platformFinance_allowed`
* `test32_triggerReconciliation_ordinaryBuyer_denied`
* `test33_triggerReconciliation_ordinarySeller_denied`
* `test34_triggerReconciliation_unassignedProjectManager_denied`
* `test35_triggerReconciliation_orgFinanceRoleWithoutPlatformFinance_denied`
* `test36_getVerifiedBusinessProfile_activeMember_allowed`
* `test37_getVerifiedBusinessProfile_crossOrgCaller_denied`
* `test38_getVerifiedBusinessProfile_inactiveMember_denied`
* `test39_getVerifiedBusinessProfile_platformAdmin_allowed`
* `test40_getVerifiedBusinessProfile_platformFinance_allowed`

## Tests Run

* `.\mvnw.cmd -Dtest=TenantIsolationContainmentTest test`
* `.\mvnw.cmd -Dtest=DealServiceTest,SettlementAuthorizationServiceTest,ReconciliationServiceTest test`
* `.\mvnw.cmd -Dtest=PaymentConfirmationContainmentTest,BankingWebhookControllerTest,EscrowReleaseContainmentTest,SettlementExecutionContainmentTest,LegacyBoundaryContainmentTest test`
* `.\mvnw.cmd -Dtest=SecurityHardenedTest,BankIntegratedEndToEndTest,TransactionEngineEndToEndTest,AuthEndToEndTest test`
* `.\mvnw.cmd test`

## Test Results

* Focused Test Total (`TenantIsolationContainmentTest`): 40 tests, 0 failures, 0 errors, 0 skipped
* Affected Unit Suites (`DealServiceTest`, `SettlementAuthorizationServiceTest`, `ReconciliationServiceTest`): 37 tests, 0 failures, 0 errors, 0 skipped
* Prior Containment Suites (`PaymentConfirmationContainmentTest`, `BankingWebhookControllerTest`, `EscrowReleaseContainmentTest`, `SettlementExecutionContainmentTest`, `LegacyBoundaryContainmentTest`): 84 tests, 0 failures, 0 errors, 0 skipped
* Security / End-to-End Suites (`SecurityHardenedTest`, `BankIntegratedEndToEndTest`, `TransactionEngineEndToEndTest`, `AuthEndToEndTest`): 6 tests, 0 failures, 0 errors, 0 skipped
* Full Backend Suite (`mvn test`): 454 tests run, 0 failures, 0 errors, 1 skipped. BUILD SUCCESS.

## Known Limitations

* B08 still fixes platform FINANCE provisioning
* B09 still fixes beneficiary verification
* B05 still fixes scoped idempotency/replay
* D06 still fixes organization metric correctness
* No database RLS added
* Legacy null-org history not repaired automatically

## B07 Status

PASS

## Ready for B08

YES
