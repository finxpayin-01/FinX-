package com.finx.verification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class PiiEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptionService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public PiiEncryptionService(@Value("${finx.security.pii-key:${jwt.secret:default-pii-encryption-key-32chars}}") String secretKey) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(secretKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize PII encryption service", e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) return plainText;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("PII encryption error: {}", e.getMessage());
            throw new RuntimeException("Error encrypting sensitive data", e);
        }
    }

    public String decrypt(String cipherTextBase64) {
        if (cipherTextBase64 == null || cipherTextBase64.isBlank()) return cipherTextBase64;
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherTextBase64);
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plainTextBytes = cipher.doFinal(cipherText);
            return new String(plainTextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("PII decryption error: {}", e.getMessage());
            throw new RuntimeException("Error decrypting sensitive data", e);
        }
    }

    public String mask(String raw, int visibleSuffixCount) {
        if (raw == null) return "****";
        String trimmed = raw.trim();
        if (trimmed.length() <= visibleSuffixCount) return "*".repeat(trimmed.length());
        return "*".repeat(trimmed.length() - visibleSuffixCount) + trimmed.substring(trimmed.length() - visibleSuffixCount);
    }

    public String maskPan(String rawPan) {
        if (rawPan == null || rawPan.isBlank()) return "ABCDE****F";
        String trimmed = rawPan.trim().toUpperCase(java.util.Locale.ROOT);
        if (trimmed.length() == 10) {
            return trimmed.substring(0, 5) + "****" + trimmed.substring(9);
        }
        if (trimmed.length() > 5) {
            return trimmed.substring(0, Math.min(5, trimmed.length() - 1)) + "****" + trimmed.substring(trimmed.length() - 1);
        }
        return "****";
    }

    public String maskBankAccount(String rawAcc) {
        if (rawAcc == null || rawAcc.isBlank()) return "********1234";
        String trimmed = rawAcc.trim();
        if (trimmed.length() <= 4) return "*".repeat(trimmed.length());
        return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
    }

    public String maskAadhaar(String rawAadhaar) {
        if (rawAadhaar == null || rawAadhaar.isBlank()) return "XXXX-XXXX-1234";
        String clean = rawAadhaar.replaceAll("[^0-9]", "");
        if (clean.length() == 12) {
            return "XXXX-XXXX-" + clean.substring(8);
        }
        return maskBankAccount(rawAadhaar);
    }
}
