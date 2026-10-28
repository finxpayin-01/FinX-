# FinX Manual UI Test Checklist

This checklist is to be executed in the manual testing stage following Playwright automation.
**DO NOT PERFORM THE MANUAL TEST YET.**
Leave `Manual Result` blank.

| ID | Role | Page | Action | Expected Result | Manual Result | Notes |
|---|---|---|---|---|---|---|
| TC-MAN-001 | Unauthenticated | `/` | Open public landing page | Hero, features, navigation, and 'Get Started' button render crisply | | Baseline landing verification |
| TC-MAN-002 | Unauthenticated | `/login` | Submit empty form | Form shows required field validation errors for email and password | | Client form validation |
| TC-MAN-003 | Unauthenticated | `/login` | Enter valid BUYER credentials and submit | Redirects to `/corporate` with active session and personalized greeting | | Authentication flow |
| TC-MAN-004 | Unauthenticated | `/login` | Enter wrong password | Displays inline error message 'Invalid email or password' | | Security error handling |
| TC-MAN-005 | Unauthenticated | `/register` | View registration form | Company name, business email, password, and role selector are visible | | Registration view |
| TC-MAN-006 | Unauthenticated | `/forgot-password` | Submit password reset request | Confirmation message displayed with email delivery instructions | | Self-service recovery |
| TC-MAN-007 | Unauthenticated | `/contact` | View contact page | Support contact details and inquiry form render | | Informational route |
| TC-MAN-008 | Unauthenticated | `/blockchain` | View blockchain explorer | Shows block height, ledger hashes, and verification integrity status | | Auditability verification |
| TC-MAN-009 | BUYER | `/corporate` | View corporate dashboard | Displays active deals count, escrow balance, and pending actions | | Buyer main dashboard |
| TC-MAN-010 | BUYER | `/corporate/projects` | Click 'Create New Deal' button | Opens deal creation modal with escrow terms and vendor selector | | Deal flow initiation |
| TC-MAN-011 | BUYER | `/corporate/projects` | Enter deal with negative amount | Form displays validation error 'Amount must be greater than zero' | | Input validation |
| TC-MAN-012 | BUYER | `/corporate/milestones` | View milestone approval queue | Displays milestones awaiting funding or buyer release approval | | Milestone progression |
| TC-MAN-013 | BUYER | `/corporate/milestones` | Click 'Approve Milestone' | Triggers approval confirmation modal with engine conditions check | | Milestone approval |
| TC-MAN-014 | BUYER | `/corporate/escrow` | View escrow balance card | Shows total funds deposited, held in trust, and settled | | Escrow visualization |
| TC-MAN-015 | BUYER | `/corporate/payments` | View payment history | Shows transaction history table with dates, IDs, and statuses | | Payment history |
| TC-MAN-016 | BUYER | `/corporate/disputes` | Click 'Raise Dispute' button | Opens dispute form with reason dropdown and evidence attachment | | Dispute initiation |
| TC-MAN-017 | BUYER | `/corporate/settings` | Update company profile details | Successfully updates profile and displays success notification | | Buyer settings |
| TC-MAN-018 | BUYER | `/admin` | Direct URL navigation to `/admin` | Denied access or redirected back to `/corporate` | | RBAC boundary |
| TC-MAN-019 | SELLER | `/vendor` | View vendor dashboard | Displays pending deliverables, earned balances, and active contracts | | Seller main dashboard |
| TC-MAN-020 | SELLER | `/vendor/projects` | View assigned projects | Shows deals where seller is designated vendor beneficiary | | Project assignment |
| TC-MAN-021 | SELLER | `/vendor/milestones` | View milestone list | Displays current milestones with deliverables status | | Milestone status |
| TC-MAN-022 | SELLER | `/vendor/deliverables` | Click 'Submit Deliverable' | Opens deliverable submission form with file upload field | | Work evidence submission |
| TC-MAN-023 | SELLER | `/vendor/deliverables` | Upload sample PDF work document | Upload completes and displays document thumbnail/metadata | | Document attachment |
| TC-MAN-024 | SELLER | `/vendor/escrow` | View vendor escrow breakdown | Displays funded vs. pending payout balances accurately | | Balance audit |
| TC-MAN-025 | SELLER | `/vendor/payments` | View payouts history | Shows completed settlement payouts with transaction IDs | | Payout audit |
| TC-MAN-026 | SELLER | `/vendor/settings` | View bank account settings | Displays linked bank account details in masked/secure format | | Bank account UI |
| TC-MAN-027 | SELLER | `/finance` | Direct URL navigation to `/finance` | Denied access or redirected to `/vendor` | | RBAC boundary |
| TC-MAN-028 | PROJECT_MANAGER | `/project-manager` | View PM dashboard | Displays cross-project milestone health and review tasks | | PM main dashboard |
| TC-MAN-029 | PROJECT_MANAGER | `/project-manager/projects` | View managed project details | Shows timeline, milestone progress bars, and assigned parties | | PM project tracking |
| TC-MAN-030 | PROJECT_MANAGER | `/project-manager/deliverables` | Review submitted vendor deliverable | Preview deliverable document and view inspection controls | | Evidence inspection |
| TC-MAN-031 | PROJECT_MANAGER | `/project-manager/reviews` | Complete milestone review | Submits review feedback (Approve / Request Revisions) | | Review submission |
| TC-MAN-032 | PROJECT_MANAGER | `/project-manager/reports` | Export project status report | Triggers export/download of progress summary | | PM reporting |
| TC-MAN-033 | PROJECT_MANAGER | `/project-manager/invitations` | Send stakeholder project invitation | Generates secure email invitation link | | Collaboration |
| TC-MAN-034 | FINANCE | `/finance` | View finance overview | Displays total settled volume, nodal pool, and pending batches | | Finance overview |
| TC-MAN-035 | FINANCE | `/finance/transactions` | Filter transaction ledger by date range | Ledger table updates dynamically with filtered transactions | | Ledger filtering |
| TC-MAN-036 | FINANCE | `/finance/invoices` | View invoice list | Displays invoices with status badges (Draft, Issued, Paid) | | Invoice management |
| TC-MAN-037 | FINANCE | `/finance/payments` | View settlement batch queue | Shows release instructions in RESERVED / DISPATCHING / SETTLED state | | Settlement tracking |
| TC-MAN-038 | FINANCE | `/finance/reconciliation` | Inspect reconciliation run details | Shows matched vs. break entries with detailed variance comparison | | Reconciliation UI |
| TC-MAN-039 | FINANCE | `/finance/reports` | View liquidity & nodal balance report | Shows chart of accounts breakdown (1010-ESCROW, 1020-NODAL) | | Accounting integrity |
| TC-MAN-040 | FINANCE | `/finance/settings` | View settlement schedule settings | Displays batch dispatch window and payout limits | | Finance settings |
| TC-MAN-041 | ADMIN | `/admin` | View system admin overview | Shows platform health, active users, deals, and risk alerts | | Admin overview |
| TC-MAN-042 | ADMIN | `/admin/users` | View user management table | Lists all users with roles, statuses, and action dropdowns | | User administration |
| TC-MAN-043 | ADMIN | `/admin/organizations` | View organizations directory | Lists verified and pending organizations | | Org administration |
| TC-MAN-044 | ADMIN | `/admin/verifications` | Review pending KYC/KYB submission | Displays submitted corporate docs and verify/reject buttons | | Compliance review |
| TC-MAN-045 | ADMIN | `/admin/risk` | View risk engine overview | Displays risk scores, risk band distributions, and active rules | | Risk oversight |
| TC-MAN-046 | ADMIN | `/admin/risk/alerts` | Inspect high-risk alert details | Shows triggering factors, anomaly score, and recommended action | | Risk mitigation |
| TC-MAN-047 | ADMIN | `/admin/risk/rules` | Toggle risk rule threshold | Shows rule configuration and sensitivity parameters | | Rule management |
| TC-MAN-048 | ADMIN | `/admin/disputes` | Review escalated dispute | Displays dispute history, evidence from both parties, and mediation | | Dispute mediation |
| TC-MAN-049 | ADMIN | `/admin/settlements` | View mock bank dispatch monitor | Shows mock settlement confirmations and outbox worker state | | Settlement monitor |
| TC-MAN-050 | ADMIN | `/admin/audit-logs` | Filter audit logs by actor and action | Shows tamper-evident audit entries with timestamps and SHA hashes | | Audit trail |
| TC-MAN-051 | ADMIN | `/admin/settings` | View system configuration | Confirms BANK_PROVIDER=mock and production payout disabled | | Safety verification |
| TC-MAN-052 | Any | Any form | Submit form by pressing 'Enter' key | Form submits cleanly without unexpected page refresh | | Keyboard ergonomics |
| TC-MAN-053 | Any | Any modal | Press 'Escape' key | Modal dismisses smoothly without freezing UI | | Modal accessibility |
| TC-MAN-054 | Any | Mobile View (390px) | Open mobile hamburger navigation drawer | Drawer opens smoothly and all navigation links are tap-friendly | | Mobile navigation |
| TC-MAN-055 | Any | All Pages | Observe session persistence | Refreshing browser retains login state without dropping to /login | | Session management |
| TC-MAN-056 | Any | All Dashboards | Click 'Log out' button | Clears session, invalidates tokens, and redirects cleanly to /login | | Logout flow |
