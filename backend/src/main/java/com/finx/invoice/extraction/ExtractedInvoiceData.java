package com.finx.invoice.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public class ExtractedInvoiceData {

    private String invoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String currency;
    private String vendorLegalName;
    private String vendorPan;
    private String vendorGstin;
    private String buyerLegalName;
    private String buyerGstin;
    private String bankAccountLast4;
    private String bankIfsc;
    private Map<String, BigDecimal> confidenceByField = new HashMap<>();

    public ExtractedInvoiceData() {
    }

    public void setFieldConfidence(String field, BigDecimal confidence) {
        if (field != null && confidence != null) {
            confidenceByField.put(field, confidence);
        }
    }

    public BigDecimal getFieldConfidence(String field) {
        return confidenceByField.getOrDefault(field, BigDecimal.ZERO);
    }

    public boolean hasLowConfidenceField(BigDecimal threshold) {
        for (BigDecimal conf : confidenceByField.values()) {
            if (conf != null && conf.compareTo(threshold) < 0) {
                return true;
            }
        }
        return false;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
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

    public Map<String, BigDecimal> getConfidenceByField() {
        return confidenceByField;
    }

    public void setConfidenceByField(Map<String, BigDecimal> confidenceByField) {
        this.confidenceByField = confidenceByField;
    }
}
