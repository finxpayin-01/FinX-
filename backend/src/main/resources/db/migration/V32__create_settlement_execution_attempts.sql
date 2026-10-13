-- V32__create_settlement_execution_attempts.sql
-- FinX Phase E4: Settlement Execution Attempt Model & Worker Leasing Framework
-- Durable tracking of execution attempts with deterministic provider operation identity.
-- Strictly identifier-only: NO raw bank payloads, NO credentials, NO masked/encrypted account numbers.

CREATE TABLE settlement_execution_attempts (
    id UUID PRIMARY KEY,
    release_instruction_id UUID NOT NULL REFERENCES release_instructions(id) ON DELETE RESTRICT,
    outbox_message_id UUID NOT NULL REFERENCES financial_outbox(id) ON DELETE RESTRICT,
    attempt_number INTEGER NOT NULL,
    operation_key VARCHAR(128) NOT NULL,
    provider_operation_key VARCHAR(128) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    beneficiary_bank_account_id UUID NOT NULL REFERENCES organization_bank_accounts(id) ON DELETE RESTRICT,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    worker_id VARCHAR(128) NOT NULL,
    provider_settlement_id VARCHAR(128),
    provider_status VARCHAR(64),
    retryable BOOLEAN,
    error_code VARCHAR(100),
    request_started_at TIMESTAMP WITH TIME ZONE,
    response_received_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    -- Uniqueness per release instruction and outbox message
    CONSTRAINT uk_settlement_attempts_release_attempt UNIQUE (release_instruction_id, attempt_number),
    CONSTRAINT uk_settlement_attempts_outbox_attempt UNIQUE (outbox_message_id, attempt_number),

    -- Semantic integrity checks
    CONSTRAINT chk_settlement_attempts_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT chk_settlement_attempts_amount CHECK (amount > 0),
    CONSTRAINT chk_settlement_attempts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_settlement_attempts_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_settlement_attempts_operation_key CHECK (char_length(trim(operation_key)) > 0),
    CONSTRAINT chk_settlement_attempts_provider_op_key CHECK (char_length(trim(provider_operation_key)) > 0),
    CONSTRAINT chk_settlement_attempts_provider CHECK (char_length(trim(provider)) > 0),
    CONSTRAINT chk_settlement_attempts_provider_account CHECK (char_length(trim(provider_account_id)) > 0),
    CONSTRAINT chk_settlement_attempts_worker_id CHECK (char_length(trim(worker_id)) > 0),
    CONSTRAINT chk_settlement_attempts_status CHECK (status IN (
        'PREPARED', 'SENDING', 'ACKNOWLEDGED', 'TRANSPORT_FAILED', 'UNKNOWN', 'TERMINAL_FAILED'
    )),

    -- PREPARED lifecycle consistency: zero network or provider fields allowed
    CONSTRAINT chk_settlement_attempts_lifecycle CHECK (
        (status != 'PREPARED')
        OR
        (request_started_at IS NULL
         AND response_received_at IS NULL
         AND provider_settlement_id IS NULL
         AND provider_status IS NULL
         AND retryable IS NULL
         AND error_code IS NULL)
    )
);

-- Partial unique index preventing duplicate active attempts per outbox message
CREATE UNIQUE INDEX uk_settlement_attempts_active_outbox
    ON settlement_execution_attempts (outbox_message_id)
    WHERE status IN ('PREPARED', 'SENDING', 'ACKNOWLEDGED', 'UNKNOWN');

-- Scoped uniqueness for provider settlement identifiers per provider
CREATE UNIQUE INDEX uk_settlement_attempts_provider_settlement_id
    ON settlement_execution_attempts (provider, provider_settlement_id)
    WHERE provider_settlement_id IS NOT NULL;

-- Indexes for performance and lifecycle queries
CREATE INDEX idx_settlement_attempts_release_attempt
    ON settlement_execution_attempts (release_instruction_id, attempt_number DESC);

CREATE INDEX idx_settlement_attempts_outbox_attempt
    ON settlement_execution_attempts (outbox_message_id, attempt_number DESC);

CREATE INDEX idx_settlement_attempts_status_created
    ON settlement_execution_attempts (status, created_at);
