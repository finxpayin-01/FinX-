-- V28__version_deterministic_reconciliation_runs.sql
-- Reconciliation R3: Version and provenance tracking for deterministic reconciliation runs and items.

-- 1. Add matcher_version and statement_source_hash to reconciliation_runs
ALTER TABLE reconciliation_runs
    ADD COLUMN IF NOT EXISTS matcher_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS statement_source_hash VARCHAR(64);

-- Add check constraint for non-blank matcher_version if present
ALTER TABLE reconciliation_runs
    ADD CONSTRAINT chk_reconciliation_runs_matcher_version
    CHECK (matcher_version IS NULL OR length(trim(matcher_version)) > 0);

-- Add check constraint for valid 64-hex statement_source_hash if present
ALTER TABLE reconciliation_runs
    ADD CONSTRAINT chk_reconciliation_runs_source_hash
    CHECK (statement_source_hash IS NULL OR statement_source_hash ~ '^[0-9a-f]{64}$');

-- Add index on (bank_statement_import_id, matcher_version, created_at)
CREATE INDEX IF NOT EXISTS idx_reconciliation_runs_import_matcher_created
    ON reconciliation_runs(bank_statement_import_id, matcher_version, created_at);

-- 2. Add reason_code to reconciliation_items
ALTER TABLE reconciliation_items
    ADD COLUMN IF NOT EXISTS reason_code VARCHAR(100);

-- Add check constraint for non-blank reason_code if present
ALTER TABLE reconciliation_items
    ADD CONSTRAINT chk_reconciliation_items_reason_code
    CHECK (reason_code IS NULL OR length(trim(reason_code)) > 0);

-- Add index on (reconciliation_run_id, reason_code)
CREATE INDEX IF NOT EXISTS idx_reconciliation_items_run_reason
    ON reconciliation_items(reconciliation_run_id, reason_code);
