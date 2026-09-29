-- V21: Invoice Matching and Evidence Verification Engine
-- Supports vendor invoice upload, field extraction, explainable checks, duplicate detection, and review decisions

CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL,
    milestone_id UUID NOT NULL,
    vendor_organization_id UUID NOT NULL,
    buyer_organization_id UUID NOT NULL,
    
    invoice_number VARCHAR(100) NOT NULL,
    normalized_invoice_number VARCHAR(100) NOT NULL,
    invoice_date DATE,
    due_date DATE,
    
    subtotal NUMERIC(15, 2),
    tax_amount NUMERIC(15, 2),
    total_amount NUMERIC(15, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    comparison_basis VARCHAR(20) NOT NULL DEFAULT 'TOTAL',
    
    vendor_legal_name VARCHAR(255),
    vendor_pan VARCHAR(20),
    vendor_gstin VARCHAR(50),
    
    buyer_legal_name VARCHAR(255),
    buyer_gstin VARCHAR(50),
    
    bank_account_last4 VARCHAR(10),
    bank_ifsc VARCHAR(20),
    
    file_name VARCHAR(255) NOT NULL,
    file_url TEXT NOT NULL,
    file_size_bytes BIGINT,
    mime_type VARCHAR(100),
    document_hash VARCHAR(64) NOT NULL,
    
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    extraction_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    matching_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    duplicate_confidence VARCHAR(50) NOT NULL DEFAULT 'UNIQUE',
    
    match_score INT,
    policy_version VARCHAR(50) NOT NULL DEFAULT 'v1.0.0',
    rejection_reason TEXT,
    
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_invoices_deal FOREIGN KEY (deal_id) REFERENCES deals(id),
    CONSTRAINT fk_invoices_milestone FOREIGN KEY (milestone_id) REFERENCES milestones(id)
);

CREATE INDEX idx_invoices_deal_id ON invoices(deal_id);
CREATE INDEX idx_invoices_milestone_id ON invoices(milestone_id);
CREATE INDEX idx_invoices_vendor_org ON invoices(vendor_organization_id);
CREATE INDEX idx_invoices_buyer_org ON invoices(buyer_organization_id);
CREATE INDEX idx_invoices_doc_hash ON invoices(document_hash);
CREATE INDEX idx_invoices_vendor_num ON invoices(vendor_organization_id, normalized_invoice_number);
CREATE INDEX idx_invoices_status ON invoices(status);
CREATE INDEX idx_invoices_created_at ON invoices(created_at);

CREATE TABLE invoice_extracted_fields (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    raw_value TEXT,
    normalized_value TEXT,
    confidence NUMERIC(5, 4) NOT NULL DEFAULT 1.0000,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_extracted_fields_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

CREATE INDEX idx_extracted_fields_invoice_id ON invoice_extracted_fields(invoice_id);

CREATE TABLE invoice_checks (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL,
    check_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    weight INT NOT NULL,
    score_earned INT NOT NULL,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_invoice_checks_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

CREATE INDEX idx_invoice_checks_invoice_id ON invoice_checks(invoice_id);
CREATE INDEX idx_invoice_checks_type ON invoice_checks(check_type);

CREATE TABLE invoice_reviews (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL,
    reviewer_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    decision VARCHAR(50),
    decision_reason VARCHAR(100),
    notes TEXT,
    evidence_references TEXT,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_invoice_reviews_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

CREATE INDEX idx_invoice_reviews_invoice_id ON invoice_reviews(invoice_id);
CREATE INDEX idx_invoice_reviews_status ON invoice_reviews(status);
