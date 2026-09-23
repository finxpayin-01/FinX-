package com.finx.ledger.entity;

import com.finx.ledger.enums.LedgerEnums.EntryType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    private UUID id;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "ledger_account_id", nullable = false)
    private UUID ledgerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    private String description;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public JournalLine() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public JournalLine(UUID journalEntryId, UUID ledgerAccountId, EntryType entryType, BigDecimal amount, String description, int sequence) {
        this();
        this.journalEntryId = journalEntryId;
        this.ledgerAccountId = ledgerAccountId;
        this.entryType = entryType;
        this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        this.description = description;
        this.sequence = sequence;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(UUID journalEntryId) { this.journalEntryId = journalEntryId; }
    public UUID getLedgerAccountId() { return ledgerAccountId; }
    public void setLedgerAccountId(UUID ledgerAccountId) { this.ledgerAccountId = ledgerAccountId; }
    public EntryType getEntryType() { return entryType; }
    public void setEntryType(EntryType entryType) { this.entryType = entryType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
