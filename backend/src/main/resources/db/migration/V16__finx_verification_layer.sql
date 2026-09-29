-- V16__finx_verification_layer.sql
-- Production Verification Architecture for KYC and KYB

-- 1. Extend kyc_profiles table
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS provider VARCHAR(50);
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS provider_reference_id VARCHAR(100);
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS pan_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS identity_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS name_match_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS name_match_score NUMERIC(5,2);
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS date_of_birth_match_status VARCHAR(50);
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS verification_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE kyc_profiles ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE;

-- 2. Extend kyb_profiles table
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS provider VARCHAR(50);
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS provider_reference_id VARCHAR(100);
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS trade_name VARCHAR(255);
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS incorporation_date DATE;
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS pan_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS gst_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS registration_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS business_status VARCHAR(50);
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS address_status VARCHAR(50);
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS name_match_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS name_match_score NUMERIC(5,2);
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS verification_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE kyb_profiles ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE;

-- 3. Extend organization_bank_accounts table
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS encrypted_account_number TEXT;
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS account_type VARCHAR(50) NOT NULL DEFAULT 'CURRENT';
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS provider VARCHAR(50);
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS provider_reference_id VARCHAR(100);
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS name_match_score NUMERIC(5,2);
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS name_match_status VARCHAR(50);
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS name_match_reason TEXT;
ALTER TABLE organization_bank_accounts ADD COLUMN IF NOT EXISTS registered_name_returned VARCHAR(255);

-- 4. Authorized Representatives table
CREATE TABLE IF NOT EXISTS authorized_representatives (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    relationship_type VARCHAR(50) NOT NULL,
    authority_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    authorization_document_ref VARCHAR(255),
    verified_at TIMESTAMP WITH TIME ZONE,
    verified_by UUID REFERENCES users(id),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_org_user_rel UNIQUE(organization_id, user_id, relationship_type)
);

CREATE INDEX IF NOT EXISTS idx_auth_rep_org ON authorized_representatives(organization_id);
CREATE INDEX IF NOT EXISTS idx_auth_rep_user ON authorized_representatives(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_rep_status ON authorized_representatives(authority_status);

-- 5. Beneficial Owners table
CREATE TABLE IF NOT EXISTS beneficial_owners (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    person_name VARCHAR(255) NOT NULL,
    ownership_percentage NUMERIC(5,2) NOT NULL,
    relationship_type VARCHAR(50) NOT NULL,
    pan_masked VARCHAR(50),
    verification_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    provider_reference VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_beneficial_owners_org ON beneficial_owners(organization_id);

-- 6. Verification Consents table
CREATE TABLE IF NOT EXISTS verification_consents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    verification_type VARCHAR(50) NOT NULL,
    consent_version VARCHAR(20) NOT NULL,
    purpose TEXT NOT NULL,
    consented_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(50),
    user_agent TEXT
);

CREATE INDEX IF NOT EXISTS idx_verif_consent_user ON verification_consents(user_id);
CREATE INDEX IF NOT EXISTS idx_verif_consent_org ON verification_consents(organization_id);

-- 7. Append-only Verification Events table
CREATE TABLE IF NOT EXISTS verification_events (
    id UUID PRIMARY KEY,
    subject_type VARCHAR(50) NOT NULL,
    subject_id UUID NOT NULL,
    verification_type VARCHAR(50) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_reference VARCHAR(100),
    old_status VARCHAR(50),
    new_status VARCHAR(50) NOT NULL,
    reason TEXT,
    actor_id UUID REFERENCES users(id),
    safe_metadata_json TEXT,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_verif_event_subject ON verification_events(subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_verif_event_type ON verification_events(verification_type);
CREATE INDEX IF NOT EXISTS idx_verif_event_time ON verification_events(timestamp);

-- 8. Verification Checks table (detailed check logs)
CREATE TABLE IF NOT EXISTS verification_checks (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    check_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_reference VARCHAR(100),
    raw_code VARCHAR(50),
    message TEXT,
    name_match_status VARCHAR(50),
    name_match_score NUMERIC(5,2),
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_verif_checks_entity ON verification_checks(entity_type, entity_id);

-- 9. Extend verification_documents table
ALTER TABLE verification_documents ADD COLUMN IF NOT EXISTS mime_type VARCHAR(100);
ALTER TABLE verification_documents ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT;
ALTER TABLE verification_documents ADD COLUMN IF NOT EXISTS storage_key VARCHAR(255);
ALTER TABLE verification_documents ADD COLUMN IF NOT EXISTS encryption_algorithm VARCHAR(50);
ALTER TABLE verification_documents ADD COLUMN IF NOT EXISTS reviewed_by UUID REFERENCES users(id);
ALTER TABLE verification_documents ADD COLUMN IF NOT EXISTS review_notes TEXT;
ALTER TABLE verification_documents ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP WITH TIME ZONE;
