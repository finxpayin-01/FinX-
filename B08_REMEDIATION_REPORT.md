# B08 Remediation Report

## Blocker

B08 — Public FINANCE registration grants platform authority

## Root Cause

1. `AuthService.register(...)` only rejected `Role.ADMIN` (`if (request.getRole() == Role.ADMIN)`), allowing any other role submitted in `RegisterRequest`—specifically `Role.FINANCE`—to be self-selected by unauthenticated registrants.
2. `RegisterRequest` and `Role.java` explicitly advertised `FINANCE` as an acceptable registration role (`@NotNull(message = "Role is required (BUYER, SELLER, PROJECT_MANAGER, or FINANCE)")` and `throw new IllegalArgumentException("... Permitted registration roles are: BUYER (or CORPORATE), SELLER (or VENDOR), PROJECT_MANAGER (or PROJ_MANAGER), FINANCE")`).
3. Accounts created with platform `Role.FINANCE` were granted expansive platform-wide authority:
   - Access to `/api/finance/**` and `/api/v1/finance/**` in `SecurityConfig`.
   - Access to platform inflow, outflow, settlement totals, and double-entry ledger account balances in `FinanceController`.
   - Access to global project finance ledgers, milestone statuses, and escrow records in `FinanceProjectController`.
   - Access to platform-wide reconciliation runs and mismatch discrepancy items in `ReconciliationController` and `ReconciliationService`.
   - Platform-wide deal oversight in `DealAccessService`.
4. While organization-level membership roles (`OrgRole.FINANCE`) are properly scoped to tenant organizations, public registration created platform `Role.FINANCE` users in the primary `users` table, conflating tenant-level accounting needs with platform operator oversight.

## Before

* Public registration rejected `Role.ADMIN` with `BadRequestException("ADMIN accounts cannot be registered publicly")`, but accepted `Role.FINANCE` unconditionally.
* Any external actor could submit raw JSON `{"role": "FINANCE"}` (or aliases `finance`, `Finance`) and receive a 201 Created response with full platform `Role.FINANCE` privileges.
* Users registering as `FINANCE` obtained immediate visibility into all platform transactions, double-entry ledger accounts, all customer deals, escrow balances, and global banking reconciliation feeds.
* Google OAuth registration did not explicitly validate or reject caller-supplied `Role.FINANCE`.
* Existing tests (`AuthEndToEndTest`) asserted that public registration with `role: "FINANCE"` succeeded with HTTP 201.

## After

* `AuthService.register(...)` enforces a strict, explicit public registration allowlist: `Set.of(Role.BUYER, Role.SELLER, Role.PROJECT_MANAGER)`.
* Privileged platform roles are strictly rejected:
  - `Role.ADMIN` -> `BadRequestException("ADMIN accounts cannot be registered publicly")`
  - `Role.FINANCE` -> `BadRequestException("FINANCE accounts cannot be registered publicly. Platform finance authority must be provisioned administratively.")`
  - Any non-allowlisted role -> `BadRequestException("Registration for role <ROLE> is not permitted publicly")`
* `RegisterRequest` validation message updated to omit `FINANCE`: `@NotNull(message = "Role is required (BUYER, SELLER, or PROJECT_MANAGER)")`.
* `Role.fromString` exception message updated to omit `FINANCE` from permitted public registration roles.
* `AuthService.authenticateWithGoogle(...)` validates caller-supplied roles upfront and rejects attempts to register privileged roles (`ADMIN`, `FINANCE`) with `BadRequestException("Privileged platform roles (ADMIN, FINANCE) cannot be provisioned via OAuth")`.
* Public role mutation confirmed non-existent: no controller or service exposes PUT or PATCH endpoints on user roles; ordinary user profile reads remain strictly read-only (`GET /api/v1/auth/me`).
* Organization-level `OrgRole.FINANCE` remains available and fully functional for tenant members, without elevating the user's platform `Role`.
* Legitimate platform `Role.FINANCE` operators retain access to finance dashboards and reconciliation via administrative/data-level provisioning.

## Public Registration Allowlist

The public registration allowlist is defined in `AuthService`:
```java
private static final java.util.Set<Role> PUBLIC_REGISTRATION_ROLES = java.util.Set.of(
        Role.BUYER,
        Role.SELLER,
        Role.PROJECT_MANAGER
);
```

