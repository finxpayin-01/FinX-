-- Migration V26: Durable Bank Statement Reconciliation Schema (R1)
-- Platform: B2B Milestone-based Fiat Escrow Platform
-- Scope: Durable bank statement evidence infrastructure (Imports, Lines, and Additive Reconciliation Extensions)

-- 1. Create Bank Statement Imports Table
CREATE TABLE IF NOT EXISTS bank_statement_imports (
    id UUID PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    opening_balance NUMERIC(19, 4),
    closing_balance NUMERIC(19, 4),
    currency VARCHAR(3) NOT NULL,
    total_lines INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    imported_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    provider_import_reference VARCHAR(128),

    CONSTRAINT chk_bsi_window CHECK (window_end >= window_start),
    CONSTRAINT chk_bsi_source_hash CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_bsi_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_bsi_total_lines CHECK (total_lines >= 0),
    CONSTRAINT chk_bsi_status CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Unique scoped idempotency: exact same statement cannot be re-imported under the same provider account
CREATE UNIQUE INDEX IF NOT EXISTS uk_bsi_provider_account_hash
    ON bank_statement_imports (provider, provider_account_id, source_hash);

-- Search index for window queries
CREATE INDEX IF NOT EXISTS idx_bsi_provider_window
    ON bank_statement_imports (provider, provider_account_id, window_start, window_end);

CREATE INDEX IF NOT EXISTS idx_bsi_status
    ON bank_statement_imports (status);


-- 2. Create Bank Statement Lines Table (Immutable Financial Evidence)
CREATE TABLE IF NOT EXISTS bank_statement_lines (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES bank_statement_imports(id) ON DELETE RESTRICT,
    provider VARCHAR(64) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    provider_line_id VARCHAR(128),
    transaction_reference VARCHAR(128),
    utr_number VARCHAR(128),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    provider_status VARCHAR(64),
    normalized_status VARCHAR(32) NOT NULL,
    transaction_time TIMESTAMP WITH TIME ZONE NOT NULL,
    value_date DATE,
    line_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_bsl_amount CHECK (amount > 0),
    CONSTRAINT chk_bsl_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_bsl_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_bsl_normalized_status CHECK (normalized_status IN ('FINAL_SUCCESS', 'PENDING', 'FAILED', 'REVERSED', 'UNKNOWN')),
    CONSTRAINT chk_bsl_fingerprint CHECK (line_fingerprint ~ '^[0-9a-f]{64}$')
);

-- Unique index for provider-assigned line IDs (when supplied)
CREATE UNIQUE INDEX IF NOT EXISTS uk_bsl_provider_line
    ON bank_statement_lines (provider, provider_account_id, provider_line_id)
    WHERE provider_line_id IS NOT NULL;

-- Unique index for canonical line fingerprint
CREATE UNIQUE INDEX IF NOT EXISTS uk_bsl_provider_fingerprint
    ON bank_statement_lines (provider, provider_account_id, line_fingerprint);

-- Lookup and audit indexes
CREATE INDEX IF NOT EXISTS idx_bsl_import_time
    ON bank_statement_lines (import_id, transaction_time);

CREATE INDEX IF NOT EXISTS idx_bsl_lookup
    ON bank_statement_lines (provider, provider_account_id, direction, transaction_time);

CREATE INDEX IF NOT EXISTS idx_bsl_utr
    ON bank_statement_lines (provider, provider_account_id, utr_number)
    WHERE utr_number IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bsl_tx_ref
    ON bank_statement_lines (provider, provider_account_id, transaction_reference)
    WHERE transaction_reference IS NOT NULL;


-- 3. Extend reconciliation_runs additively (nullable columns for backward compatibility)
ALTER TABLE reconciliation_runs
    ADD COLUMN IF NOT EXISTS provider_account_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS window_start TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS window_end TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS bank_statement_import_id UUID REFERENCES bank_statement_imports(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS opening_balance NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS closing_balance NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS currency VARCHAR(3);

ALTER TABLE reconciliation_runs
    ADD CONSTRAINT chk_recon_runs_window CHECK (window_start IS NULL OR window_end IS NULL OR window_end >= window_start),
    ADD CONSTRAINT chk_recon_runs_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$');

CREATE INDEX IF NOT EXISTS idx_recon_runs_import
    ON reconciliation_runs (bank_statement_import_id);


-- 4. Extend reconciliation_items additively (nullable columns for backward compatibility)
ALTER TABLE reconciliation_items
    ADD COLUMN IF NOT EXISTS bank_statement_line_id UUID REFERENCES bank_statement_lines(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS internal_effect_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS internal_effect_id UUID,
    ADD COLUMN IF NOT EXISTS currency VARCHAR(3),
    ADD COLUMN IF NOT EXISTS direction VARCHAR(16),
    ADD COLUMN IF NOT EXISTS bank_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS resolution_notes TEXT;

ALTER TABLE reconciliation_items
    ADD CONSTRAINT chk_recon_items_internal_effect_type CHECK (internal_effect_type IS NULL OR internal_effect_type IN ('PAYMENT_TRANSACTION', 'SETTLEMENT_INSTRUCTION')),
    ADD CONSTRAINT chk_recon_items_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT chk_recon_items_direction CHECK (direction IS NULL OR direction IN ('CREDIT', 'DEBIT'));

-- Partial unique indexes for 1-to-1 match support in a run
CREATE UNIQUE INDEX IF NOT EXISTS uk_recon_items_run_statement_line
    ON reconciliation_items (reconciliation_run_id, bank_statement_line_id)
    WHERE bank_statement_line_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_recon_items_run_internal_effect
    ON reconciliation_items (reconciliation_run_id, internal_effect_type, internal_effect_id)
    WHERE internal_effect_type IS NOT NULL AND internal_effect_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_recon_items_statement_line
    ON reconciliation_items (bank_statement_line_id);

CREATE INDEX IF NOT EXISTS idx_recon_items_internal_effect
    ON reconciliation_items (internal_effect_type, internal_effect_id);
