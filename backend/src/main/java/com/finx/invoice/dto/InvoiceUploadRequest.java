package com.finx.invoice.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

public class InvoiceUploadRequest {

    @NotBlank(message = "File name is required")
    private String fileName;

    private String fileUrl;

    private String fileContentBase64;

    private String mimeType = "application/pdf";

    private Long fileSizeBytes;

    // Optional extracted or manual fields
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String currency = "INR";
    private String comparisonBasis = "TOTAL";

    private String vendorLegalName;
    private String vendorPan;
    private String vendorGstin;

    private String buyerLegalName;
    private String buyerGstin;

    private String bankAccountLast4;
    private String bankIfsc;

    private java.util.UUID revisionOf;
    public java.util.UUID getRevisionOf() { return revisionOf; }
    public void setRevisionOf(java.util.UUID value) { revisionOf = value; }

    public InvoiceUploadRequest() {
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getFileContentBase64() {
        return fileContentBase64;
    }

    public void setFileContentBase64(String fileContentBase64) {
        this.fileContentBase64 = fileContentBase64;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
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

    public String getComparisonBasis() {
        return comparisonBasis;
    }

    public void setComparisonBasis(String comparisonBasis) {
        this.comparisonBasis = comparisonBasis;
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
}