Publicly allowed roles:
- `Role.BUYER` (and accepted parser alias `CORPORATE`)
- `Role.SELLER` (and accepted parser alias `VENDOR`)
- `Role.PROJECT_MANAGER` (and accepted parser aliases `PROJ_MANAGER`, `PROJ MANAGER`, `PROJECT MANAGER`)

## Privileged Roles

Privileged platform roles are strictly denied from public self-registration:
- `Role.ADMIN` public signup = denied (`BadRequestException: ADMIN accounts cannot be registered publicly`)
- `Role.FINANCE` public signup = denied (`BadRequestException: FINANCE accounts cannot be registered publicly. Platform finance authority must be provisioned administratively.`)
- Case and alias variants (`finance`, `Finance`, `admin`, `ROLE_FINANCE`, `ROLE_ADMIN`) are denied:
  - Valid enum case/aliases resolve to `Role.FINANCE` or `Role.ADMIN` and are rejected by the public allowlist with HTTP 400.
  - Unsupported prefixes (`ROLE_FINANCE`, `ROLE_ADMIN`) or unknown roles (`SUPERUSER`) fail parser deserialization and are safely rejected with HTTP 400.

## OAuth / External Registration

In `AuthService.authenticateWithGoogle(GoogleOAuthRequest request)`:
- Upfront validation rejects caller-supplied `Role.ADMIN` or `Role.FINANCE`:
  ```java
  if (request.getRole() == Role.ADMIN || request.getRole() == Role.FINANCE) {
      throw new BadRequestException("Privileged platform roles (ADMIN, FINANCE) cannot be provisioned via OAuth");
  }
  ```
- New user provisioning defaults safely to `Role.BUYER` unless `request.getRole() == Role.SELLER`.
- Normal OAuth sign-in and sign-up for `BUYER` and `SELLER` remain operational.

## Public Role Mutation

Audited all controllers and services across the backend:
- `AuthController` only provides `register`, `login`, `refresh`, `logout`, OAuth flows, and read-only `GET /me`.
- `AdminController` only provides read-only `GET /users`, `GET /dashboard`, and `GET /audit-logs`.
- No PUT or PATCH mapping exists for updating user profiles or user roles.
- `User.setRole(...)` is never called in production code paths; it is only referenced in test fixtures.
- Documented status: **NO PUBLIC ROLE-MUTATION PATH FOUND**.

## Organization FINANCE vs Platform FINANCE

FinX maintains a strict, architectural distinction between two concepts:

1. **Platform Role.FINANCE**:
   - Represents a privileged FinX platform operator authority.
   - Stored in `users.role`.
   - Grants global platform oversight: global ledger inspection (`/api/v1/finance/ledger`), cross-tenant financial metrics (`/api/v1/finance/summary`), global project ledgers (`/api/finance/projects`), and global transaction reconciliation runs (`/api/reconciliation/**`).
   - Must NOT be publicly self-provisioned.

2. **Organization Member OrgRole.FINANCE**:
   - Represents a tenant-scoped membership role within a specific `Organization`.
   - Stored in `organization_members.role`.
   - Bound to an active membership (`OrganizationMember`) in a single organization.
   - Grants organization-level authority (e.g. managing organization bank accounts and viewing organization profiles).
   - Does NOT set or elevate the user's platform `Role` (the user's platform role remains `Role.BUYER`, `Role.SELLER`, or `Role.PROJECT_MANAGER`).
   - B07 and B08 enforce that organization-level finance members cannot access global reconciliation (`ReconciliationService.requirePlatformAdminOrFinance`) or global platform finance dashboards.

## Controlled Platform Provisioning

- Legitimate platform operators with `Role.FINANCE` or `Role.ADMIN` cannot self-register through public signup or OAuth.
- No public workaround or unauthenticated operator creation API was introduced.
- Legitimate platform finance operators must currently be provisioned through controlled administrative data operations or automated bootstrap scripts (`DataInitializer`) until a dedicated enterprise operator-provisioning workflow is introduced.
- Existing legitimately provisioned platform finance accounts retain full intended platform authority.

## Existing FINANCE Accounts

Provenance does not exist and existing FINANCE assignments require controlled review; no automatic guess/demotion was performed.

