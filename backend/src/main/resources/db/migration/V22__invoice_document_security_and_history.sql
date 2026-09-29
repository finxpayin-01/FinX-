-- Private bytes, append-only assessment evidence, explicit revision and payment requirements.
CREATE TABLE invoice_documents (
    invoice_id UUID PRIMARY KEY REFERENCES invoices(id),
    content BYTEA NOT NULL
);
CREATE TABLE invoice_assessments (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    fingerprint VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    policy_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_invoice_assessment_history ON invoice_assessments(invoice_id, created_at);
ALTER TABLE invoices ADD COLUMN revision_of UUID REFERENCES invoices(id);
ALTER TABLE invoices ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE invoice_reviews ADD COLUMN assessment_id UUID REFERENCES invoice_assessments(id);
CREATE INDEX idx_invoice_review_assessment ON invoice_reviews(assessment_id);
CREATE INDEX idx_invoice_revision ON invoices(revision_of);
CREATE INDEX idx_invoice_number_amount ON invoices(normalized_invoice_number, total_amount);
CREATE INDEX idx_invoice_gstin_number ON invoices(vendor_gstin, normalized_invoice_number);
ALTER TABLE milestones ADD COLUMN invoice_required BOOLEAN NOT NULL DEFAULT FALSE;
