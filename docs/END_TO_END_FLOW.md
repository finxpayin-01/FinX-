# FINX — End-to-End B2B Milestone Payment Flow

## 1. Scenario Summary
This walkthrough documents the full 18-step B2B milestone-payment lifecycle, verified deterministically in `BankIntegratedEndToEndTest.java`:
- Corporate Buyer: **Acme Buyer Corp** (Organization: *Acme Global*)
- Enterprise Vendor: **Apex Vendor Solutions** (Organization: *Apex Cloud Infra*)
- Deal Amount: **₹10,00,000 INR**
- Milestone 1: **₹4,00,000 INR** (Architecture & Ingestion)
- Milestone 2: **₹6,00,000 INR** (Deployment & Final Delivery)

---

## 2. Step-by-Step Flow

### Phase 1: Onboarding & Trust Verification
1. **Buyer & Vendor Registration**:
   - Both entities register on FinX with their respective corporate emails.
2. **Organization Creation**:
   - Buyer registers `Acme Global` (`PRIVATE_LIMITED`, PAN: `AAACA1234A`, GSTIN: `27AAACA1234A1Z5`).
   - Vendor registers `Apex Cloud Infra` (`PRIVATE_LIMITED`, PAN: `AAACV5678B`, GSTIN: `27AAACV5678B1Z2`).
3. **Corporate KYB Submission**:
   - Both organizations complete business KYB verification, validating corporate registry and tax records.
4. **Bank Account Registration & Penny-Drop Verification**:
   - Vendor registers corporate bank account (`HDFC0001234`, Account: `********1012`).
   - System triggers simulated penny-drop verification via `MockBankingProvider`.
   - Banking partner confirms name match score (98%) and returns verification reference `MOCK-PENNY-...`. Account is marked `VERIFIED`.

---

### Phase 2: Deal Creation, Invariant Check, & Mutual Agreement
5. **Deal Creation**:
   - Buyer creates deal: `₹10,00,000 INR`, generating human-readable reference `FINX-DL-2026-000001`.
6. **Milestone Configuration**:
   - Milestone 1: ₹4,00,000.
   - Milestone 2: ₹6,00,000.
   - Invariant enforced: $\sum \text{Milestones} = \text{Deal Total} = ₹10,00,000$.
7. **Counterparty Acceptance**:
   - Vendor accepts deal invitation.
8. **Deal Agreement Drafting & Dual Digital Signing**:
   - System generates draft agreement `v1` with cryptographic SHA-256 document hash.
   - Buyer authorized signatory reviews and signs contract with audit timestamp and IP.
   - Vendor authorized signatory reviews and signs contract. Status transitions to `SIGNED`. Deal transitions to `ACTIVE`.

---

### Phase 3: Bank Payment Instruction & Escrow Funding
9. **Generate Payment Instruction**:
   - Buyer requests payment instruction.
   - `MockBankingProvider` issues Virtual Account Number (`FINX...`) and IFSC (`FINX0001001`) with reference `FINX-TXN-2026-000001`.
10. **Bank Confirmation & Double-Entry Posting**:
    - Bank webhook confirms receipt of funds from buyer (simulated via `/simulate-confirmation`).
    - Internal Double-Entry Ledger posts balanced journal entry:
      - **DEBIT**: `1010-ESCROW-POOL` ₹10,00,000
      - **CREDIT**: `2010-ESCROW-LIABILITY` ₹10,00,000
    - Deal escrow account credited with ₹10,00,000. Deal marked `FUNDED`.

---

### Phase 4: Deliverable Execution, Review, & Settlement
11. **Milestone Work & Deliverable Submission**:
    - Vendor starts work (`IN_PROGRESS`), completes development, and uploads deliverables (`https://s3.finx.dev/...`). Milestone status: `UNDER_REVIEW`.
12. **Buyer Review & Approval**:
    - Buyer inspects deliverables and approves milestone (`APPROVED`).
13. **10-Gate Settlement Authorization**:
    - Buyer triggers payout authorization (`POST /api/v1/settlements/authorize`).
    - FinX runs the 10 settlement validation checks (Vendor KYB verified, bank verified, deal active, agreement signed, milestone approved, risk cleared, no dispute).
    - `BankingProvider.initiateSettlement(...)` issues UTR `UTR-MOCK-STL-...` to vendor bank account.
    - Balanced journal entry posted:
      - **DEBIT**: `2010-ESCROW-LIABILITY` ₹4,00,000
      - **CREDIT**: `1010-ESCROW-POOL` ₹4,00,000
    - Milestone marked `COMPLETED`. Vendor notified of disbursed settlement.

---

### Phase 5: Double-Disbursement Prevention & Automated Reconciliation
14. **Double-Payout Defense**:
    - Second attempt to settle the same milestone with a new idempotency key is immediately rejected with HTTP 400 Bad Request ("Milestone must be APPROVED... current status: COMPLETED").
15. **Automated Reconciliation**:
    - Admin triggers `POST /api/v1/reconciliation/trigger`.
    - Engine pulls bank statement transactions, compares against internal transaction references and amounts, and produces matched reconciliation report.
