-- Migration V29: Create Release Instructions and Reservations Foundation
-- Platform: B2B Milestone-based Fiat Escrow Platform
-- Scope: Post-Engine Release Execution Foundation (Persistence & Invariants Only)

-- 1. Release Instructions Table (Durable execution intent bound to certified EngineDecision)
CREATE TABLE IF NOT EXISTS release_instructions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    milestone_id UUID NOT NULL REFERENCES milestones(id) ON DELETE RESTRICT,
    release_decision_id UUID NOT NULL REFERENCES engine_decisions(id) ON DELETE RESTRICT,
    release_decision_fingerprint VARCHAR(64) NOT NULL,
    policy_version_id UUID NOT NULL REFERENCES engine_policy_versions(id) ON DELETE RESTRICT,
    policy_content_hash VARCHAR(64) NOT NULL,
    beneficiary_bank_account_id UUID NOT NULL REFERENCES organization_bank_accounts(id) ON DELETE RESTRICT,
    provider VARCHAR(50) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    authorized_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    authorized_at TIMESTAMP WITH TIME ZONE,
    settled_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT chk_release_instructions_amount CHECK (amount > 0),
    CONSTRAINT chk_release_instructions_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_release_instructions_provider CHECK (length(trim(provider)) > 0),
    CONSTRAINT chk_release_instructions_provider_acc CHECK (length(trim(provider_account_id)) > 0),
    CONSTRAINT chk_release_instructions_idempotency CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT chk_release_instructions_release_fp CHECK (release_decision_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_release_instructions_policy_hash CHECK (policy_content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_release_instructions_request_hash CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_release_instructions_status CHECK (status IN (
        'CREATED', 'AUTHORIZED', 'RESERVED', 'QUEUED', 'DISPATCHING',
        'SUBMITTED', 'PENDING_SETTLEMENT', 'UNKNOWN_RECON_REQUIRED',
        'MANUAL_REVIEW', 'SETTLED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT chk_release_instructions_auth CHECK (
        (authorized_at IS NULL AND authorized_by IS NULL) OR
        (authorized_at IS NOT NULL AND authorized_by IS NOT NULL)
    ),
    CONSTRAINT chk_release_instructions_settled CHECK (
        (status = 'SETTLED' AND settled_at IS NOT NULL) OR
        (status != 'SETTLED' AND settled_at IS NULL)
    )
);

-- Release instruction unique indexes
CREATE UNIQUE INDEX IF NOT EXISTS uk_release_instructions_decision
    ON release_instructions (release_decision_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_instructions_org_idempotency
    ON release_instructions (organization_id, idempotency_key);

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_instructions_active_milestone
    ON release_instructions (milestone_id)
    WHERE status IN (
        'CREATED', 'AUTHORIZED', 'RESERVED', 'QUEUED', 'DISPATCHING',
        'SUBMITTED', 'PENDING_SETTLEMENT', 'UNKNOWN_RECON_REQUIRED',
        'MANUAL_REVIEW'
    );

-- Release instruction lookup indexes
CREATE INDEX IF NOT EXISTS idx_release_instructions_org_created
    ON release_instructions (organization_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_release_instructions_deal_created
    ON release_instructions (deal_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_release_instructions_milestone_created
    ON release_instructions (milestone_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_release_instructions_status_created
    ON release_instructions (status, created_at);

CREATE INDEX IF NOT EXISTS idx_release_instructions_provider_scope
    ON release_instructions (provider, provider_account_id, status);

CREATE INDEX IF NOT EXISTS idx_release_instructions_beneficiary
    ON release_instructions (beneficiary_bank_account_id);


-- 2. Release Reservations Table (Durable escrow balance reservation without balance deduction)
CREATE TABLE IF NOT EXISTS release_reservations (
    id UUID PRIMARY KEY,
    release_instruction_id UUID NOT NULL REFERENCES release_instructions(id) ON DELETE RESTRICT,
    escrow_account_id UUID NOT NULL REFERENCES escrow_accounts(id) ON DELETE RESTRICT,
    milestone_id UUID NOT NULL REFERENCES milestones(id) ON DELETE RESTRICT,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    consumed_at TIMESTAMP WITH TIME ZONE,
    released_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT chk_release_reservations_amount CHECK (amount > 0),
    CONSTRAINT chk_release_reservations_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_release_reservations_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED')),
    CONSTRAINT chk_release_reservations_lifecycle CHECK (
        (status = 'ACTIVE' AND consumed_at IS NULL AND released_at IS NULL) OR
        (status = 'CONSUMED' AND consumed_at IS NOT NULL AND released_at IS NULL) OR
        (status = 'RELEASED' AND released_at IS NOT NULL AND consumed_at IS NULL)
    )
);

-- Release reservation unique indexes
CREATE UNIQUE INDEX IF NOT EXISTS uk_release_reservations_instruction
    ON release_reservations (release_instruction_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_reservations_active_milestone
    ON release_reservations (milestone_id)
    WHERE status = 'ACTIVE';

-- Release reservation lookup indexes
CREATE INDEX IF NOT EXISTS idx_release_reservations_escrow_status
    ON release_reservations (escrow_account_id, status);

CREATE INDEX IF NOT EXISTS idx_release_reservations_milestone_status
    ON release_reservations (milestone_id, status);

CREATE INDEX IF NOT EXISTS idx_release_reservations_status_created
    ON release_reservations (status, created_at);
