ALTER TABLE risk_assessments ADD COLUMN user_id UUID REFERENCES users(id);
ALTER TABLE risk_assessments ADD COLUMN organization_id UUID REFERENCES organizations(id);
ALTER TABLE risk_assessments ADD COLUMN user_type VARCHAR(20);
ALTER TABLE risk_assessments ADD COLUMN rule_version VARCHAR(100);
ALTER TABLE risk_assessments ADD COLUMN previous_score INT;
ALTER TABLE risk_assessments ADD COLUMN score_change INT;
ALTER TABLE risk_assessments ADD COLUMN review_required BOOLEAN;
ALTER TABLE risk_assessments ADD COLUMN review_status VARCHAR(30);
CREATE INDEX idx_risk_assessments_user ON risk_assessments(user_id, created_at);
CREATE INDEX idx_risk_assessments_org ON risk_assessments(organization_id, created_at);

CREATE TABLE risk_scores (
    id UUID PRIMARY KEY,
    subject_key VARCHAR(150) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id),
    organization_id UUID REFERENCES organizations(id),
    user_type VARCHAR(20) NOT NULL CHECK (user_type IN ('BUYER','VENDOR')),
    assessment_id UUID REFERENCES risk_assessments(id),
    score INT NOT NULL DEFAULT 0 CHECK (score BETWEEN 0 AND 100),
    risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW',
    verification_status VARCHAR(50),
    transaction_volume NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    calculated_at TIMESTAMP WITH TIME ZONE,
    next_refresh_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_risk_scores_user ON risk_scores(user_id);
CREATE INDEX idx_risk_scores_org ON risk_scores(organization_id);
CREATE INDEX idx_risk_scores_assessment ON risk_scores(assessment_id);
CREATE INDEX idx_risk_scores_filter ON risk_scores(user_type,risk_level,score);
CREATE INDEX idx_risk_scores_refresh ON risk_scores(next_refresh_at);

CREATE TABLE risk_rules (
    id UUID PRIMARY KEY,
    user_type VARCHAR(20) NOT NULL UNIQUE,
    configuration TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID REFERENCES users(id)
);
CREATE INDEX idx_risk_rules_editor ON risk_rules(updated_by);

CREATE TABLE risk_events (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES risk_scores(id),
    source_key VARCHAR(255) NOT NULL,
    audit_log_id UUID REFERENCES audit_logs(id),
    deal_id UUID REFERENCES deals(id),
    milestone_id UUID REFERENCES milestones(id),
    payment_id UUID REFERENCES payments(id),
    dispute_id UUID REFERENCES disputes(id),
    attachment_id UUID REFERENCES deliverables(id),
    safe_evidence TEXT,
    event_type VARCHAR(50) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    retry_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(255),
    dead_letter BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(source_key,subject_id)
);
CREATE INDEX idx_risk_events_subject_time ON risk_events(subject_id,occurred_at,event_type);
CREATE INDEX idx_risk_events_pending ON risk_events(dead_letter,processed_at,retry_at);
CREATE INDEX idx_risk_events_audit ON risk_events(audit_log_id);
CREATE INDEX idx_risk_events_deal ON risk_events(deal_id);
CREATE INDEX idx_risk_events_milestone ON risk_events(milestone_id);
CREATE INDEX idx_risk_events_payment ON risk_events(payment_id);
CREATE INDEX idx_risk_events_dispute ON risk_events(dispute_id);
CREATE INDEX idx_risk_events_attachment ON risk_events(attachment_id);

CREATE TABLE risk_score_history (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES risk_scores(id),
    assessment_id UUID NOT NULL UNIQUE REFERENCES risk_assessments(id),
    snapshot TEXT NOT NULL,
    calculated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_risk_history_subject_time ON risk_score_history(subject_id,calculated_at);
CREATE TABLE risk_factors (
    id UUID PRIMARY KEY,
    assessment_id UUID NOT NULL REFERENCES risk_assessments(id),
    code VARCHAR(100) NOT NULL,
    evidence TEXT NOT NULL
);
CREATE INDEX idx_risk_factors_assessment ON risk_factors(assessment_id);
CREATE TABLE risk_alerts (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES risk_scores(id),
    assessment_id UUID NOT NULL REFERENCES risk_assessments(id),
    severity VARCHAR(20) NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cleared_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_risk_alerts_subject ON risk_alerts(subject_id,cleared_at);
CREATE INDEX idx_risk_alerts_assessment ON risk_alerts(assessment_id);
CREATE INDEX idx_risk_alerts_open ON risk_alerts(cleared_at,created_at);
CREATE TABLE risk_reviews (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES risk_scores(id),
    assessment_id UUID NOT NULL UNIQUE REFERENCES risk_assessments(id),
    alert_id UUID NOT NULL REFERENCES risk_alerts(id),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    action VARCHAR(30) NOT NULL DEFAULT 'MANUAL_REVIEW',
    reviewer_id UUID REFERENCES users(id),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    restriction_until TIMESTAMP WITH TIME ZONE,
    reason TEXT,
    notes TEXT,
    evidence_references TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_risk_reviews_subject ON risk_reviews(subject_id,status);
CREATE INDEX idx_risk_reviews_alert ON risk_reviews(alert_id);
CREATE INDEX idx_risk_reviews_reviewer ON risk_reviews(reviewer_id);
CREATE INDEX idx_risk_reviews_status ON risk_reviews(status,created_at);
CREATE INDEX idx_deals_risk_buyer ON deals(buyer_id,buyer_organization_id,created_at);
CREATE INDEX idx_deals_risk_vendor ON deals(seller_id,seller_organization_id,created_at);
