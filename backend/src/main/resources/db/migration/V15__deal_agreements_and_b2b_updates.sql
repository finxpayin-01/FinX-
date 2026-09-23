ALTER TABLE deals ADD COLUMN IF NOT EXISTS deal_reference VARCHAR(100);
ALTER TABLE deals ADD COLUMN IF NOT EXISTS buyer_organization_id UUID REFERENCES organizations(id);
ALTER TABLE deals ADD COLUMN IF NOT EXISTS seller_organization_id UUID REFERENCES organizations(id);
ALTER TABLE deals ADD COLUMN IF NOT EXISTS invitation_email VARCHAR(255);
ALTER TABLE deals ADD COLUMN IF NOT EXISTS invitation_phone VARCHAR(50);
ALTER TABLE deals ADD COLUMN IF NOT EXISTS invitation_gstin VARCHAR(50);
ALTER TABLE deals ADD COLUMN IF NOT EXISTS agreement_status VARCHAR(50) DEFAULT 'DRAFT';

CREATE UNIQUE INDEX IF NOT EXISTS idx_deals_deal_reference ON deals(deal_reference);
CREATE INDEX IF NOT EXISTS idx_deals_buyer_org ON deals(buyer_organization_id);
CREATE INDEX IF NOT EXISTS idx_deals_seller_org ON deals(seller_organization_id);

CREATE TABLE deal_agreements (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE CASCADE,
    version INT NOT NULL DEFAULT 1,
    title VARCHAR(255) NOT NULL,
    terms_content TEXT NOT NULL,
    document_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    buyer_accepted_at TIMESTAMP WITH TIME ZONE,
    buyer_accepted_by UUID REFERENCES users(id),
    buyer_signer_ip VARCHAR(50),
    seller_accepted_at TIMESTAMP WITH TIME ZONE,
    seller_accepted_by UUID REFERENCES users(id),
    seller_signer_ip VARCHAR(50),
    effective_date TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(deal_id, version)
);

CREATE INDEX idx_agreements_deal ON deal_agreements(deal_id);

CREATE TABLE agreement_audit_events (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES deal_agreements(id) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL,
    actor_id UUID REFERENCES users(id),
    ip_address VARCHAR(50),
    user_agent TEXT,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata_json TEXT
);

CREATE INDEX idx_agreement_audit_agreement ON agreement_audit_events(agreement_id);
