package com.finx.invoice.matching;

import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.InvoiceCheckType;

public record InvoiceCheckDetail(
        InvoiceCheckType checkType,
        CheckStatus status,
        String reasonCode,
        String message,
        int weight,
        int scoreEarned,
        String details
) {
}
