CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(50) NOT NULL UNIQUE,
    account_name VARCHAR(100) NOT NULL,
    account_type VARCHAR(50) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    organization_id UUID REFERENCES organizations(id),
    deal_id UUID REFERENCES deals(id),
    balance NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_acc_org ON ledger_accounts(organization_id);
CREATE INDEX idx_ledger_acc_deal ON ledger_accounts(deal_id);

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    entry_reference VARCHAR(100) NOT NULL UNIQUE,
    transaction_type VARCHAR(50) NOT NULL,
    reference_id UUID,
    deal_id UUID REFERENCES deals(id),
    milestone_id UUID REFERENCES milestones(id),
    total_debit NUMERIC(19,4) NOT NULL,
    total_credit NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    narration TEXT NOT NULL,
    posted_by UUID REFERENCES users(id),
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (total_debit = total_credit)
);

CREATE INDEX idx_journal_entries_deal ON journal_entries(deal_id);
CREATE INDEX idx_journal_entries_ref ON journal_entries(reference_id);

CREATE TABLE journal_lines (
    id UUID PRIMARY KEY,
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
    ledger_account_id UUID NOT NULL REFERENCES ledger_accounts(id),
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    description VARCHAR(255),
    sequence INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (amount > 0),
    CHECK (entry_type IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_journal_lines_entry ON journal_lines(journal_entry_id);
CREATE INDEX idx_journal_lines_account ON journal_lines(ledger_account_id);
