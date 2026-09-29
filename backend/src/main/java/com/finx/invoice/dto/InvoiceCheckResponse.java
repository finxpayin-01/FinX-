package com.finx.invoice.dto;

import com.finx.invoice.entity.InvoiceCheck;
import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.InvoiceCheckType;

import java.time.Instant;
import java.util.UUID;

public record InvoiceCheckResponse(
        UUID id,
        InvoiceCheckType checkType,
        CheckStatus status,
        String reasonCode,
        String message,
        int weight,
        int scoreEarned,
        String details,
        Instant createdAt
) {
    public static InvoiceCheckResponse fromEntity(InvoiceCheck check) {
        return new InvoiceCheckResponse(
                check.getId(),
                check.getCheckType(),
                check.getStatus(),
                check.getReasonCode(),
                check.getMessage(),
                check.getWeight(),
                check.getScoreEarned(),
                check.getDetails(),
                check.getCreatedAt()
        );
    }
}
