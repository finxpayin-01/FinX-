-- FINX Migration V8: On-Ramp and Off-Ramp Transactions
-- Platform: B2B Milestone-based Fiat Escrow Platform

-- 1. On-Ramp Transactions
CREATE TABLE IF NOT EXISTS onramp_transactions (
    id UUID PRIMARY KEY,
    buyer_id UUID NOT NULL,
    project_id UUID NOT NULL,
    milestone_id UUID NOT NULL,
    provider VARCHAR(100) NOT NULL,
    provider_order_id VARCHAR(255),
    provider_transaction_id VARCHAR(255),
    fiat_currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    fiat_amount NUMERIC(15, 2) NOT NULL,
    crypto_asset VARCHAR(20) NOT NULL DEFAULT 'USDC',
    expected_crypto_amount NUMERIC(15, 6),
    actual_crypto_amount NUMERIC(15, 6),
    network VARCHAR(50),
    destination_wallet VARCHAR(255),
    quote_id VARCHAR(255),
    quote_expires_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    blockchain_tx_hash VARCHAR(255),
    failure_code VARCHAR(100),
    failure_reason TEXT,
    idempotency_key VARCHAR(255) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_onramp_milestone FOREIGN KEY (milestone_id) REFERENCES milestones(id) ON DELETE RESTRICT,
    CONSTRAINT fk_onramp_buyer FOREIGN KEY (buyer_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_onramp_buyer_id ON onramp_transactions(buyer_id);
CREATE INDEX IF NOT EXISTS idx_onramp_project_id ON onramp_transactions(project_id);
CREATE INDEX IF NOT EXISTS idx_onramp_milestone_id ON onramp_transactions(milestone_id);
CREATE INDEX IF NOT EXISTS idx_onramp_provider_order_id ON onramp_transactions(provider_order_id);
CREATE INDEX IF NOT EXISTS idx_onramp_status ON onramp_transactions(status);

-- 2. Webhook Events (Idempotency and Audit)
CREATE TABLE IF NOT EXISTS webhook_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(100) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload_hash VARCHAR(255),
    processing_status VARCHAR(50) NOT NULL DEFAULT 'RECEIVED',
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_provider_event UNIQUE(provider, provider_event_id)
);

CREATE INDEX IF NOT EXISTS idx_webhook_provider_event ON webhook_events(provider, provider_event_id);
CREATE INDEX IF NOT EXISTS idx_webhook_status ON webhook_events(processing_status);

-- 3. Update Existing Payments Table to incorporate FundingRail
-- Adding a column to explicitly separate rails without breaking Razorpay compatibility
ALTER TABLE payments ADD COLUMN IF NOT EXISTS funding_rail VARCHAR(50) DEFAULT 'RAZORPAY';

-- 4. Idempotency Table (Global)
CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(255) PRIMARY KEY,
    user_id UUID,
    operation VARCHAR(100) NOT NULL,
    request_hash VARCHAR(255),
    response_reference VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON idempotency_keys(expires_at);

-- 5. Off-Ramp Transactions
CREATE TABLE IF NOT EXISTS offramp_transactions (
    id UUID PRIMARY KEY,
    vendor_id UUID NOT NULL,
    deal_id UUID NOT NULL,
    milestone_id UUID NOT NULL,
    provider VARCHAR(100) NOT NULL,
    provider_payout_id VARCHAR(255),
    fiat_currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    fiat_amount NUMERIC(15, 2),
    crypto_asset VARCHAR(20) NOT NULL DEFAULT 'USDC',
    crypto_amount NUMERIC(15, 6) NOT NULL,
    quote_id VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    failure_reason TEXT,
    idempotency_key VARCHAR(255) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_offramp_milestone FOREIGN KEY (milestone_id) REFERENCES milestones(id) ON DELETE RESTRICT,
    CONSTRAINT fk_offramp_vendor FOREIGN KEY (vendor_id) REFERENCES users(id) ON DELETE RESTRICT
);
