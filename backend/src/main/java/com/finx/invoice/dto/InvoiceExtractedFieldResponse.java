package com.finx.invoice.dto;

import com.finx.invoice.entity.InvoiceExtractedField;

import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceExtractedFieldResponse(
        UUID id,
        String fieldName,
        String rawValue,
        String normalizedValue,
        BigDecimal confidence
) {
    public static InvoiceExtractedFieldResponse fromEntity(InvoiceExtractedField field) {
        return new InvoiceExtractedFieldResponse(
                field.getId(),
                field.getFieldName(),
                field.getRawValue(),
                field.getNormalizedValue(),
                field.getConfidence()
        );
    }
}
