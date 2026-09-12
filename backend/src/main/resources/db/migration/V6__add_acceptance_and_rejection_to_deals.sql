-- FINX Migration V6: Add Acceptance and Rejection Columns to Deals
-- Platform: B2B Milestone-based Fiat Escrow Platform

ALTER TABLE deals ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE deals ADD COLUMN IF NOT EXISTS accepted_by UUID;
ALTER TABLE deals ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE deals ADD COLUMN IF NOT EXISTS rejected_by UUID;
ALTER TABLE deals ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
