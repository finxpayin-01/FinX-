package com.finx.payment.service.onramp.onramper;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class OnramperSignatureService {
    private static final Logger log = LoggerFactory.getLogger(OnramperSignatureService.class);
    
    private final OnramperProperties properties;

    public OnramperSignatureService(OnramperProperties properties) {
        this.properties = properties;
    }

    public void validateSettings() {
        if (properties.getV2PrivateKey() == null || properties.getV2PrivateKey().isBlank()) {
            throw new RuntimeException("ONRAMP_V2_PRIVATE_KEY missing. V2 Intent blocked.");
        }
    }

    public String hashEndUserIp(String endUserIp) {
        validateSettings(); // Fail closed on missing V2 key
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(endUserIp.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) { hexString.append('0'); }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Error hashing IP", e);
            throw new RuntimeException("Failed to hash end-user IP", e);
        }
    }

    // Additional Ed25519 or other signing logic if strictly implemented goes here
}
