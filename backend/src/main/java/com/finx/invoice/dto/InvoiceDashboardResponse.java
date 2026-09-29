package com.finx.invoice.dto;

import java.util.List;

public record InvoiceDashboardResponse(
        long totalInvoicesProcessed,
        long strongMatches,
        long reviewRequired,
        long majorMismatches,
        long duplicatesDetected,
        long pendingReviews,
        List<InvoiceResponse> recentInvoices
) {
}
