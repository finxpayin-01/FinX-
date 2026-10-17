package com.finx.banking.signing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic HMAC-SHA256 request signer for partner banking integrations.
 * Canonicalizes request inputs (method, path, epoch millis, request ID, SHA-256 body hash)
 * and signs with a shared secret key. Never logs or leaks the secret material.
 */
public class HmacSha256BankRequestSigner implements BankRequestSigner {

    private static final String ALGORITHM = "HMAC-SHA256";
    private static final String HMAC_SHA256_JAVA = "HmacSHA256";

    private final String keyId;
    private final byte[] secretKeyBytes;

    public HmacSha256BankRequestSigner(String keyId, String secretKey) {
        this.keyId = keyId != null ? keyId : "default";
        Objects.requireNonNull(secretKey, "secretKey must not be null");
        if (secretKey.isBlank()) {
            throw new IllegalArgumentException("secretKey must not be blank");
        }
        this.secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String getAlgorithm() {
        return ALGORITHM;
    }

    @Override
    public BankSigningResult sign(BankSigningRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String bodyHash = computeSha256Hex(request.bodyBytes());
        String canonicalString = request.method().toUpperCase(Locale.ROOT) + "\n"
                + request.path() + "\n"
                + request.timestamp().toEpochMilli() + "\n"
                + request.requestId() + "\n"
                + bodyHash;

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_JAVA);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKeyBytes, HMAC_SHA256_JAVA);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8));
            String signature = HexFormat.of().formatHex(hmacBytes);
            return new BankSigningResult(signature, ALGORITHM, keyId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 request signature", e);
        }
    }

    private static String computeSha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data != null ? data : new byte[0]);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
