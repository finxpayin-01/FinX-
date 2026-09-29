package com.finx.invoice.dto;

import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.InvoiceReviewStatus;
import com.finx.invoice.enums.ReviewDecision;

import java.time.Instant;
import java.util.UUID;

public record InvoiceReviewResponse(
        UUID id,
        UUID invoiceId,
        UUID reviewerId,
        InvoiceReviewStatus status,
        ReviewDecision decision,
        String decisionReason,
        String notes,
        String evidenceReferences,
        Instant reviewedAt,
        Instant createdAt
) {
    public static InvoiceReviewResponse fromEntity(InvoiceReview r) {
        return new InvoiceReviewResponse(
                r.getId(),
                r.getInvoice() != null ? r.getInvoice().getId() : null,
                r.getReviewerId(),
                r.getStatus(),
                r.getDecision(),
                r.getDecisionReason(),
                r.getNotes(),
                r.getEvidenceReferences(),
                r.getReviewedAt(),
                r.getCreatedAt()
        );
    }
}
