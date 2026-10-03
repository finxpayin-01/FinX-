-- Migration V24: Harden Financial Source References and Ledger Consistency (B06)
-- Platform: B2B Milestone-based Fiat Escrow Platform

-- 1. Add bank PaymentInstruction FK column to escrow_ledger (additive, preserves legacy payment_id)
ALTER TABLE escrow_ledger
    ADD COLUMN IF NOT EXISTS payment_instruction_id UUID
    REFERENCES payment_instructions(id) ON DELETE SET NULL;

-- 2. Index for bank payment instruction in escrow_ledger
CREATE INDEX IF NOT EXISTS idx_escrow_ledger_payment_instruction_id
    ON escrow_ledger(payment_instruction_id);

-- 3. Confirmed bank event uniqueness: exactly one escrow projection per bank instruction
CREATE UNIQUE INDEX IF NOT EXISTS uk_escrow_ledger_payment_instruction_id
    ON escrow_ledger(payment_instruction_id)
    WHERE payment_instruction_id IS NOT NULL;

-- 4. Source exclusivity: an escrow ledger entry cannot have both legacy payment_id and bank payment_instruction_id
ALTER TABLE escrow_ledger
    ADD CONSTRAINT chk_escrow_ledger_source_exclusivity
    CHECK (NOT (payment_id IS NOT NULL AND payment_instruction_id IS NOT NULL));

-- 5. Fund source requirement: every FUND entry must have exactly one source (either legacy payment_id or bank payment_instruction_id)
ALTER TABLE escrow_ledger
    ADD CONSTRAINT chk_escrow_ledger_fund_source
    CHECK (transaction_type != 'FUND' OR (
        (payment_id IS NOT NULL AND payment_instruction_id IS NULL) OR
        (payment_id IS NULL AND payment_instruction_id IS NOT NULL)
    ));

-- 6. Journal source effect uniqueness: one financial source cannot post duplicate journals of the same transaction type
CREATE UNIQUE INDEX IF NOT EXISTS uk_journal_entries_type_reference
    ON journal_entries(transaction_type, reference_id)
    WHERE reference_id IS NOT NULL;
