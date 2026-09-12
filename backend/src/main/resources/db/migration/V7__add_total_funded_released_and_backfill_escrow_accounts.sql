-- V7__add_total_funded_released_and_backfill_escrow_accounts.sql
-- 1. Add total_funded and total_released columns to escrow_accounts table
ALTER TABLE escrow_accounts ADD COLUMN IF NOT EXISTS total_funded NUMERIC(15, 2) NOT NULL DEFAULT 0.00;
ALTER TABLE escrow_accounts ADD COLUMN IF NOT EXISTS total_released NUMERIC(15, 2) NOT NULL DEFAULT 0.00;

-- 2. Backfill missing escrow accounts for every existing deal
INSERT INTO escrow_accounts (id, deal_id, balance, currency, status, version, created_at, updated_at, total_funded, total_released)
SELECT
    gen_random_uuid(),
    d.id,
    0.00,
    COALESCE(d.currency, 'INR'),
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0.00,
    0.00
FROM deals d
WHERE NOT EXISTS (
    SELECT 1 FROM escrow_accounts ea WHERE ea.deal_id = d.id
);

-- 3. Reconstruct total_funded and total_released from immutable ledger transactions
UPDATE escrow_accounts ea
SET total_funded = COALESCE((
    SELECT SUM(el.amount)
    FROM escrow_ledger el
    WHERE el.escrow_account_id = ea.id AND el.transaction_type = 'FUND'
), 0.00),
total_released = COALESCE((
    SELECT SUM(el.amount)
    FROM escrow_ledger el
    WHERE el.escrow_account_id = ea.id AND el.transaction_type = 'RELEASE'
), 0.00);

-- 4. Reconcile balance to strictly equal (total_funded - total_released)
UPDATE escrow_accounts
SET balance = total_funded - total_released
WHERE balance != (total_funded - total_released);
