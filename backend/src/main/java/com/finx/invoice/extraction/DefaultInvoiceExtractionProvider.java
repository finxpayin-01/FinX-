package com.finx.invoice.extraction;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="finx.invoice.extraction-provider", havingValue="manual", matchIfMissing=true)
public class DefaultInvoiceExtractionProvider implements InvoiceExtractionProvider {

    @Override
    public String getProviderName() {
        return "MANUAL_ENTRY_PROVIDER";
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return true;
        String m = mimeType.toLowerCase();
        return m.contains("pdf") || m.contains("png") || m.contains("jpeg") || m.contains("jpg") || m.contains("octet-stream");
    }

    @Override
    public ExtractedInvoiceData extract(InvoiceDocumentInput document) {
        ExtractedInvoiceData data = new ExtractedInvoiceData();

        if (document.manualOverrides() != null) {
            ExtractedInvoiceData mo = document.manualOverrides();
            data.setInvoiceNumber(mo.getInvoiceNumber());
            data.setInvoiceDate(mo.getInvoiceDate());
            data.setDueDate(mo.getDueDate());
            data.setSubtotal(mo.getSubtotal());
            data.setTaxAmount(mo.getTaxAmount());
            data.setTotalAmount(mo.getTotalAmount());
            data.setCurrency(mo.getCurrency());
            data.setVendorLegalName(mo.getVendorLegalName());
            data.setVendorPan(mo.getVendorPan());
            data.setVendorGstin(mo.getVendorGstin());
            data.setBuyerLegalName(mo.getBuyerLegalName());
            data.setBuyerGstin(mo.getBuyerGstin());
            data.setBankAccountLast4(mo.getBankAccountLast4());
            data.setBankIfsc(mo.getBankIfsc());

            if (mo.getConfidenceByField() != null && !mo.getConfidenceByField().isEmpty()) {
                data.setConfidenceByField(mo.getConfidenceByField());
            } else {
                setDefaultConfidences(data);
            }
            return data;
        }

        return data;
    }

    private void setDefaultConfidences(ExtractedInvoiceData data) {
        BigDecimal high = BigDecimal.ONE;
        if (data.getVendorLegalName()!=null) data.setFieldConfidence("vendorLegalName",high);
        if (data.getBuyerLegalName()!=null) data.setFieldConfidence("buyerLegalName",high);
        if (data.getBankAccountLast4()!=null) data.setFieldConfidence("bankAccountLast4",high);
        if (data.getBankIfsc()!=null) data.setFieldConfidence("bankIfsc",high);
        if (data.getInvoiceNumber() != null) data.setFieldConfidence("invoiceNumber", high);
        if (data.getInvoiceDate() != null) data.setFieldConfidence("invoiceDate", high);
        if (data.getTotalAmount() != null) data.setFieldConfidence("totalAmount", high);
        if (data.getCurrency() != null) data.setFieldConfidence("currency", high);
        if (data.getVendorGstin() != null) data.setFieldConfidence("vendorGstin", high);
        if (data.getVendorPan() != null) data.setFieldConfidence("vendorPan", high);
        if (data.getBuyerGstin() != null) data.setFieldConfidence("buyerGstin", high);
    }
}
