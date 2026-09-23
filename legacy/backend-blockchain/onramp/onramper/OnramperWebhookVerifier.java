package com.finx.payment.service.onramp.onramper;

import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.MessageDigest;

@Service
public class OnramperWebhookVerifier {
    private static final Logger log = LoggerFactory.getLogger(OnramperWebhookVerifier.class);
    
    public boolean verifySignature(String rawBody, String signature, String secret) {
        if (secret == null || secret.isBlank()) {
            log.error("ONRAMP_WEBHOOK_SECRET missing. Webhook rejected.");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }

        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            byte[] hash = sha256_HMAC.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(hash);
            return MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Webhook signature verification failed", e);
            return false;
        }
    }
}
