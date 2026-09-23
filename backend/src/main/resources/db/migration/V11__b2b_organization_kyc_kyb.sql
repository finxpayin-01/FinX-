CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    legal_name VARCHAR(255) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    registration_number VARCHAR(100),
    tax_id VARCHAR(50),
    gstin VARCHAR(50),
    registered_address TEXT,
    verification_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_org_tax_id ON organizations(tax_id);
CREATE INDEX idx_org_gstin ON organizations(gstin);

CREATE TABLE organization_members (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    invited_at TIMESTAMP WITH TIME ZONE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(organization_id, user_id)
);

CREATE INDEX idx_org_members_user ON organization_members(user_id);

CREATE TABLE organization_bank_accounts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    account_holder_name VARCHAR(255) NOT NULL,
    account_number_masked VARCHAR(50) NOT NULL,
    account_number_hash VARCHAR(128) NOT NULL,
    ifsc_code VARCHAR(20) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    branch_name VARCHAR(100),
    is_primary BOOLEAN NOT NULL DEFAULT false,
    verification_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED',
    verification_reference VARCHAR(100),
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_org_bank_acc_org ON organization_bank_accounts(organization_id);

CREATE TABLE kyc_profiles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    full_name VARCHAR(255) NOT NULL,
    pan_number_masked VARCHAR(50),
    status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED',
    id_document_type VARCHAR(50),
    id_document_ref VARCHAR(100),
    verification_reference VARCHAR(100),
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id)
);

CREATE TABLE kyb_profiles (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    business_pan_masked VARCHAR(50),
    gstin VARCHAR(50),
    cin_or_llpin VARCHAR(50),
    udyam_number VARCHAR(50),
    status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED',
    verification_reference VARCHAR(100),
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(organization_id)
);

CREATE TABLE verification_documents (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    document_type VARCHAR(50) NOT NULL,
    file_reference VARCHAR(255) NOT NULL,
    file_hash VARCHAR(128) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_verif_doc_entity ON verification_documents(entity_type, entity_id);
