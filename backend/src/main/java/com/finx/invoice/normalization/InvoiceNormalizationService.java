package com.finx.invoice.normalization;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Service
public class InvoiceNormalizationService {

    public String normalizeInvoiceNumber(String invoiceNumber) {
        if (invoiceNumber == null) return null;
        String trimmed = invoiceNumber.trim();
        // Replace internal tabs/multiple whitespace with single space and uppercase for comparison
        String normalized = trimmed.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return normalized;
    }

    public String normalizeGstin(String gstin) {
        if (gstin == null) return null;
        return gstin.trim().replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public String normalizePan(String pan) {
        if (pan == null) return null;
        return pan.trim().replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public String normalizeLegalName(String legalName) {
        if (legalName == null) return null;
        String trimmed = legalName.trim().replaceAll("\\s+", " ");
        // Common suffix stripping for comparison (Pvt Ltd, LLC, Inc)
        String lower = trimmed.toLowerCase(Locale.ROOT);
        lower = lower.replaceAll("\\b(private|pvt|ltd|limited|llc|inc|corp|corporation)\\b", "");
        lower = lower.replaceAll("[^a-z0-9]", " ").replaceAll("\\s+", " ").trim();
        return lower;
    }

    public String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) return null;
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    public BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) return null;
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
