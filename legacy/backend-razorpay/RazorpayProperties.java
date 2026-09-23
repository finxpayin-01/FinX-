package com.finx.payment.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayProperties {

    private static final Logger log = LoggerFactory.getLogger(RazorpayProperties.class);

    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private String currency = "INR";
    private boolean sandboxMode = true;

    @PostConstruct
    public void logConfiguration() {
        if (!isConfigured()) {
            log.warn("================================================================================");
            if (keyId != null && !keyId.trim().isEmpty()) {
                String masked = keyId.length() > 6 ? keyId.substring(0, 6) + "..." : "[CONFIGURED]";
                log.warn("[PAYMENT CONFIGURATION] Razorpay Key ID configured ({}), but RAZORPAY_KEY_SECRET is pending.", masked);
                log.warn("Both Key ID and Key Secret are required for live checkout and signature verification.");
            } else {
                log.warn("[PAYMENT CONFIGURATION] Razorpay keys not configured.");
            }
            log.warn("Payments and webhook verification are disabled until Razorpay credentials are configured.");
            log.warn("Configure RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, and RAZORPAY_WEBHOOK_SECRET before accepting payments.");
            log.warn("================================================================================");
        } else {
            log.info("================================================================================");
            String masked = keyId.length() > 6 ? keyId.substring(0, 6) + "..." : "[CONFIGURED]";
            log.info("[PAYMENT CONFIGURATION] Razorpay is CONFIGURED and ACTIVE (Key ID: {})", masked);
            log.info("Key Secret: [CONFIGURED - NEVER LOGGED]");
            log.info("Default Currency: {}", currency);
            log.info("================================================================================");
        }
    }

    public boolean isConfigured() {
        return keyId != null && !keyId.trim().isEmpty() &&
               keySecret != null && !keySecret.trim().isEmpty();
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isSandboxMode() {
        return sandboxMode;
    }

    public void setSandboxMode(boolean sandboxMode) {
        this.sandboxMode = sandboxMode;
    }
}
