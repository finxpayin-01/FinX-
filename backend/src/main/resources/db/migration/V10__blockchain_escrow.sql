CREATE TABLE blockchain_wallets (
    user_id UUID NOT NULL REFERENCES users(id),
    chain_id BIGINT NOT NULL CHECK (chain_id > 0),
    address VARCHAR(42) NOT NULL,
    challenge VARCHAR(500) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, chain_id),
    UNIQUE (chain_id, address)
);

CREATE TABLE blockchain_escrows (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL UNIQUE REFERENCES deals(id),
    chain_id BIGINT NOT NULL CHECK (chain_id > 0),
    factory_address VARCHAR(42) NOT NULL,
    contract_address VARCHAR(42),
    token_address VARCHAR(42) NOT NULL,
    token_symbol VARCHAR(20) NOT NULL,
    token_decimals INTEGER NOT NULL CHECK (token_decimals BETWEEN 0 AND 36),
    buyer_address VARCHAR(42) NOT NULL,
    vendor_address VARCHAR(42) NOT NULL,
    total_atomic NUMERIC(78,0) NOT NULL CHECK (total_atomic > 0),
    funded_atomic NUMERIC(78,0) NOT NULL DEFAULT 0,
    released_atomic NUMERIC(78,0) NOT NULL DEFAULT 0,
    refunded_atomic NUMERIC(78,0) NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL DEFAULT 'CREATION_PENDING',
    reconciliation_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    indexed_block BIGINT,
    indexed_hash VARCHAR(66),
    start_block BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (chain_id, contract_address),
    CHECK (buyer_address <> vendor_address),
    CHECK (funded_atomic >= 0 AND funded_atomic <= total_atomic),
    CHECK (released_atomic >= 0 AND refunded_atomic >= 0 AND released_atomic + refunded_atomic <= funded_atomic)
);
CREATE INDEX idx_chain_escrow_reconciliation ON blockchain_escrows(reconciliation_status, created_at);

CREATE TABLE blockchain_allocations (
    escrow_id UUID NOT NULL REFERENCES blockchain_escrows(id),
    milestone_id UUID NOT NULL UNIQUE REFERENCES milestones(id),
    amount_atomic NUMERIC(78,0) NOT NULL CHECK (amount_atomic > 0),
    chain_status INTEGER NOT NULL DEFAULT 0 CHECK (chain_status BETWEEN 0 AND 6),
    PRIMARY KEY (escrow_id, milestone_id)
);

CREATE TABLE blockchain_transactions (
    id UUID PRIMARY KEY,
    escrow_id UUID NOT NULL REFERENCES blockchain_escrows(id),
    milestone_id UUID REFERENCES milestones(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    actor_role VARCHAR(30) NOT NULL,
    operation VARCHAR(30) NOT NULL,
    operation_key VARCHAR(180) NOT NULL UNIQUE,
    idempotency_key VARCHAR(150) NOT NULL,
    request_hash VARCHAR(66) NOT NULL,
    chain_id BIGINT NOT NULL,
    from_address VARCHAR(42) NOT NULL,
    to_address VARCHAR(42) NOT NULL,
    calldata TEXT NOT NULL,
    amount_atomic NUMERIC(78,0) NOT NULL DEFAULT 0,
    tx_hash VARCHAR(66),
    status VARCHAR(40) NOT NULL DEFAULT 'CREATED',
    block_number BIGINT,
    block_hash VARCHAR(66),
    confirmations BIGINT NOT NULL DEFAULT 0,
    reason VARCHAR(1000),
    error_code VARCHAR(60),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    failed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(actor_id, idempotency_key),
    UNIQUE(chain_id, tx_hash)
);
CREATE INDEX idx_chain_tx_escrow ON blockchain_transactions(escrow_id, created_at);
CREATE INDEX idx_chain_tx_pending ON blockchain_transactions(status, created_at);
CREATE INDEX idx_chain_tx_milestone ON blockchain_transactions(milestone_id);

CREATE TABLE blockchain_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    escrow_id UUID NOT NULL REFERENCES blockchain_escrows(id),
    chain_id BIGINT NOT NULL,
    tx_hash VARCHAR(66) NOT NULL,
    log_index BIGINT NOT NULL,
    block_number BIGINT NOT NULL,
    block_hash VARCHAR(66) NOT NULL,
    event_topic VARCHAR(66) NOT NULL,
    event_data TEXT NOT NULL,
    event_topics TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(chain_id, tx_hash, log_index)
);
CREATE INDEX idx_chain_events_escrow ON blockchain_events(escrow_id, block_number);

CREATE TABLE blockchain_funding_intents (
    id UUID PRIMARY KEY,
    escrow_id UUID NOT NULL REFERENCES blockchain_escrows(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(150) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    destination_wallet VARCHAR(42) NOT NULL,
    checkout_url TEXT,
    amount_atomic NUMERIC(78,0) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_id, idempotency_key)
);
CREATE INDEX idx_chain_funding_escrow ON blockchain_funding_intents(escrow_id);
