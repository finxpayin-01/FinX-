package com.finx.invoice.entity;

import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices", indexes = {
        @Index(name = "idx_invoices_deal_id", columnList = "deal_id"),
        @Index(name = "idx_invoices_milestone_id", columnList = "milestone_id"),
        @Index(name = "idx_invoices_vendor_org", columnList = "vendor_organization_id"),
        @Index(name = "idx_invoices_buyer_org", columnList = "buyer_organization_id"),
        @Index(name = "idx_invoices_doc_hash", columnList = "document_hash"),
        @Index(name = "idx_invoices_vendor_num", columnList = "vendor_organization_id, normalized_invoice_number"),
        @Index(name = "idx_invoices_status", columnList = "status"),
        @Index(name = "idx_invoices_created_at", columnList = "created_at")
})
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "milestone_id", nullable = false)
    private UUID milestoneId;

    @Column(name = "vendor_organization_id", nullable = false)
    private UUID vendorOrganizationId;

    @Column(name = "buyer_organization_id", nullable = false)
    private UUID buyerOrganizationId;

    @Column(name = "invoice_number", nullable = false, length = 100)
    private String invoiceNumber;

    @Column(name = "normalized_invoice_number", nullable = false, length = 100)
    private String normalizedInvoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "subtotal", precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax_amount", precision = 15, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency = "INR";

    @Column(name = "comparison_basis", nullable = false, length = 20)
    private String comparisonBasis = "TOTAL";

    @Column(name = "vendor_legal_name")
    private String vendorLegalName;

    @Column(name = "vendor_pan", length = 20)
    private String vendorPan;

    @Column(name = "vendor_gstin", length = 50)
    private String vendorGstin;

    @Column(name = "buyer_legal_name")
    private String buyerLegalName;

    @Column(name = "buyer_gstin", length = 50)
    private String buyerGstin;

    @Column(name = "bank_account_last4", length = 10)
    private String bankAccountLast4;

    @Column(name = "bank_ifsc", length = 20)
    private String bankIfsc;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "document_hash", nullable = false, length = 64)
    private String documentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private InvoiceStatus status = InvoiceStatus.UPLOADED;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false, length = 50)
    private ExtractionStatus extractionStatus = ExtractionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "matching_status", nullable = false, length = 50)
    private MatchingStatus matchingStatus = MatchingStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_confidence", nullable = false, length = 50)
    private DuplicateConfidence duplicateConfidence = DuplicateConfidence.UNIQUE;

    @Column(name = "match_score")
    private Integer matchScore;

    @Column(name = "policy_version", nullable = false, length = 50)
    private String policyVersion = "v1.0.0";

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<InvoiceCheck> checks = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<InvoiceReview> reviews = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<InvoiceExtractedField> extractedFields = new ArrayList<>();

    @Column(name="revision_of")
    private UUID revisionOf;
    @Version @Column(name="row_version")
    private long rowVersion;
    public UUID getRevisionOf() { return revisionOf; }
    public void setRevisionOf(UUID value) { revisionOf = value; }

    public Invoice() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDealId() {
        return dealId;
    }

    public void setDealId(UUID dealId) {
        this.dealId = dealId;
    }

    public UUID getMilestoneId() {
        return milestoneId;
    }

    public void setMilestoneId(UUID milestoneId) {
        this.milestoneId = milestoneId;
    }

    public UUID getVendorOrganizationId() {
        return vendorOrganizationId;
    }

    public void setVendorOrganizationId(UUID vendorOrganizationId) {
        this.vendorOrganizationId = vendorOrganizationId;
    }

    public UUID getBuyerOrganizationId() {
        return buyerOrganizationId;
    }

    public void setBuyerOrganizationId(UUID buyerOrganizationId) {
        this.buyerOrganizationId = buyerOrganizationId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public String getNormalizedInvoiceNumber() {
        return normalizedInvoiceNumber;
    }

    public void setNormalizedInvoiceNumber(String normalizedInvoiceNumber) {
        this.normalizedInvoiceNumber = normalizedInvoiceNumber;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getComparisonBasis() {
        return comparisonBasis;
    }

    public void setComparisonBasis(String comparisonBasis) {
        this.comparisonBasis = comparisonBasis;
    }

    public String getVendorLegalName() {
        return vendorLegalName;
    }

    public void setVendorLegalName(String vendorLegalName) {
        this.vendorLegalName = vendorLegalName;
    }

    public String getVendorPan() {
        return vendorPan;
    }

    public void setVendorPan(String vendorPan) {
        this.vendorPan = vendorPan;
    }

    public String getVendorGstin() {
        return vendorGstin;
    }

    public void setVendorGstin(String vendorGstin) {
        this.vendorGstin = vendorGstin;
    }

    public String getBuyerLegalName() {
        return buyerLegalName;
    }

    public void setBuyerLegalName(String buyerLegalName) {
        this.buyerLegalName = buyerLegalName;
    }

    public String getBuyerGstin() {
        return buyerGstin;
    }

    public void setBuyerGstin(String buyerGstin) {
        this.buyerGstin = buyerGstin;
    }

    public String getBankAccountLast4() {
        return bankAccountLast4;
    }

    public void setBankAccountLast4(String bankAccountLast4) {
        this.bankAccountLast4 = bankAccountLast4;
    }

    public String getBankIfsc() {
        return bankIfsc;
    }

    public void setBankIfsc(String bankIfsc) {
        this.bankIfsc = bankIfsc;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public void setDocumentHash(String documentHash) {
        this.documentHash = documentHash;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public ExtractionStatus getExtractionStatus() {
        return extractionStatus;
    }

    public void setExtractionStatus(ExtractionStatus extractionStatus) {
        this.extractionStatus = extractionStatus;
    }

    public MatchingStatus getMatchingStatus() {
        return matchingStatus;
    }

    public void setMatchingStatus(MatchingStatus matchingStatus) {
        this.matchingStatus = matchingStatus;
    }

    public DuplicateConfidence getDuplicateConfidence() {
        return duplicateConfidence;
    }

    public void setDuplicateConfidence(DuplicateConfidence duplicateConfidence) {
        this.duplicateConfidence = duplicateConfidence;
    }

    public Integer getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(Integer matchScore) {
        this.matchScore = matchScore;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<InvoiceCheck> getChecks() {
        return checks;
    }

    public void setChecks(List<InvoiceCheck> checks) {
        this.checks.clear();
        if (checks != null) {
            this.checks.addAll(checks);
        }
    }

    public List<InvoiceReview> getReviews() {
        return reviews;
    }

    public void setReviews(List<InvoiceReview> reviews) {
        this.reviews.clear();
        if (reviews != null) {
            this.reviews.addAll(reviews);
        }
    }

    public List<InvoiceExtractedField> getExtractedFields() {
        return extractedFields;
    }

    public void setExtractedFields(List<InvoiceExtractedField> extractedFields) {
        this.extractedFields.clear();
        if (extractedFields != null) {
            this.extractedFields.addAll(extractedFields);
        }
    }
}
