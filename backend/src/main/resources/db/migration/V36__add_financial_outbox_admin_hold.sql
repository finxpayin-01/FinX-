-- V36__add_financial_outbox_admin_hold.sql
-- FinX Phase E11: Extend Financial Outbox with Administrative HELD Status
-- Allows pre-dispatch administrative hold without affecting idempotency key or attempt count.

ALTER TABLE financial_outbox DROP CONSTRAINT chk_financial_outbox_status;
ALTER TABLE financial_outbox ADD CONSTRAINT chk_financial_outbox_status 
    CHECK (status IN ('PENDING', 'LEASED', 'RETRY', 'HELD', 'PROCESSED', 'DEAD_LETTER'));

ALTER TABLE financial_outbox DROP CONSTRAINT chk_financial_outbox_lifecycle;
ALTER TABLE financial_outbox ADD CONSTRAINT chk_financial_outbox_lifecycle CHECK (
    (status = 'PENDING' AND locked_at IS NULL AND locked_by IS NULL AND processed_at IS NULL AND dead_lettered_at IS NULL)
    OR
    (status = 'RETRY' AND locked_at IS NULL AND locked_by IS NULL AND processed_at IS NULL AND dead_lettered_at IS NULL)
    OR
    (status = 'HELD' AND locked_at IS NULL AND locked_by IS NULL AND processed_at IS NULL AND dead_lettered_at IS NULL)
    OR
    (status = 'LEASED' AND locked_at IS NOT NULL AND locked_by IS NOT NULL AND processed_at IS NULL AND dead_lettered_at IS NULL)
    OR
    (status = 'PROCESSED' AND processed_at IS NOT NULL AND dead_lettered_at IS NULL)
    OR
    (status = 'DEAD_LETTER' AND dead_lettered_at IS NOT NULL AND processed_at IS NULL)
);
