# FinX Web Test Coverage Matrix

**Audit Date:** 2026-10-18  
**Scope:** Complete Route Inventory across All Platform Roles  
**Total Routes Discovered:** 60  
**Total Routes Tested:** 60  
**Routes PASS:** 50  
**Routes PARTIAL:** 9  
**Routes FAIL:** 1  
**Authoritative Status:** AUDIT ONLY (No code modifications applied)

---

| Area | Route | Role | Buttons Tested | Forms Tested | API Tested | Result | Defects |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Public Marketing | `/` | PUBLIC | 5 | 0 | Static assets | PASS | DEFECT-015 |
| Authentication | `/login` | PUBLIC | 4 | 1 | `/api/auth/login` | PASS | DEFECT-001 |
| Authentication | `/register` | PUBLIC | 5 | 1 | `/api/auth/register` | PASS | DEFECT-016 |
| Authentication | `/forgot-password` | PUBLIC | 5 | 1 | `/api/auth/forgot-password` | PASS | None |
| Public Support | `/contact` | PUBLIC | 4 | 1 | `/api/contact` | PASS | None |
| Public Explorer | `/blockchain` | PUBLIC | 3 | 1 | `/api/notifications`, `/api/deals` | PARTIAL | DEFECT-009, DEFECT-017 |
| Verification | `/kyc` | BUYER | 8 | 0 | `/api/v1/kyc/consent`, `/api/v1/kyc/start` | PASS | None |
| Onboarding | `/onboarding` | BUYER | 10 | 0 | `/api/v1/organizations/me` | PARTIAL | DEFECT-003 |
| Routing | `/dashboard` | BUYER | 2 | 0 | Session check | PASS | None |
| Fallback | `/_not-found` | PUBLIC | 5 | 1 | Next.js fallback | FAIL | 404 Render |
| OAuth | `/auth/callback/google` | PUBLIC | 2 | 0 | `/api/auth/google/callback` | PASS | DEFECT-008, DEFECT-018 |
| Buyer Portal | `/corporate` | BUYER | 7 | 0 | `/api/deals`, `/api/notifications` | PASS | None |
| Buyer Portal | `/corporate/projects` | BUYER | 10 | 0 | `/api/deals`, `/api/deals/sellers` | PASS | None |
| Buyer Portal | `/corporate/milestones` | BUYER | 10 | 0 | `/api/v1/milestones` | PASS | None |
| Buyer Portal | `/corporate/payments` | BUYER | 8 | 0 | `/api/v1/payments/instructions` | PASS | None |
| Buyer Portal | `/corporate/escrow` | BUYER | 8 | 0 | `/api/escrow/deal/{id}` | PASS | None |
| Buyer Portal | `/corporate/disputes` | BUYER | 8 | 0 | `/api/v1/disputes` | PASS | None |
| Buyer Portal | `/corporate/settings` | BUYER | 14 | 0 | `/api/v1/organizations/{id}/bank-accounts` | PASS | DEFECT-005 |
| Seller Portal | `/vendor` | SELLER | 8 | 0 | `/api/deals`, `/api/notifications` | PASS | None |
| Seller Portal | `/vendor/projects` | SELLER | 8 | 0 | `/api/deals` | PASS | None |
| Seller Portal | `/vendor/milestones` | SELLER | 8 | 0 | `/api/v1/milestones` | PASS | None |
| Seller Portal | `/vendor/deliverables` | SELLER | 9 | 1 | `/api/v1/milestones/{id}/submit` | PASS | None |
| Seller Portal | `/vendor/payments` | SELLER | 8 | 0 | `/api/v1/payments/instructions` | PASS | None |
| Seller Portal | `/vendor/escrow` | SELLER | 8 | 0 | `/api/escrow/deal/{id}` | PASS | None |
| Seller Portal | `/vendor/settings` | SELLER | 14 | 0 | `/api/v1/organizations/{id}/bank-accounts` | PASS | DEFECT-005 |
| PM Portal | `/project-manager` | PROJECT_MANAGER | 7 | 0 | `/api/deals`, `/api/project-manager/invitations/count` | PASS | None |
| PM Portal | `/project-manager/projects` | PROJECT_MANAGER | 7 | 0 | `/api/deals` | PASS | None |
| PM Portal | `/project-manager/reviews` | PROJECT_MANAGER | 7 | 0 | `/api/v1/milestones` | PASS | None |
| PM Portal | `/project-manager/deliverables` | PROJECT_MANAGER | 7 | 0 | `/api/v1/milestones` | PASS | None |
| PM Portal | `/project-manager/reports` | PROJECT_MANAGER | 7 | 0 | `/api/deals` | PASS | None |
| PM Portal | `/project-manager/invitations` | PROJECT_MANAGER | 10 | 0 | `/api/project-manager/invitations` | PASS | None |
| PM Portal | `/project-manager/settings` | PROJECT_MANAGER | 15 | 0 | `/api/auth/me` | PASS | None |
| Admin Portal | `/admin` | ADMIN | 9 | 0 | `/api/admin/dashboard` | PASS | DEFECT-013 |
| Admin Portal | `/admin/deals` | ADMIN | 8 | 0 | `/api/v1/deals` | PASS | DEFECT-003, DEFECT-014 |
| Admin Portal | `/admin/projects` | ADMIN | 7 | 0 | `/api/deals` | PASS | None |
| Admin Portal | `/admin/escrow` | ADMIN | 7 | 0 | `/api/escrow` | PASS | None |
| Admin Portal | `/admin/settlements` | ADMIN | 16 | 0 | `/api/v1/admin/settlements` | PARTIAL | DEFECT-003, DEFECT-010 |
| Admin Portal | `/admin/disputes` | ADMIN | 7 | 0 | `/api/v1/disputes` | PASS | None |
| Admin Portal | `/admin/reconciliation` | ADMIN | 20 | 0 | `/api/v1/reconciliation/runs` | PARTIAL | DEFECT-003 |
| Admin Portal | `/admin/risk` | ADMIN | 8 | 0 | `/admin/risk/dashboard` | PASS | None |
| Admin Portal | `/admin/risk/alerts` | ADMIN | 10 | 0 | `/admin/risk/alerts` | PASS | None |
| Admin Portal | `/admin/risk/rules` | ADMIN | 11 | 1 | `/admin/risk/rules` | PASS | None |
| Admin Portal | `/admin/risk/reviews` | ADMIN | 10 | 0 | `/admin/risk/reviews` | PASS | None |
| Admin Portal | `/admin/risk/buyers` | ADMIN | 11 | 1 | `/admin/risk/buyers` | PASS | DEFECT-019 |
| Admin Portal | `/admin/risk/vendors` | ADMIN | 11 | 1 | `/admin/risk/vendors` | PASS | DEFECT-019 |
| Admin Portal | `/admin/organizations` | ADMIN | 19 | 0 | `/api/v1/admin/organizations` | PARTIAL | DEFECT-003 |
| Admin Portal | `/admin/users` | ADMIN | 12 | 0 | `/admin/users` | PASS | DEFECT-012 |
| Admin Portal | `/admin/verifications` | ADMIN | 12 | 0 | `/api/v1/admin/verifications` | PASS | None |
| Admin Portal | `/admin/invoices` | ADMIN | 10 | 0 | `/api/invoices` | PASS | None |
| Admin Portal | `/admin/audit` | ADMIN | 7 | 0 | `/admin/audit-logs` | PASS | None |
| Admin Portal | `/admin/audit-logs` | ADMIN | 7 | 0 | `/admin/audit-logs` | PASS | None |
| Admin Portal | `/admin/settings` | ADMIN | 14 | 0 | `/api/auth/me` | PASS | None |
| Finance Portal | `/finance` | FINANCE | 15 | 0 | `/api/finance/dashboard` | PARTIAL | DEFECT-004 |
| Finance Portal | `/finance/projects` | FINANCE | 7 | 0 | `/api/finance/projects` | PASS | None |
| Finance Portal | `/finance/payments` | FINANCE | 7 | 0 | `/api/finance/transactions` | PARTIAL | DEFECT-004 |
| Finance Portal | `/finance/transactions` | FINANCE | 15 | 0 | `/api/finance/transactions` | PARTIAL | DEFECT-004 |
| Finance Portal | `/finance/invoices` | FINANCE | 7 | 0 | `/api/invoices` | PASS | None |
| Finance Portal | `/finance/reconciliation` | FINANCE | 7 | 0 | `/api/finance/reconciliation` | PASS | None |
| Finance Portal | `/finance/reports` | FINANCE | 8 | 0 | `/api/finance/transactions` | PARTIAL | DEFECT-004 |
| Finance Portal | `/finance/settings` | FINANCE | 14 | 0 | `/api/auth/me` | PASS | None |

---

## Status Classification Summary

- **PASS (50 Routes):** Page loads, renders complete DOM layout, buttons respond, valid API connectivity, zero console errors.
- **PARTIAL (9 Routes):** Page loads and renders partial visual components, but encounters console errors or network failures due to mismatched endpoint names or duplicate `/api/api/` prefixes.
- **FAIL (1 Route):** Route returns HTTP 404 during direct navigation (`/_not-found`).
