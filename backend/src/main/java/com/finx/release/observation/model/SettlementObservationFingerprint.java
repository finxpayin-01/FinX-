package com.finx.release.observation.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Deterministic SHA-256 canonical evidence fingerprint for SettlementObservation.
 * Enables exact-evidence deduplication per execution attempt and source.
 * Excludes local volatile timestamps (observedAt, createdAt) and the random observation ID.
 */
public final class SettlementObservationFingerprint {

    private static final String NULL_MARKER = "<NULL>";
    private static final String DELIMITER = "|";

    private SettlementObservationFingerprint() {}

    public static String calculate(
            UUID releaseInstructionId,
            UUID executionAttemptId,
            String provider,
            SettlementObservationSource source,
            String sourceReference,
            String providerOperationKey,
            String providerSettlementId,
            String providerAccountId,
            String providerStatus,
            SettlementObservationFinality finality,
            String reasonCode,
            String utrNumber,
            BigDecimal observedAmount,
            String observedCurrency,
            String observedDirection,
            Instant providerObservedAt
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(releaseInstructionId != null ? releaseInstructionId.toString() : NULL_MARKER).append(DELIMITER);
        sb.append(executionAttemptId != null ? executionAttemptId.toString() : NULL_MARKER).append(DELIMITER);
        sb.append(provider != null ? provider.trim().toUpperCase(Locale.ROOT) : NULL_MARKER).append(DELIMITER);
        sb.append(source != null ? source.name() : NULL_MARKER).append(DELIMITER);
        sb.append(sourceReference != null ? sourceReference.trim() : NULL_MARKER).append(DELIMITER);
        sb.append(providerOperationKey != null ? providerOperationKey.trim() : NULL_MARKER).append(DELIMITER);
        sb.append(providerSettlementId != null && !providerSettlementId.isBlank() ? providerSettlementId.trim() : NULL_MARKER).append(DELIMITER);
        sb.append(providerAccountId != null && !providerAccountId.isBlank() ? providerAccountId.trim() : NULL_MARKER).append(DELIMITER);
        sb.append(providerStatus != null ? providerStatus.trim().toUpperCase(Locale.ROOT) : NULL_MARKER).append(DELIMITER);
        sb.append(finality != null ? finality.name() : NULL_MARKER).append(DELIMITER);
        sb.append(reasonCode != null ? reasonCode.trim() : NULL_MARKER).append(DELIMITER);
        sb.append(utrNumber != null && !utrNumber.isBlank() ? utrNumber.trim() : NULL_MARKER).append(DELIMITER);
        sb.append(observedAmount != null ? observedAmount.stripTrailingZeros().toPlainString() : NULL_MARKER).append(DELIMITER);
        sb.append(observedCurrency != null && !observedCurrency.isBlank() ? observedCurrency.trim().toUpperCase(Locale.ROOT) : NULL_MARKER).append(DELIMITER);
        sb.append(observedDirection != null && !observedDirection.isBlank() ? observedDirection.trim().toUpperCase(Locale.ROOT) : NULL_MARKER).append(DELIMITER);
        sb.append(providerObservedAt != null ? providerObservedAt.toString() : NULL_MARKER);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
