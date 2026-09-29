package com.finx.invoice.dto;

import com.finx.invoice.entity.Invoice;
import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public record InvoiceResponse(
        UUID id,
        UUID dealId,
        UUID milestoneId,
        UUID vendorOrganizationId,
        UUID buyerOrganizationId,
        String invoiceNumber,
        String normalizedInvoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String currency,
        String comparisonBasis,
        String vendorLegalName,
        String vendorPan,
        String vendorGstin,
        String buyerLegalName,
        String buyerGstin,
        String bankAccountLast4,
        String bankIfsc,
        String fileName,
        String fileUrl,
        Long fileSizeBytes,
        String mimeType,
        String documentHash,
        InvoiceStatus status,
        ExtractionStatus extractionStatus,
        MatchingStatus matchingStatus,
        DuplicateConfidence duplicateConfidence,
        Integer matchScore,
        String policyVersion,
        String rejectionReason,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<InvoiceCheckResponse> checks,
        List<InvoiceReviewResponse> reviews,
        List<InvoiceExtractedFieldResponse> extractedFields
) {
    public static InvoiceResponse fromEntity(Invoice invoice) { return fromEntity(invoice, true); }
    public static InvoiceResponse fromEntity(Invoice invoice, boolean reviewer) {
        List<InvoiceCheckResponse> checkResponses = invoice.getChecks() != null
                ? invoice.getChecks().stream().map(c -> reviewer ? InvoiceCheckResponse.fromEntity(c) : new InvoiceCheckResponse(c.getId(), c.getCheckType(), c.getStatus(), c.getReasonCode(), c.getMessage(), 0, 0, null, c.getCreatedAt())).collect(Collectors.toList())
                : Collections.emptyList();

        List<InvoiceReviewResponse> reviewResponses = invoice.getReviews() != null
                ? invoice.getReviews().stream().map(InvoiceReviewResponse::fromEntity).collect(Collectors.toList())
                : Collections.emptyList();

        List<InvoiceExtractedFieldResponse> fieldResponses = invoice.getExtractedFields() != null
                ? invoice.getExtractedFields().stream().map(InvoiceExtractedFieldResponse::fromEntity).collect(Collectors.toList())
                : Collections.emptyList();

        return new InvoiceResponse(
                invoice.getId(),
                invoice.getDealId(),
                invoice.getMilestoneId(),
                invoice.getVendorOrganizationId(),
                invoice.getBuyerOrganizationId(),
                invoice.getInvoiceNumber(),
                invoice.getNormalizedInvoiceNumber(),
                invoice.getInvoiceDate(),
                invoice.getDueDate(),
                invoice.getSubtotal(),
                invoice.getTaxAmount(),
                invoice.getTotalAmount(),
                invoice.getCurrency(),
                invoice.getComparisonBasis(),
                invoice.getVendorLegalName(),
                invoice.getVendorPan(),
                invoice.getVendorGstin(),
                invoice.getBuyerLegalName(),
                invoice.getBuyerGstin(),
                invoice.getBankAccountLast4(),
                invoice.getBankIfsc(),
                invoice.getFileName(),
                invoice.getFileUrl(),
                invoice.getFileSizeBytes(),
                invoice.getMimeType(),
                invoice.getDocumentHash(),
                invoice.getStatus(),
                invoice.getExtractionStatus(),
                invoice.getMatchingStatus(),
                invoice.getDuplicateConfidence(),
                invoice.getMatchScore(),
                invoice.getPolicyVersion(),
                invoice.getRejectionReason(),
                invoice.getCreatedBy(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt(),
                checkResponses,
                reviewResponses,
                fieldResponses
        );
    }
}