Source and schema inspection of `User.java` and migration `V1__create_users_and_auth_tables.sql` confirms:
- The `users` table contains columns: `id`, `name`, `email`, `password_hash`, `role`, `status`, `created_at`, `updated_at`, `uid`, `pm_id`.
- There is no provenance column (e.g., `provisioned_by`, `operator_provenance`, `is_platform_operator`, or `source`).
- Consequently, the current schema cannot reliably distinguish a legitimately provisioned internal finance operator from a historical public self-registrant.
- Blindly demoting all `Role.FINANCE` users in the database would risk locking out legitimate operators.
- Containment ensures no new unauthorized FINANCE accounts can be registered. Historical accounts require controlled administrative review and manual remediation.

## Finance Endpoint Authorization

Global finance and reconciliation endpoints remain strictly restricted to privileged platform roles:
- `FinanceController` (`/api/v1/finance/**`): protected by `SecurityConfig` (`hasAnyRole("ADMIN", "FINANCE")`).
- `FinanceProjectController` (`/api/finance/**`): protected by class-level `@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")` and `SecurityConfig`.
- `ReconciliationController` (`/api/v1/admin/reconciliation/**` and `/api/reconciliation/**`): protected by `@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")` and service-level `requirePlatformAdminOrFinance(...)`.
- `DealAccessService`: platform `Role.ADMIN` and `Role.FINANCE` retain oversight; organization-level finance members without platform roles are treated as normal participants and restricted to deals where their organization is an active counterparty.

## Interaction With B01

B01 disabled untrusted public payment confirmation (`POST /payments/instructions/{id}/confirm`). B08 prevents public users from self-registering with `Role.FINANCE`, ensuring that public actors cannot acquire platform privileges to access financial endpoints. Both boundaries fail closed against unprivileged assertions of financial truth.

## Interaction With B02

B02 secured bank webhook ingestion with signature verification, timestamp tolerance, and idempotent inbox deduplication. B08 ensures that webhook and authentication flows cannot be abused to self-provision platform finance accounts.

## Interaction With B03

B03 disabled direct public escrow release (`POST /api/escrow/{milestoneId}/release`). B08 ensures public registrants cannot gain platform finance access to inspect or manipulate escrow ledger balances.

## Interaction With B04

B04 failed closed on combined settlement execution (`POST /settlements/authorize`). B08 ensures that callers querying settlement records or historical financial summaries via finance endpoints are authenticated with legitimate platform `Role.FINANCE` or `Role.ADMIN`, while execution remains strictly disabled.

## Interaction With B12

B12 established an architectural boundary separating legacy payment and blockchain routes from authoritative bank payment orchestration. B08 ensures that public registration cannot provision privileged platform authority across either modern or legacy surfaces.

## Interaction With B07

B07 established tenant isolation, active membership verification, and counterparty deal binding. It explicitly decoupled organization-level membership roles (`OrgRole.FINANCE`) from platform reconciliation authority. B08 reinforces B07 by closing the public registration loophole that previously allowed users to self-provision platform `Role.FINANCE`, ensuring that organization finance members and platform finance operators remain strictly segregated.

## Files Modified

- `backend/src/main/java/com/finx/auth/service/AuthService.java`
- `backend/src/main/java/com/finx/auth/dto/request/RegisterRequest.java`
- `backend/src/main/java/com/finx/common/enums/Role.java`
- `backend/src/main/java/com/finx/auth/controller/AuthController.java`
- `backend/src/test/java/com/finx/auth/AuthServiceTest.java`
- `backend/src/test/java/com/finx/e2e/AuthEndToEndTest.java`

## Files Created

- `backend/src/test/java/com/finx/security/PlatformRoleProvisioningContainmentTest.java`
- `B08_REMEDIATION_REPORT.md`

## Database Migration

NONE. No schema migration was added or modified. Historical migrations remain untouched.

## API Compatibility

- Public registration requests with `"role": "FINANCE"` return HTTP 400 Bad Request instead of HTTP 201 Created.
- Public registration requests with `"role": "ADMIN"` continue to return HTTP 400 Bad Request.
- Normal public registration for `BUYER`, `SELLER`, `PROJECT_MANAGER` (and aliases `CORPORATE`, `VENDOR`, `PROJ_MANAGER`) continues to succeed with HTTP 201 Created.
- Google OAuth requests attempting to supply `ADMIN` or `FINANCE` return HTTP 400 Bad Request.
- Legitimate platform operators with `Role.FINANCE` retain existing access to finance dashboards and reconciliation.

