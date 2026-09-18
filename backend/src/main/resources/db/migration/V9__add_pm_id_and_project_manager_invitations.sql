-- FINX Migration V8: Add PM ID and Project Manager Invitations
-- Platform: B2B Milestone-based Fiat Escrow Platform

-- 1. Add pm_id column to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS pm_id VARCHAR(30);

-- 2. Backfill existing users with role = 'PROJECT_MANAGER'
-- Deterministically derive from user id UUID (6 uppercase alphanumeric characters)
UPDATE users
SET pm_id = 'FINX-PM-' || UPPER(SUBSTRING(REPLACE(CAST(id AS VARCHAR(36)), '-', ''), 1, 6))
WHERE role = 'PROJECT_MANAGER' AND (pm_id IS NULL OR TRIM(pm_id) = '');

-- 3. Add UNIQUE index on pm_id
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_pm_id ON users(pm_id);

-- 4. Add project_manager_id to deals table
ALTER TABLE deals ADD COLUMN IF NOT EXISTS project_manager_id UUID;
CREATE INDEX IF NOT EXISTS idx_deals_project_manager_id ON deals(project_manager_id);

-- 5. Create project_manager_invitations table
CREATE TABLE IF NOT EXISTS project_manager_invitations (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL,
    project_manager_id UUID NOT NULL,
    invited_by UUID NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    invited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMP WITH TIME ZONE,
    declined_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    decline_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pmi_deal FOREIGN KEY (deal_id) REFERENCES deals(id) ON DELETE CASCADE,
    CONSTRAINT fk_pmi_pm FOREIGN KEY (project_manager_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_pmi_invited_by FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pmi_deal_id ON project_manager_invitations(deal_id);
CREATE INDEX IF NOT EXISTS idx_pmi_pm_id ON project_manager_invitations(project_manager_id);
CREATE INDEX IF NOT EXISTS idx_pmi_status ON project_manager_invitations(status);
