CREATE TABLE payment_instructions (
    id UUID PRIMARY KEY,
    instruction_reference VARCHAR(100) NOT NULL UNIQUE,
    deal_id UUID NOT NULL REFERENCES deals(id),
    milestone_id UUID REFERENCES milestones(id),
    buyer_organization_id UUID REFERENCES organizations(id),
    seller_organization_id UUID REFERENCES organizations(id),
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    provider VARCHAR(50) NOT NULL,
    provider_instruction_id VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    virtual_account_number VARCHAR(50),
    virtual_ifsc VARCHAR(20),
    idempotency_key VARCHAR(100) UNIQUE,
    initiated_by UUID REFERENCES users(id),
    confirmed_at TIMESTAMP WITH TIME ZONE,
    failure_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_instructions_deal ON payment_instructions(deal_id);
CREATE INDEX idx_payment_instructions_status ON payment_instructions(status);

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    transaction_reference VARCHAR(100) NOT NULL UNIQUE,
    payment_instruction_id UUID NOT NULL REFERENCES payment_instructions(id) ON DELETE CASCADE,
    provider_reference VARCHAR(100),
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    status VARCHAR(50) NOT NULL,
    utr_number VARCHAR(100),
    raw_provider_response TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_tx_instruction ON payment_transactions(payment_instruction_id);
CREATE INDEX idx_payment_tx_utr ON payment_transactions(utr_number);

CREATE TABLE settlement_instructions (
    id UUID PRIMARY KEY,
    settlement_reference VARCHAR(100) NOT NULL UNIQUE,
    deal_id UUID NOT NULL REFERENCES deals(id),
    milestone_id UUID NOT NULL REFERENCES milestones(id),
    seller_organization_id UUID NOT NULL REFERENCES organizations(id),
    beneficiary_bank_account_id UUID NOT NULL REFERENCES organization_bank_accounts(id),
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    provider VARCHAR(50) NOT NULL,
    provider_settlement_id VARCHAR(100),
    utr_number VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    authorized_by UUID REFERENCES users(id),
    authorized_at TIMESTAMP WITH TIME ZONE,
    settled_at TIMESTAMP WITH TIME ZONE,
    failure_reason TEXT,
    idempotency_key VARCHAR(100) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_settlements_deal ON settlement_instructions(deal_id);
CREATE INDEX idx_settlements_milestone ON settlement_instructions(milestone_id);
CREATE INDEX idx_settlements_status ON settlement_instructions(status);

CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    refund_reference VARCHAR(100) NOT NULL UNIQUE,
    deal_id UUID NOT NULL REFERENCES deals(id),
    milestone_id UUID REFERENCES milestones(id),
    payment_instruction_id UUID REFERENCES payment_instructions(id),
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    reason TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    authorized_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refunds_deal ON refunds(deal_id);
