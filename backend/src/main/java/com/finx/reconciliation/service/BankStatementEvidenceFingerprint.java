package com.finx.reconciliation.service;

import com.finx.banking.dto.BankStatementEntry;
import com.finx.reconciliation.entity.BankStatementLine;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

public final class BankStatementEvidenceFingerprint {

    private BankStatementEvidenceFingerprint() {
    }

    public static String computeLineFingerprint(BankStatementLine line) {
        if (line == null) {
            throw new IllegalArgumentException("BankStatementLine cannot be null");
        }
        return computeLineFingerprint(
                line.getProvider(),
                line.getProviderAccountId(),
                line.getProviderLineId(),
                line.getTransactionReference(),
                line.getUtrNumber(),
                line.getAmount(),
                line.getCurrency(),
                line.getDirection() != null ? line.getDirection().name() : null,
                line.getProviderStatus(),
                line.getNormalizedStatus() != null ? line.getNormalizedStatus().name() : null,
                line.getTransactionTime(),
                line.getValueDate()
        );
    }

    public static String computeLineFingerprint(String provider, String providerAccountId, BankStatementEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("BankStatementEntry cannot be null");
        }
        return computeLineFingerprint(
                provider,
                providerAccountId,
                entry.getProviderLineId(),
                entry.getTransactionReference(),
                entry.getUtrNumber(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getDirection() != null ? entry.getDirection().name() : null,
                entry.getProviderStatus(),
                entry.getFinality() != null ? entry.getFinality().name() : null,
                entry.getTransactionTime(),
                entry.getValueDate()
        );
    }

    public static String computeLineFingerprint(
            String provider,
            String providerAccountId,
            String providerLineId,
            String transactionReference,
            String utrNumber,
            BigDecimal amount,
            String currency,
            String direction,
            String providerStatus,
            String finality,
            Instant transactionTime,
            LocalDate valueDate
    ) {
        StringBuilder sb = new StringBuilder();
        appendCanonicalField(sb, provider);
        appendCanonicalField(sb, providerAccountId);
        appendCanonicalField(sb, providerLineId);
        appendCanonicalField(sb, transactionReference);
        appendCanonicalField(sb, utrNumber);
        appendCanonicalField(sb, amount != null ? amount.stripTrailingZeros().toPlainString() : null);
        appendCanonicalField(sb, currency != null ? currency.toUpperCase().trim() : null);
        appendCanonicalField(sb, direction);
        appendCanonicalField(sb, providerStatus);
        appendCanonicalField(sb, finality);
        appendCanonicalField(sb, transactionTime != null ? transactionTime.toString() : null);
        appendCanonicalField(sb, valueDate != null ? valueDate.toString() : null);

        return sha256Hex(sb.toString());
    }

    public static String computeSourceHash(
            String provider,
            String providerAccountId,
            String currency,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            String providerImportReference,
            List<String> lineFingerprints,
            Instant windowStart,
            Instant windowEnd
    ) {
        StringBuilder sb = new StringBuilder();
        appendCanonicalField(sb, provider);
        appendCanonicalField(sb, providerAccountId);
        appendCanonicalField(sb, currency != null ? currency.toUpperCase().trim() : null);
        appendCanonicalField(sb, openingBalance != null ? openingBalance.stripTrailingZeros().toPlainString() : null);
        appendCanonicalField(sb, closingBalance != null ? closingBalance.stripTrailingZeros().toPlainString() : null);
        appendCanonicalField(sb, providerImportReference);

        if (lineFingerprints == null || lineFingerprints.isEmpty()) {
            // Empty statement rule: include window boundaries so different empty windows have distinct hashes
            appendCanonicalField(sb, "EMPTY");
            appendCanonicalField(sb, windowStart != null ? windowStart.toString() : null);
            appendCanonicalField(sb, windowEnd != null ? windowEnd.toString() : null);
        } else {
            List<String> sortedFingerprints = new ArrayList<>(lineFingerprints);
            Collections.sort(sortedFingerprints);
            for (String fp : sortedFingerprints) {
                appendCanonicalField(sb, fp);
            }
        }

        return sha256Hex(sb.toString());
    }

    private static void appendCanonicalField(StringBuilder sb, String value) {
        String safeValue = value == null ? "" : value.trim();
        sb.append(safeValue.length()).append(':').append(safeValue).append('|');
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
