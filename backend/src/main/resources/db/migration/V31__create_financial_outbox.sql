-- V31__create_financial_outbox.sql
-- FinX Phase E3: Transactional Financial Outbox Schema for Atomic Release Dispatch Enqueue
-- Durable local command queue for asynchronous release dispatch.
-- Strictly identifier-only: NO raw bank payloads, NO credentials, NO masked/encrypted account numbers.

CREATE TABLE financial_outbox (
    id UUID PRIMARY KEY,
    release_instruction_id UUID NOT NULL REFERENCES release_instructions(id) ON DELETE RESTRICT,
    event_type VARCHAR(64) NOT NULL,
    operation_key VARCHAR(128) NOT NULL,
    payload_version INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE,
    locked_by VARCHAR(128),
    last_error_code VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    dead_lettered_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,

    -- Operation and event uniqueness
    CONSTRAINT uk_financial_outbox_release_instruction_event UNIQUE (release_instruction_id, event_type),
    CONSTRAINT uk_financial_outbox_event_operation_key UNIQUE (event_type, operation_key),

    -- Semantic validation
    CONSTRAINT chk_financial_outbox_event_type CHECK (event_type = 'RELEASE_DISPATCH_REQUESTED'),
    CONSTRAINT chk_financial_outbox_operation_key CHECK (char_length(trim(operation_key)) > 0),
    CONSTRAINT chk_financial_outbox_payload_version CHECK (payload_version > 0),
    CONSTRAINT chk_financial_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_financial_outbox_status CHECK (status IN ('PENDING', 'LEASED', 'RETRY', 'PROCESSED', 'DEAD_LETTER')),

    -- Lifecycle consistency
    CONSTRAINT chk_financial_outbox_lifecycle CHECK (
        (status = 'PENDING' AND locked_at IS NULL AND locked_by IS NULL AND processed_at IS NULL AND dead_lettered_at IS NULL)
        OR
        (status = 'RETRY' AND locked_at IS NULL AND locked_by IS NULL AND processed_at IS NULL AND dead_lettered_at IS NULL)
        OR
        (status = 'LEASED' AND locked_at IS NOT NULL AND locked_by IS NOT NULL AND processed_at IS NULL AND dead_lettered_at IS NULL)
        OR
        (status = 'PROCESSED' AND processed_at IS NOT NULL AND dead_lettered_at IS NULL)
        OR
        (status = 'DEAD_LETTER' AND dead_lettered_at IS NOT NULL AND processed_at IS NULL)
    )
);

-- Indexes for worker leasing queries, instruction lookup, and lease timeouts
CREATE INDEX idx_financial_outbox_status_available_created ON financial_outbox (status, available_at, created_at);
CREATE INDEX idx_financial_outbox_release_instruction_id ON financial_outbox (release_instruction_id);
CREATE INDEX idx_financial_outbox_locked_at ON financial_outbox (locked_at);
