package com.finx.invoice.extraction;

public record InvoiceDocumentInput(
        String fileName,
        String fileUrl,
        String mimeType,
        byte[] content,
        ExtractedInvoiceData manualOverrides
) {
}
