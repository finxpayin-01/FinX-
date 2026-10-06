-- Migration V27: Bind Internal Financial Effect Provider Account Scope (R2.5)
-- Platform: B2B Milestone-based Fiat Escrow Platform
-- Scope: Add provider_account_id to payment_instructions and settlement_instructions for deterministic account-scope lineage

-- 1. Add provider_account_id to payment_instructions
ALTER TABLE payment_instructions
    ADD COLUMN IF NOT EXISTS provider_account_id VARCHAR(128);

ALTER TABLE payment_instructions
    ADD CONSTRAINT chk_pi_provider_account_id
    CHECK (provider_account_id IS NULL OR length(trim(provider_account_id)) > 0);

CREATE INDEX IF NOT EXISTS idx_payment_instructions_provider_account_created
    ON payment_instructions(provider, provider_account_id, created_at);

-- 2. Add provider_account_id to settlement_instructions
ALTER TABLE settlement_instructions
    ADD COLUMN IF NOT EXISTS provider_account_id VARCHAR(128);

ALTER TABLE settlement_instructions
    ADD CONSTRAINT chk_si_provider_account_id
    CHECK (provider_account_id IS NULL OR length(trim(provider_account_id)) > 0);

CREATE INDEX IF NOT EXISTS idx_settlement_instructions_provider_account_created
    ON settlement_instructions(provider, provider_account_id, created_at);
