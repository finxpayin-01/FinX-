# FINX — KYC & KYB Trust Verification Layer

## 1. Overview
In a B2B transaction environment, both parties must be rigorously verified before agreements can be executed or money can be moved. FinX establishes a dual-tier verification architecture:
1. **Corporate KYB (Know Your Business)**: Verifies the legal business entity, tax registration, entity type, and registered office.
2. **Individual KYC (Know Your Customer)**: Verifies authorized signatories, directors, or representatives executing transactions on behalf of the company.

---

## 2. Business KYB Workflow

### Entity Types Supported
- `PROPRIETORSHIP`
- `PARTNERSHIP`
- `LLP` (Limited Liability Partnership)
- `PRIVATE_LIMITED`
- `PUBLIC_LIMITED`
- `OTHER`

### Verification Data Collected & Validated
- Legal Registered Name
- Entity Structure
- Business Permanent Account Number (PAN)
- Goods and Services Tax Identification Number (GSTIN)
- Corporate Identification Number (CIN) or LLPIN
- Registered Office Address
- Authorized Signatory Details

### KYB Lifecycle States
```
NOT_STARTED
    │
    ▼
IN_PROGRESS ──────────────► PENDING_REVIEW
    │                               │
    ▼                               ▼
FAILED / REJECTED ◄──────────── VERIFIED
                                    │
                                    ▼
                                 EXPIRED
```

---

## 3. Bank Account & Penny-Drop Verification

Counterparties register corporate bank accounts under their organization:
- `OrganizationBankAccount` stores `account_number_masked` (e.g., `********1012`) and `account_number_hash` for deduplication. Plaintext account numbers are never exposed via APIs.
- **Penny-Drop Verification**: The system initiates an automated verification API call (`POST /api/v1/bank-accounts/{id}/verify-penny-drop`). The banking partner deposits a nominal ₹1 amount, confirms account holder name against the registered corporate entity name, and returns a verified UTR / reference.
- Only accounts with `verification_status = VERIFIED` are eligible to receive milestone settlement payouts.

---

## 4. Verified Business Network Metrics

To foster inter-enterprise trust without resorting to black-box arbitrary credit scoring, FinX maintains transparent, platform-derived metrics:
- `completed_deals_count`: Historical count of fully executed deals.
- `completed_milestones_count`: Number of successfully approved and settled milestones.
- `dispute_count`: Number of formal disputes raised or resolved.
- `trust_score`: Normalized score (0–100) reflecting counterparty dispute rates and verification completeness.
