# FINX — Internal Double-Entry Ledger System

## 1. Accounting Principles & Invariants
FinX enforces strict double-entry bookkeeping across all financial movements. Financial state is NEVER tracked via simple scalar balance mutations (e.g., `balance = balance + amount`).

### Core Invariants
1. **Mathematical Equality**: In every journal entry, the sum of all DEBIT entries must exactly equal the sum of all CREDIT entries:
   $$\sum \text{Debit} = \sum \text{Credit}$$
2. **Numeric Precision**: Floating-point numbers are strictly forbidden. All monetary math uses Java `BigDecimal` and PostgreSQL `NUMERIC(19,4)`.
3. **Immutability**: Once written, journal entries and lines cannot be updated or deleted. Corrections are executed exclusively via compensating/reversal journal entries.
4. **Idempotency**: All postings require an immutable transaction reference (e.g., `FINX-JRN-2026-000001`) tied to a primary domain event.

---

## 2. Standard Chart of Accounts

| Account Code | Account Name | Type | Normal Balance | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `1010-ESCROW-POOL` | Escrow Pool Asset | `ASSET` | Debit | Aggregate customer funds held in the banking partner's escrow/nodal structure. |
| `1020-BANK-NODAL` | Bank Nodal Account | `ASSET` | Debit | Physical banking partner nodal account balance. |
| `2010-ESCROW-LIABILITY`| Escrow Deal Liability | `LIABILITY` | Credit | Obligation to counterparties for active deal milestones. |
| `2020-VENDOR-PAYABLE` | Vendor Settlement Payable| `LIABILITY` | Credit | Approved milestone funds cleared and queued for bank payout. |
| `4010-FINX-FEE-REVENUE`| FinX Fee Revenue | `REVENUE` | Credit | Platform technology and processing fee revenue. |

---

## 3. Transaction Posting Patterns

### A. Escrow Funding (Buyer Bank Transfer Confirmed)
When the partner bank confirms the buyer's virtual account deposit:
- **DEBIT**: `1010-ESCROW-POOL` (Asset increases)
- **CREDIT**: `2010-ESCROW-LIABILITY` (Liability increases)

### B. Milestone Settlement (Vendor Payout Disbursed)
When the buyer approves a milestone and all 10 settlement authorization gates clear:
- **DEBIT**: `2010-ESCROW-LIABILITY` (Liability decreased/relieved)
- **CREDIT**: `1010-ESCROW-POOL` (Asset decreased, paid out via bank UTR transfer)

### C. Buyer Refund
If a deal is cancelled or a dispute is resolved with a refund to the buyer:
- **DEBIT**: `2010-ESCROW-LIABILITY` (Liability relieved)
- **CREDIT**: `1010-ESCROW-POOL` (Asset decreased, returned to buyer source account)

---

## 4. Entity Schema

```sql
-- journal_entries
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    entry_reference VARCHAR(64) NOT NULL UNIQUE,
    transaction_type VARCHAR(64) NOT NULL,
    reference_id UUID,
    deal_id UUID,
    milestone_id UUID,
    total_debit NUMERIC(19,4) NOT NULL,
    total_credit NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    narration TEXT NOT NULL,
    posted_by UUID,
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- journal_lines
CREATE TABLE journal_lines (
    id UUID PRIMARY KEY,
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES ledger_accounts(id),
    entry_type VARCHAR(10) NOT NULL, -- 'DEBIT' or 'CREDIT'
    amount NUMERIC(19,4) NOT NULL,
    description VARCHAR(255),
    sequence_order INT NOT NULL
);
```
