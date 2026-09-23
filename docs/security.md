# FINX — Security Controls & Risk Governance Architecture

## 1. Authentication & Authorization
- **JWT Architecture**: Stateless JWT with HMAC-SHA256 (`HS256`) signatures, validated on every API request.
- **Hierarchical Role-Based Access Control (RBAC)**:
  - Global Roles: `ADMIN`, `BUYER`, `SELLER`, `PROJECT_MANAGER`, `FINANCE`.
  - Organization Roles: `OWNER`, `ADMIN`, `FINANCE`, `PROJECT_MANAGER`, `AUTHORIZED_SIGNATORY`, `MEMBER`.
- **Multi-Tenant Isolation**: Database entity isolation enforcing ownership checks (`orgId`, `buyerId`, `sellerId`) on every resource query.

---

## 2. Sensitive Data & PII Protection
- **Masking at Rest & in Transit**:
  - Bank Account Numbers: Only masked numbers (`********1012`) and secure SHA-256 hashes (`account_number_hash`) are stored.
  - Tax & Identity Numbers: PANs and GSTINs are masked in public endpoints.
- **Log Hygiene**: Automated regex redaction in SLF4J / Logback configuration. Raw passwords, JWT tokens, bank credentials, and full identity numbers are strictly excluded from console and file logging.

---

## 3. Financial Settlement Gate (The 10 Payout Gates)
Before any milestone payout instruction is issued to the banking provider, `SettlementAuthorizationService` evaluates the following 10 mandatory controls:
1. **Seller Organization KYB Status**: Must be `VERIFIED`.
2. **Seller Beneficiary Bank Account**: Must exist, belong to the seller organization, and have `verification_status = VERIFIED` via penny-drop validation.
3. **Buyer Verification Requirements**: Buyer organization must be verified.
4. **Deal Status**: Must be `ACTIVE` or `FUNDED`.
5. **Agreement State**: Associated `DealAgreement` must have status `SIGNED` by both buyer and seller authorized signatories.
6. **Milestone Status**: Target milestone must be in `APPROVED` status with verified deliverables.
7. **Escrow Liquidity**: Sufficient funds must be confirmed and present in the deal escrow balance.
8. **Dispute Check**: No active `OPEN` or `UNDER_REVIEW` disputes on the deal or milestone.
9. **Risk Clearance**: Associated `RiskAssessment` must be in `CLEARED` state with risk level not exceeding `MEDIUM`.
10. **Duplicate Prevention**: No prior `SETTLED` instruction exists for this milestone. Replays of identical idempotency keys return existing instructions; new attempts are rejected.

---

## 4. Webhook & Replay Protection
- **Cryptographic Signatures**: Webhooks from banking providers are verified using HMAC signatures (`X-Bank-Signature` / `X-FinX-Signature`).
- **Timestamp Threshold**: Payloads older than 5 minutes (300,000 ms) are rejected.
- **Idempotency Keys**: Event UUIDs are stored in `processed_webhook_events`. Duplicates are acknowledged with HTTP 200 without triggering redundant state transitions.