## Tests Added

`PlatformRoleProvisioningContainmentTest`:
1. `testBuyerRegistration_Succeeds` — BUYER public registration succeeds (HTTP 201).
2. `testSellerRegistration_Succeeds` — SELLER public registration succeeds (HTTP 201).
3. `testProjectManagerRegistration_Succeeds` — PROJECT_MANAGER public registration succeeds (HTTP 201).
4. `testAdminRegistration_Denied` — ADMIN public registration is denied (HTTP 400).
5. `testFinanceRegistration_Denied` — FINANCE public registration is denied (HTTP 400).
6. `testPrivilegedRoleCaseAndAliasBypass_Denied` — Case and alias bypasses (`finance`, `Finance`, `admin`, `ROLE_FINANCE`) are denied.
7. `testMalformedRole_RejectedSafely` — Malformed / unknown roles (`SUPERUSER`, empty string) are rejected safely (HTTP 400).
8. `testOAuth_CannotChooseAdmin` — OAuth registration cannot choose ADMIN (HTTP 400).
9. `testOAuth_CannotChooseFinance` — OAuth registration cannot choose FINANCE (HTTP 400).
10. `testOAuth_NormalUserBehaviorPreserved` — OAuth normal-user behavior preserved for safe roles.
11. `testOrdinaryUserCannotChangeRoleToFinance` — Ordinary user cannot change own role to FINANCE (no public mutation path exists).
12. `testOrdinaryUserCannotChangeRoleToAdmin` — Ordinary user cannot change own role to ADMIN (no public mutation path exists).
13. `testOrgFinanceMembershipDoesNotSetUserPlatformRole` — Organization FINANCE membership does not set User platform Role.FINANCE.
14. `testOrgFinanceMemberCannotAccessGlobalFinance` — Organization FINANCE member cannot access global finance endpoints.
15. `testOrgFinanceMemberCannotAccessGlobalReconciliation` — Organization FINANCE member cannot access global reconciliation.
16. `testLegitimatePlatformFinance_RetainsIntendedAccess` — Legitimately provisioned platform FINANCE retains intended global finance and reconciliation access.
17. `testPlatformAdmin_RetainsIntendedAccess` — Platform ADMIN retains intended global access.
18. `testSchemaLacksRoleProvenance_ControlledReviewRequired` — Verifies schema contains no role-provenance column, documenting why automatic demotion is impossible without guessing.

`AuthServiceTest`:
- `testRegister_FinanceRole_ThrowsBadRequestException` — Unit test asserting `Role.FINANCE` registration throws `BadRequestException`.

`AuthEndToEndTest`:
- Updated `testRegistrationWithAllSupportedRolesAndAliases` to assert `FINANCE` registration returns HTTP 400 Bad Request.

## Tests Run

- `PlatformRoleProvisioningContainmentTest` (18 tests)
- `AuthServiceTest` (11 tests)
- `AuthEndToEndTest` (3 tests)
- `RoleTest` (6 tests)
- `PaymentConfirmationContainmentTest` (B01) (15 tests)
- `BankingWebhookControllerTest` (B02) (28 tests)
- `EscrowReleaseContainmentTest` (B03) (13 tests)
- `SettlementExecutionContainmentTest` (B04) (18 tests)
- `LegacyBoundaryContainmentTest` (B12) (10 tests)
- `TenantIsolationContainmentTest` (B07) (40 tests)
- Full backend suite (`mvn test`) (473 tests)

## Test Results

- Tests run: 473
- Failures: 0
- Errors: 0
- Skipped: 1
- Result: **BUILD SUCCESS**

## Known Limitations

* Existing historical FINANCE accounts cannot be programmatically distinguished from legitimate operators due to lack of provenance columns in the schema; controlled manual data review is required.
* No dedicated admin UI or self-service operator-provisioning workflow was added (out of scope for B08).
* B09 beneficiary and verification authority remains separate.
* No database role-provenance migration was introduced.

## B08 Status

PASS

## Ready for B09

YES
