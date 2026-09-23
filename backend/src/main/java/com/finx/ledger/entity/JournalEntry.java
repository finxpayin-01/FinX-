package com.finx.ledger.entity;

import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    private UUID id;

    @Column(name = "entry_reference", nullable = false, unique = true)
    private String entryReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private LedgerTransactionType transactionType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "deal_id")
    private UUID dealId;

    @Column(name = "milestone_id")
    private UUID milestoneId;

    @Column(name = "total_debit", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCredit;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String narration;

    @Column(name = "posted_by")
    private UUID postedBy;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "journalEntryId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<JournalLine> lines = new ArrayList<>();

    public JournalEntry() {
        this.id = UUID.randomUUID();
        this.currency = "INR";
        this.postedAt = Instant.now();
    }

    public JournalEntry(String entryReference, LedgerTransactionType transactionType, UUID referenceId,
                        UUID dealId, UUID milestoneId, BigDecimal amount, String currency, String narration, UUID postedBy) {
        this();
        this.entryReference = entryReference;
        this.transactionType = transactionType;
        this.referenceId = referenceId;
        this.dealId = dealId;
        this.milestoneId = milestoneId;
        this.totalDebit = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        this.totalCredit = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        if (currency != null) this.currency = currency;
        this.narration = narration;
        this.postedBy = postedBy;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEntryReference() { return entryReference; }
    public void setEntryReference(String entryReference) { this.entryReference = entryReference; }
    public LedgerTransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(LedgerTransactionType transactionType) { this.transactionType = transactionType; }
    public UUID getReferenceId() { return referenceId; }
    public void setReferenceId(UUID referenceId) { this.referenceId = referenceId; }
    public UUID getDealId() { return dealId; }
    public void setDealId(UUID dealId) { this.dealId = dealId; }
    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }
    public BigDecimal getTotalDebit() { return totalDebit; }
    public void setTotalDebit(BigDecimal totalDebit) { this.totalDebit = totalDebit; }
    public BigDecimal getTotalCredit() { return totalCredit; }
    public void setTotalCredit(BigDecimal totalCredit) { this.totalCredit = totalCredit; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }
    public UUID getPostedBy() { return postedBy; }
    public void setPostedBy(UUID postedBy) { this.postedBy = postedBy; }
    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
    public List<JournalLine> getLines() { return lines; }
    public void setLines(List<JournalLine> lines) { this.lines = lines; }
}
