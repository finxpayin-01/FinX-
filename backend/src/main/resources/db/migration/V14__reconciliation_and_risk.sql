CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    run_reference VARCHAR(100) NOT NULL UNIQUE,
    provider VARCHAR(50) NOT NULL,
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE,
    total_internal_transactions INT NOT NULL DEFAULT 0,
    total_bank_transactions INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    mismatch_count INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL,
    triggered_by UUID REFERENCES users(id),
    summary_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_recon_runs_status ON reconciliation_runs(status);

CREATE TABLE reconciliation_items (
    id UUID PRIMARY KEY,
    reconciliation_run_id UUID NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    finx_transaction_reference VARCHAR(100),
    bank_transaction_reference VARCHAR(100),
    finx_amount NUMERIC(19,4),
    bank_amount NUMERIC(19,4),
    status VARCHAR(50) NOT NULL,
    discrepancy_details TEXT,
    resolution_status VARCHAR(50) NOT NULL DEFAULT 'UNRESOLVED',
    resolved_by UUID REFERENCES users(id),
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_recon_items_run ON reconciliation_items(reconciliation_run_id);
CREATE INDEX idx_recon_items_status ON reconciliation_items(status);

CREATE TABLE risk_assessments (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    risk_level VARCHAR(50) NOT NULL,
    risk_score INT NOT NULL,
    factors_summary TEXT,
    is_cleared BOOLEAN NOT NULL DEFAULT true,
    reviewed_by UUID REFERENCES users(id),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_risk_assessments_entity ON risk_assessments(entity_type, entity_id);
CREATE INDEX idx_risk_assessments_level ON risk_assessments(risk_level);

CREATE TABLE risk_signals (
    id UUID PRIMARY KEY,
    risk_assessment_id UUID NOT NULL REFERENCES risk_assessments(id) ON DELETE CASCADE,
    signal_type VARCHAR(100) NOT NULL,
    severity VARCHAR(50) NOT NULL,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_risk_signals_assessment ON risk_signals(risk_assessment_id);
