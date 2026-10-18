# FinX Web Button & Form Functional Audit Report

**Audit Date:** 2026-10-18  
**Scope:** Interactive Elements (Buttons, Form Submissions, Navigation Links, Modal Actions)  
**Total Buttons Tested:** 537  
**Total Forms Tested:** 10  
**Broken / Dead Buttons Detected:** 17  
**Authoritative Status:** AUDIT ONLY (No code modifications applied)

---

## Executive Summary

Across the 60 routes tested in the FinX web application:
- **537 interactive elements** were audited for clickability, event binding, API invocation, disabled states, and navigation validity.
- **520 buttons** functioned properly (performing client navigation, opening modals, submitting forms, or executing verified API calls).
- **17 buttons** were identified as **Dead Buttons** (no `onClick` handler, nested inside invalid interactive HTML tags, or failing to trigger the intended mutation/action).
- **10 forms** were audited across registration, login, KYC document submission, deal creation, and filters. All 10 forms correctly render input elements and submit valid payloads, with two form validation/feedback defects noted.

---

## Complete Broken & Dead Button Inventory

| Element ID | Page / Route | Role | Element Text | HTML Tag | Expected Action | Actual Behavior | Defect ID | Severity | Root Cause |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `BTN-DEAD-01` | `/` | PUBLIC | `Get Started` | `BUTTON` | Smooth scroll to `#details` section | Trapped inside `<a href="#details">`; button tag has no direct listener | `DEFECT-015` | P3 | Nested interactive element |
| `BTN-DEAD-02` | `/` | PUBLIC | `See Entire Detail` | `BUTTON` | Smooth scroll to `#details` section | Trapped inside `<a href="#details">`; button tag has no direct listener | `DEFECT-015` | P3 | Nested interactive element |
| `BTN-DEAD-03` | `/` | PUBLIC | `Create Account` | `BUTTON` | Navigate to `/register` | Trapped inside `<Link href="/register">`; button tag has no direct listener | `DEFECT-015` | P3 | Nested interactive element |
| `BTN-DEAD-04` | `/` | PUBLIC | `Start Using FINX Today` | `BUTTON` | Navigate to `/register` | Trapped inside `<Link href="/register">`; button tag has no direct listener | `DEFECT-015` | P3 | Nested interactive element |
| `BTN-DEAD-05` | `/blockchain` | PUBLIC | `Show password` | `BUTTON` | Toggle password masking on demo card | Inert; no `onClick` state toggle attached | `DEFECT-017` | P3 | Static demo element |
| `BTN-DEAD-06` | `/auth/callback/google` | PUBLIC | `Return to Sign In` | `BUTTON` | Redirect to `/login` | Button rendered in error state without attached `onClick` handler | `DEFECT-018` | P3 | Missing router navigation |
| `BTN-DEAD-07` | `/admin` | ADMIN | `Launch Escrow Command` | `BUTTON` | Open escrow modal or navigate to `/admin/deals` | Inert placeholder button | `DEFECT-013` | P3 | Unwired quick action |
| `BTN-DEAD-08` | `/admin` | ADMIN | `View Audit History` | `BUTTON` | Navigate to `/admin/audit-logs` | Inert placeholder button | `DEFECT-013` | P3 | Unwired quick action |
| `BTN-DEAD-09` | `/admin/deals` | ADMIN | `Sign Out` | `BUTTON` | Terminate session and redirect to `/login` | Header secondary sign out button does not invoke `logout()` | `DEFECT-014` | P3 | Unwired header button |
| `BTN-DEAD-10` | `/admin/deals` | ADMIN | `Refresh` | `BUTTON` | Refetch deal list | Invokes `fetchDeals()` which calls double-prefixed `/api/v1/deals` | `DEFECT-003` | P2 | API URL mismatch |
| `BTN-DEAD-11` | `/admin/risk/buyers` | ADMIN | `Apply filters` | `BUTTON` | Apply risk score and level filters | Inert button; filters are applied client-side without form submit hook | `DEFECT-019` | P3 | Unbound filter submit |
| `BTN-DEAD-12` | `/admin/risk/vendors` | ADMIN | `Apply filters` | `BUTTON` | Apply risk score and level filters | Inert button; filters are applied client-side without form submit hook | `DEFECT-019` | P3 | Unbound filter submit |
| `BTN-DEAD-13` | `/admin/users` | ADMIN | `Suspend Identity` (Row 1) | `BUTTON` | Suspend user account | Inert; no `onClick` attached to table row action button | `DEFECT-012` | P3 | Unbound table action |
| `BTN-DEAD-14` | `/admin/users` | ADMIN | `Suspend Identity` (Row 2) | `BUTTON` | Suspend user account | Inert; no `onClick` attached to table row action button | `DEFECT-012` | P3 | Unbound table action |
| `BTN-DEAD-15` | `/admin/users` | ADMIN | `Suspend Identity` (Row 3) | `BUTTON` | Suspend user account | Inert; no `onClick` attached to table row action button | `DEFECT-012` | P3 | Unbound table action |
| `BTN-DEAD-16` | `/admin/users` | ADMIN | `Suspend Identity` (Row 4) | `BUTTON` | Suspend user account | Inert; no `onClick` attached to table row action button | `DEFECT-012` | P3 | Unbound table action |
| `BTN-DEAD-17` | `/admin/users` | ADMIN | `Suspend Identity` (Row 5) | `BUTTON` | Suspend user account | Inert; no `onClick` attached to table row action button | `DEFECT-012` | P3 | Unbound table action |

