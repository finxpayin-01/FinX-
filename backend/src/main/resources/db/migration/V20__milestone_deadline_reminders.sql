-- FINX Migration V18: Milestone Submission Deadlines, Reminders & Escalations
-- Platform: B2B Milestone-based Fiat Escrow Platform

-- 1. Add submission_deadline to milestones
ALTER TABLE milestones ADD COLUMN IF NOT EXISTS submission_deadline TIMESTAMP WITH TIME ZONE;
CREATE INDEX IF NOT EXISTS idx_milestones_submission_deadline ON milestones(submission_deadline);

-- Backfill submission_deadline from due_date for existing milestones where null
UPDATE milestones SET submission_deadline = due_date WHERE submission_deadline IS NULL AND due_date IS NOT NULL;

-- 2. Create milestone_reminders table for persistent, database-backed scheduling
CREATE TABLE IF NOT EXISTS milestone_reminders (
    id UUID PRIMARY KEY,
    milestone_id UUID NOT NULL,
    deal_id UUID NOT NULL,
    recipient_id UUID NOT NULL,
    reminder_type VARCHAR(50) NOT NULL,
    scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    channel VARCHAR(50) NOT NULL DEFAULT 'IN_APP',
    failure_reason TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    deadline_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reminders_milestone FOREIGN KEY (milestone_id) REFERENCES milestones(id) ON DELETE CASCADE,
    CONSTRAINT fk_reminders_deal FOREIGN KEY (deal_id) REFERENCES deals(id) ON DELETE CASCADE,
    CONSTRAINT fk_reminders_recipient FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_milestone_recipient_type UNIQUE (milestone_id, recipient_id, reminder_type, deadline_version)
);

CREATE INDEX IF NOT EXISTS idx_reminders_due ON milestone_reminders(status, scheduled_for);
CREATE INDEX IF NOT EXISTS idx_reminders_milestone_status ON milestone_reminders(milestone_id, status);
CREATE INDEX IF NOT EXISTS idx_reminders_recipient ON milestone_reminders(recipient_id);
