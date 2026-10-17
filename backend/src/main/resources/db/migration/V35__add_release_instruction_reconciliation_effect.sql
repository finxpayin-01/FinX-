-- V35__add_release_instruction_reconciliation_effect.sql
-- Expand reconciliation effect type constraint to support modern ReleaseInstruction outbound effects

ALTER TABLE reconciliation_items
    DROP CONSTRAINT chk_recon_items_internal_effect_type;

ALTER TABLE reconciliation_items
    ADD CONSTRAINT chk_recon_items_internal_effect_type
    CHECK (internal_effect_type IS NULL OR internal_effect_type IN ('PAYMENT_TRANSACTION', 'SETTLEMENT_INSTRUCTION', 'RELEASE_INSTRUCTION'));
