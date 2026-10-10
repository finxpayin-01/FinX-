-- Migration V30: Create Milestone Approval Bindings Foundation
-- Platform: B2B Milestone-based Fiat Escrow Platform
-- Scope: Durable Milestone Approval Evidence (Immutable Binding)

CREATE TABLE IF NOT EXISTS milestone_approval_bindings (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    milestone_id UUID NOT NULL REFERENCES milestones(id) ON DELETE RESTRICT,
    approved_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    approval_authority VARCHAR(30) NOT NULL,
    milestone_fingerprint VARCHAR(64) NOT NULL,
    deliverables_fingerprint VARCHAR(64) NOT NULL,
    approval_fingerprint VARCHAR(64) NOT NULL,
    project_manager_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_milestone_approval_bindings_milestone UNIQUE (milestone_id),
    CONSTRAINT chk_mab_authority CHECK (approval_authority IN ('BUYER', 'PROJECT_MANAGER', 'ADMIN')),
    CONSTRAINT chk_mab_milestone_fp CHECK (milestone_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_mab_deliverables_fp CHECK (deliverables_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_mab_approval_fp CHECK (approval_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_mab_org_approved_at
    ON milestone_approval_bindings (organization_id, approved_at DESC);

CREATE INDEX IF NOT EXISTS idx_mab_deal_approved_at
    ON milestone_approval_bindings (deal_id, approved_at DESC);

CREATE INDEX IF NOT EXISTS idx_mab_milestone_id
    ON milestone_approval_bindings (milestone_id);

CREATE INDEX IF NOT EXISTS idx_mab_approved_by
    ON milestone_approval_bindings (approved_by, approved_at DESC);
