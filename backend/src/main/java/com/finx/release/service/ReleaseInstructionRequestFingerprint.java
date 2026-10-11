package com.finx.release.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic, collision-safe SHA-256 request fingerprint computation for ReleaseInstruction creation.
 * Uses length-prefixed canonical encoding over authoritative invariant parameters.
 */
public final class ReleaseInstructionRequestFingerprint {

    private ReleaseInstructionRequestFingerprint() {
        // Utility class
    }

    public static String computeRequestFingerprint(
            UUID organizationId,
            UUID dealId,
            UUID milestoneId,
            UUID releaseDecisionId,
            String releaseDecisionFingerprint,
            String releaseInputFingerprint,
            UUID policyVersionId,
            String policyContentHash,
            UUID beneficiaryBankAccountId,
            String provider,
            String providerAccountId,
            BigDecimal amount,
            String currency
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(milestoneId, "milestoneId must not be null");
        Objects.requireNonNull(releaseDecisionId, "releaseDecisionId must not be null");
        Objects.requireNonNull(releaseDecisionFingerprint, "releaseDecisionFingerprint must not be null");
        Objects.requireNonNull(releaseInputFingerprint, "releaseInputFingerprint must not be null");
        Objects.requireNonNull(policyVersionId, "policyVersionId must not be null");
        Objects.requireNonNull(policyContentHash, "policyContentHash must not be null");
        Objects.requireNonNull(beneficiaryBankAccountId, "beneficiaryBankAccountId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        String normalizedAmount = amount.stripTrailingZeros().toPlainString();
        String normalizedCurrency = currency.trim().toUpperCase();

        StringBuilder canonical = new StringBuilder(512);
        encodeField(canonical, organizationId.toString());
        encodeField(canonical, dealId.toString());
        encodeField(canonical, milestoneId.toString());
        encodeField(canonical, releaseDecisionId.toString());
        encodeField(canonical, releaseDecisionFingerprint);
        encodeField(canonical, releaseInputFingerprint);
        encodeField(canonical, policyVersionId.toString());
        encodeField(canonical, policyContentHash);
        encodeField(canonical, beneficiaryBankAccountId.toString());
        encodeField(canonical, provider.trim().toUpperCase());
        encodeField(canonical, providerAccountId.trim());
        encodeField(canonical, normalizedAmount);
        encodeField(canonical, normalizedCurrency);

        return sha256Hex(canonical.toString());
    }

    private static void encodeField(StringBuilder sb, String value) {
        sb.append(value.length()).append(':').append(value).append(';');
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
