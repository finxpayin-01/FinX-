-- Migration V25: Add Engine Policy Versions and Engine Decisions Foundation
-- Platform: B2B Milestone-based Fiat Escrow Platform
-- Scope: Restricted Common EngineDecision Framework Foundation (Audit and Decision Recording Only)

-- 1. Engine Policy Versions Table (Immutable policy metadata archive)
CREATE TABLE IF NOT EXISTS engine_policy_versions (
    id UUID PRIMARY KEY,
    organization_id UUID,
    scope VARCHAR(30) NOT NULL,
    engine_type VARCHAR(50) NOT NULL,
    policy_key VARCHAR(100) NOT NULL,
    version VARCHAR(50) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    schema_version VARCHAR(20) NOT NULL DEFAULT '1.0',
    description VARCHAR(500),
    policy_content TEXT,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_engine_policy_scope CHECK (scope IN ('PLATFORM', 'ORGANIZATION')),
    CONSTRAINT chk_engine_policy_engine_type CHECK (engine_type IN ('RISK', 'TRUST', 'MILESTONE_RULES', 'APPROVAL', 'FRAUD', 'DOCUMENT', 'RECONCILIATION', 'DISPUTE', 'RELEASE')),
    CONSTRAINT chk_engine_policy_key_nonblank CHECK (length(trim(policy_key)) > 0),
    CONSTRAINT chk_engine_policy_version_nonblank CHECK (length(trim(version)) > 0),
    CONSTRAINT chk_engine_policy_content_hash_nonblank CHECK (length(trim(content_hash)) > 0),
    CONSTRAINT chk_engine_policy_scope_org CHECK (
        (scope = 'PLATFORM' AND organization_id IS NULL) OR
        (scope = 'ORGANIZATION' AND organization_id IS NOT NULL)
    )
);

-- Indexes for policy versions
CREATE INDEX IF NOT EXISTS idx_engine_policy_org ON engine_policy_versions(organization_id);
CREATE INDEX IF NOT EXISTS idx_engine_policy_type_key ON engine_policy_versions(engine_type, policy_key);

-- Partial unique indexes: platform scope (organization_id IS NULL) vs organization scope (organization_id IS NOT NULL)
CREATE UNIQUE INDEX IF NOT EXISTS uk_engine_policy_platform
    ON engine_policy_versions (engine_type, policy_key, version)
    WHERE organization_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_engine_policy_org
    ON engine_policy_versions (organization_id, engine_type, policy_key, version)
    WHERE organization_id IS NOT NULL;


-- 2. Engine Decisions Table (Append-only evaluation envelopes)
CREATE TABLE IF NOT EXISTS engine_decisions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    engine_type VARCHAR(50) NOT NULL,
    subject_type VARCHAR(50) NOT NULL,
    subject_id UUID NOT NULL,
    decision VARCHAR(20) NOT NULL,
    evaluation_outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    error_code VARCHAR(100),
    score NUMERIC(10, 4),
    reason_codes_json TEXT NOT NULL DEFAULT '[]',
    explanation VARCHAR(1000),
    evidence_references_json TEXT NOT NULL DEFAULT '[]',
    rule_version VARCHAR(100),
    policy_version_id UUID,
    policy_content_hash VARCHAR(64),
    source_assessment_id UUID,
    evaluation_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    input_fingerprint VARCHAR(64),
    input_revision VARCHAR(100),
    schema_version VARCHAR(20) NOT NULL DEFAULT '1.0',
    engine_implementation_version VARCHAR(50) NOT NULL DEFAULT '1.0.0',
    decision_fingerprint VARCHAR(64),
    valid_until TIMESTAMP WITH TIME ZONE,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_engine_decisions_decision CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    CONSTRAINT chk_engine_decisions_outcome CHECK (evaluation_outcome IN ('SUCCESS', 'ERROR')),
    CONSTRAINT chk_engine_decisions_engine_type CHECK (engine_type IN ('RISK', 'TRUST', 'MILESTONE_RULES', 'APPROVAL', 'FRAUD', 'DOCUMENT', 'RECONCILIATION', 'DISPUTE', 'RELEASE')),
    CONSTRAINT chk_engine_decisions_subject_type_nonblank CHECK (length(trim(subject_type)) > 0),
    CONSTRAINT chk_engine_decisions_error_invariant CHECK (
        evaluation_outcome != 'ERROR' OR (decision = 'REVIEW' AND error_code IS NOT NULL AND length(trim(error_code)) > 0)
    ),
    CONSTRAINT chk_engine_decisions_valid_until CHECK (valid_until IS NULL OR valid_until >= evaluated_at)
);

-- Indexes for engine decisions
CREATE INDEX IF NOT EXISTS idx_engine_decisions_org_evaluated
    ON engine_decisions(organization_id, evaluated_at DESC);

CREATE INDEX IF NOT EXISTS idx_engine_decisions_org_subject
    ON engine_decisions(organization_id, subject_type, subject_id, evaluated_at DESC);

CREATE INDEX IF NOT EXISTS idx_engine_decisions_org_engine_subject
    ON engine_decisions(organization_id, engine_type, subject_type, subject_id, evaluated_at DESC);

CREATE INDEX IF NOT EXISTS idx_engine_decisions_correlation
    ON engine_decisions(correlation_id);

CREATE INDEX IF NOT EXISTS idx_engine_decisions_evaluation
    ON engine_decisions(evaluation_id);

-- Logical decision uniqueness constraint (prevents duplicate decision identity within an evaluation cycle)
CREATE UNIQUE INDEX IF NOT EXISTS uk_engine_decisions_logical_identity
    ON engine_decisions (evaluation_id, engine_type, subject_type, subject_id, organization_id);
