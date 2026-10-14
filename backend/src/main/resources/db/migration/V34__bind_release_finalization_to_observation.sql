-- Migration V34: Bind Release Finalization To Trusted Settlement Observation
-- Platform: B2B Milestone-based Fiat Escrow Platform
-- Scope: E7 - Atomic Trusted Settlement Finalizer (Observation Binding & Auditability)

ALTER TABLE release_instructions
    ADD COLUMN IF NOT EXISTS final_settlement_observation_id UUID
        REFERENCES settlement_observations(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS finalized_by UUID
        REFERENCES users(id) ON DELETE RESTRICT;

-- Ensure an observation can finalize at most one release instruction
CREATE UNIQUE INDEX IF NOT EXISTS uix_release_instructions_final_observation
    ON release_instructions (final_settlement_observation_id)
    WHERE final_settlement_observation_id IS NOT NULL;

-- Index for lookup by finalized_by
CREATE INDEX IF NOT EXISTS idx_release_instructions_finalized_by
    ON release_instructions (finalized_by)
    WHERE finalized_by IS NOT NULL;

-- Consistency constraint:
-- For SETTLED rows: settled_at, final_settlement_observation_id, and finalized_by MUST all be non-null.
-- For non-SETTLED rows: final_settlement_observation_id MUST be null.
ALTER TABLE release_instructions
    ADD CONSTRAINT chk_release_instructions_settled_observation CHECK (
        (status = 'SETTLED' AND final_settlement_observation_id IS NOT NULL AND finalized_by IS NOT NULL) OR
        (status != 'SETTLED' AND final_settlement_observation_id IS NULL)
    );
