-- V33__create_settlement_observations.sql
-- FinX Phase E6: Trusted Settlement Observation Inbox & Provider Recovery
-- Durable immutable external settlement evidence tracking.
-- Strictly identifier & evidence-hash only: NO raw payloads, NO plaintext account numbers.

CREATE TABLE settlement_observations (
    id UUID PRIMARY KEY,
    release_instruction_id UUID NOT NULL REFERENCES release_instructions(id) ON DELETE RESTRICT,
    execution_attempt_id UUID NOT NULL REFERENCES settlement_execution_attempts(id) ON DELETE RESTRICT,
    webhook_event_id UUID REFERENCES webhook_events(id) ON DELETE RESTRICT,
    provider VARCHAR(50) NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_reference VARCHAR(255) NOT NULL,
    provider_operation_key VARCHAR(128) NOT NULL,
    provider_settlement_id VARCHAR(128),
    provider_account_id VARCHAR(128),
    provider_status VARCHAR(64) NOT NULL,
    finality VARCHAR(32) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    utr_number VARCHAR(128),
    observed_amount NUMERIC(19,4),
    observed_currency VARCHAR(3),
    observed_direction VARCHAR(10),
    provider_observed_at TIMESTAMP WITH TIME ZONE,
    evidence_hash VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Deduplication of identical provider evidence per attempt and source
    CONSTRAINT uk_settlement_observations_attempt_source_hash UNIQUE (execution_attempt_id, source, evidence_hash),

    -- Semantic integrity checks
    CONSTRAINT chk_settlement_obs_provider CHECK (char_length(trim(provider)) > 0),
    CONSTRAINT chk_settlement_obs_source CHECK (source IN ('WEBHOOK', 'STATUS_POLL', 'OPERATION_LOOKUP')),
    CONSTRAINT chk_settlement_obs_source_reference CHECK (char_length(trim(source_reference)) > 0),
    CONSTRAINT chk_settlement_obs_provider_op_key CHECK (char_length(trim(provider_operation_key)) > 0),
    CONSTRAINT chk_settlement_obs_provider_status CHECK (char_length(trim(provider_status)) > 0),
    CONSTRAINT chk_settlement_obs_finality CHECK (finality IN ('FINAL_SUCCESS', 'PENDING', 'FAILED', 'REVERSED', 'NOT_FOUND', 'UNKNOWN')),
    CONSTRAINT chk_settlement_obs_reason_code CHECK (char_length(trim(reason_code)) > 0),
    CONSTRAINT chk_settlement_obs_evidence_hash CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_settlement_obs_amount CHECK (observed_amount IS NULL OR observed_amount > 0),
    CONSTRAINT chk_settlement_obs_currency CHECK (observed_currency IS NULL OR observed_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_settlement_obs_direction CHECK (observed_direction IS NULL OR observed_direction = 'DEBIT'),

    -- FINAL_SUCCESS requires authoritative providerSettlementId and UTR
    CONSTRAINT chk_settlement_obs_final_success CHECK (
        finality != 'FINAL_SUCCESS'
        OR (
            provider_settlement_id IS NOT NULL AND char_length(trim(provider_settlement_id)) > 0
            AND utr_number IS NOT NULL AND char_length(trim(utr_number)) > 0
        )
    )
);

-- Partial unique index ensuring at most one settlement observation per webhook event
CREATE UNIQUE INDEX uk_settlement_observations_webhook_event
    ON settlement_observations (webhook_event_id)
    WHERE webhook_event_id IS NOT NULL;

-- Lookup indexes
CREATE INDEX idx_settlement_obs_release_instruction
    ON settlement_observations (release_instruction_id, observed_at DESC);

CREATE INDEX idx_settlement_obs_execution_attempt
    ON settlement_observations (execution_attempt_id, observed_at DESC);

CREATE INDEX idx_settlement_obs_provider_settlement
    ON settlement_observations (provider, provider_settlement_id);

CREATE INDEX idx_settlement_obs_provider_op_key
    ON settlement_observations (provider_operation_key);

CREATE INDEX idx_settlement_obs_finality_observed_at
    ON settlement_observations (finality, observed_at);

CREATE INDEX idx_settlement_obs_source_reference
    ON settlement_observations (source, source_reference);
