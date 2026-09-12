-- FINX Migration V5: Add and Backfill Automatic Unique User UIDs
-- Platform: B2B Milestone-based Fiat Escrow Platform

-- 1. Ensure uid column exists on users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS uid VARCHAR(20);

-- 2. Backfill existing users that have null or empty uid
-- Deterministically derive from user id UUID (first 8 hex digits uppercase)
UPDATE users
SET uid = 'USR-' || UPPER(SUBSTRING(REPLACE(CAST(id AS VARCHAR(36)), '-', ''), 1, 8))
WHERE uid IS NULL OR TRIM(uid) = '';

-- 3. Add UNIQUE constraint and index on uid
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_uid ON users(uid);

-- 4. Ensure project_id column exists on deals table
ALTER TABLE deals ADD COLUMN IF NOT EXISTS project_id VARCHAR(20);

-- 5. Backfill existing deals with null or empty project_id
UPDATE deals
SET project_id = 'PRJ-' || UPPER(SUBSTRING(REPLACE(CAST(id AS VARCHAR(36)), '-', ''), 1, 8))
WHERE project_id IS NULL OR TRIM(project_id) = '';

-- 6. Add UNIQUE constraint and index on deals.project_id
CREATE UNIQUE INDEX IF NOT EXISTS uk_deals_project_id ON deals(project_id);
