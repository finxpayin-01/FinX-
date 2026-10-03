-- Migration V23: Harden Financial Idempotency and Effect Identity (B05)
-- Platform: B2B Milestone-based Fiat Escrow Platform

-- 1. Add request_hash to payment_instructions
ALTER TABLE payment_instructions ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

-- 2. Drop global unique constraint on payment_instructions idempotency_key to allow scoped uniqueness
ALTER TABLE payment_instructions DROP CONSTRAINT IF EXISTS payment_instructions_idempotency_key_key;

-- 3. Create scoped unique index on payment_instructions (deal_id, idempotency_key)
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_instructions_deal_key
    ON payment_instructions (deal_id, idempotency_key);

-- 4. Add request_hash to settlement_instructions
ALTER TABLE settlement_instructions ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

-- 5. Drop global unique constraint on settlement_instructions idempotency_key to allow scoped uniqueness
ALTER TABLE settlement_instructions DROP CONSTRAINT IF EXISTS settlement_instructions_idempotency_key_key;

-- 6. Create scoped unique index on settlement_instructions (deal_id, idempotency_key)
CREATE UNIQUE INDEX IF NOT EXISTS uk_settlement_instructions_deal_key
    ON settlement_instructions (deal_id, idempotency_key);

-- 7. Business effect uniqueness: exactly one active or settled settlement attempt per milestone
CREATE UNIQUE INDEX IF NOT EXISTS uk_settlement_active_milestone
    ON settlement_instructions (milestone_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'SETTLED');

-- 8. Add provider column to payment_transactions for scoped provider credit identity
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS provider VARCHAR(50) NOT NULL DEFAULT 'BANK';

-- 9. Scoped provider credit identity: one bank/provider credit (UTR) usable only once per provider
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_tx_provider_utr
    ON payment_transactions (provider, utr_number);

-- 10. Scoped provider credit identity for settlement instructions
CREATE UNIQUE INDEX IF NOT EXISTS uk_settlement_provider_utr
    ON settlement_instructions (provider, utr_number);