---

## Form Functional Audit

### Form 1: User Login Form
- **Route:** `/login`
- **Role:** PUBLIC
- **Inputs:** `email` (type: email, required), `password` (type: password, required)
- **Submit Action:** POST `/api/auth/login`
- **Payload:** `{"email": "...", "password": "..."}`
- **Empty Submission:** Handled by HTML5 `required` attribute.
- **Valid Submission:** Successfully authenticated all 5 platform roles and issued JWT tokens (`PASS`).
- **Invalid Submission:** Catches 401 error, but **does not display visible error message** to the user (`DEFECT-001`, Severity: P2).
- **Double Submit Protection:** Submit button transitions to disabled spinner state during in-flight call (`PASS`).

### Form 2: User Registration Form
- **Route:** `/register`
- **Role:** PUBLIC
- **Inputs:** `fullName` (type: text, required), `email` (type: email, required), `password` (type: password, required)
- **Submit Action:** POST `/api/auth/register`
- **Empty Submission:** Form blocks empty submit with input required markers (`PASS`).
- **Valid Submission:** Submits registration request to backend (`PASS`).
- **Role Selector Defect:** Registration form **lacks role selection UI** (`DEFECT-016`, Severity: P3). Role defaults to `BUYER` without user agency.

### Form 3: Forgot Password Form
- **Route:** `/forgot-password`
- **Role:** PUBLIC
- **Inputs:** `email` (type: email, required)
- **Submit Action:** Sends recovery email request
- **Validation:** Validated email format (`PASS`).
- **UI State:** Shows feedback upon submission (`PASS`).

### Form 4: KYC Verification Form
- **Route:** `/kyc`
- **Role:** BUYER / SELLER
- **Inputs:** PAN number, identity document upload, consent checkbox
- **Submit Action:** POST `/api/v1/kyc/consent` -> POST `/api/v1/kyc/start`
- **Validation:** Enforces document file format and consent validation (`PASS`).
- **UI State:** Renders document drag-and-drop zone with progress states (`PASS`).

### Form 5: Deal Initiation Form
- **Route:** `/corporate/projects/new` (Modal / Page)
- **Role:** BUYER
- **Inputs:** `title`, `description`, `sellerId` (dropdown), `totalAmount`, `currency`
- **Submit Action:** POST `/api/deals`
- **Seller Dropdown:** Correctly populated via `GET /api/deals/sellers` (`PASS`).
- **Validation:** Rejects negative or zero amounts (`PASS`).

### Form 6: Milestone Creation Form
- **Route:** `/corporate/milestones` (Deal Detail View)
- **Role:** BUYER
- **Inputs:** `title`, `description`, `amount`, `deadline`
- **Submit Action:** POST `/api/v1/deals/{dealId}/milestones`
- **Validation:** Ensures milestone sum does not exceed unallocated deal balance (`PASS`).

### Form 7: Deliverable Submission Form
- **Route:** `/vendor/deliverables`
- **Role:** SELLER
- **Inputs:** Deliverable notes, external URL / file attachment
- **Submit Action:** POST `/api/v1/milestones/{id}/submit`
- **Validation:** Requires deliverable notes or proof link (`PASS`).

### Form 8: Dispute Filing Form
- **Route:** `/corporate/disputes`, `/vendor/disputes`
- **Role:** BUYER / SELLER
- **Inputs:** `dealId`, `milestoneId`, `reason`, `evidence`
- **Submit Action:** POST `/api/v1/disputes`
- **Validation:** Requires selection of valid deal and milestone (`PASS`).

### Form 9: Bank Account Linking Form
- **Route:** `/corporate/settings`, `/vendor/settings`
- **Role:** BUYER / SELLER
- **Inputs:** Account number, confirm account number, IFSC code, account holder name
- **Submit Action:** POST `/api/v1/organizations/{id}/bank-accounts`
- **Endpoint Hazard:** Verification trigger calls mismatched `/api/v1/bank-accounts/{id}/verify-penny-drop` (`DEFECT-005`, Severity: P1).

### Form 10: Risk Policy Rule Form
- **Route:** `/admin/risk/rules`
- **Role:** ADMIN
- **Inputs:** Metric thresholds, points adjustment, enable/disable toggle
- **Submit Action:** PATCH `/admin/risk/rules/{id}`
- **Validation:** Numeric bounds enforced (`PASS`).

---

## Working Interactive Elements Summary

- **Navigation Menus (Sidebar & Header):** 100% operational across all 5 platform roles (`PASS`).
- **Role Switching & Logout:** `useAuth().logout()` terminates JWT session, clears local storage, and redirects to `/login` (`PASS`).
- **Tab Components:** Workspace tab switching across Projects, Milestones, Settlements, and Risk items operates cleanly with zero hydration warnings (`PASS`).
- **Modal Dialogs:** Deal creation, milestone deliverable inspection, and dispute detail modals render with appropriate backdrop blur and dismiss controls (`PASS`).
