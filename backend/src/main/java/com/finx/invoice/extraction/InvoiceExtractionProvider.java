package com.finx.invoice.extraction;

public interface InvoiceExtractionProvider {

    String getProviderName();

    boolean supports(String mimeType);

    ExtractedInvoiceData extract(InvoiceDocumentInput document);
}
