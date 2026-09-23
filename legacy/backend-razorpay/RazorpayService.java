package com.finx.payment.service;

import com.finx.payment.config.RazorpayProperties;
import com.finx.exception.PaymentGatewayUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
public class RazorpayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayService.class);
    private final RazorpayProperties properties;
    private final RestClient restClient;

    public RazorpayService(RazorpayProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.razorpay.com/v1")
                .build();
    }

    public String createOrder(BigDecimal amount, String currency, String receipt) {
        requirePaymentCredentials();
        long amountInSubunits = amount.multiply(BigDecimal.valueOf(100)).longValue();

        try {
            log.info("Initiating Razorpay {} order: amount={} {} receipt={}",
                    properties.isSandboxMode() ? "test" : "live", amount, currency, receipt);
            Map<String, Object> body = Map.of(
                    "amount", amountInSubunits,
                    "currency", currency != null ? currency.toUpperCase() : properties.getCurrency(),
                    "receipt", receipt
            );

            Map<?, ?> response = restClient.post()
                    .uri("/orders")
                    .headers(headers -> headers.setBasicAuth(properties.getKeyId(), properties.getKeySecret()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("id")) {
                String orderId = String.valueOf(response.get("id"));
                log.info("Razorpay order created successfully: {}", orderId);
                return orderId;
            }
            throw new PaymentGatewayUnavailableException("Razorpay did not return an order ID.");
        } catch (PaymentGatewayUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Razorpay order creation failed", e);
            throw new PaymentGatewayUnavailableException("Razorpay is unavailable. No payment order was created.", e);
        }
    }

    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null || signature == null || signature.isBlank()) {
            log.warn("Invalid signature verification parameters: orderId={}, paymentId={}", orderId, paymentId);
            return false;
        }

        requirePaymentCredentials();
        String secret = properties.getKeySecret();
        String payload = orderId + "|" + paymentId;

        String expectedSignature = calculateHmacSha256(payload, secret);

        boolean signatureMatches = MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8)
        );

        if (signatureMatches) {
            log.info("Razorpay cryptographic HMAC-SHA256 signature verified for orderId={}", orderId);
        } else {
            log.warn("Razorpay signature verification FAILED for orderId={} paymentId={}", orderId, paymentId);
        }

        return signatureMatches;
    }

    public String generateTestSignature(String orderId, String paymentId) {
        requirePaymentCredentials();
        return calculateHmacSha256(orderId + "|" + paymentId, properties.getKeySecret());
    }

    public String calculateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    public boolean verifyWebhookSignature(String payload, String signature) {
        if (payload == null || signature == null || signature.isBlank()) {
            return false;
        }

        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new PaymentGatewayUnavailableException("Razorpay webhook secret is not configured.");
        }

        String expectedSignature = calculateHmacSha256(payload, secret);
        boolean matches = MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8)
        );

        return matches;
    }

    public String getPublicKey() {
        requirePaymentCredentials();
        return properties.getKeyId();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public boolean isKeyIdPresent() {
        return properties.getKeyId() != null && !properties.getKeyId().trim().isEmpty();
    }

    public boolean isKeySecretPresent() {
        return properties.getKeySecret() != null && !properties.getKeySecret().trim().isEmpty();
    }

    public boolean isWebhookSecretPresent() {
        return properties.getWebhookSecret() != null && !properties.getWebhookSecret().trim().isEmpty();
    }

    public String getKeyMode() {
        String key = properties.getKeyId();
        if (key == null || key.isBlank()) return "NONE";
        if (key.trim().startsWith("rzp_test_")) return "TEST";
        if (key.trim().startsWith("rzp_live_")) return "LIVE";
        return "UNKNOWN";
    }

    public String getPublicKeySafe() {
        return properties.getKeyId();
    }

    public RazorpayProperties getProperties() {
        return properties;
    }

    private void requirePaymentCredentials() {
        if (!properties.isConfigured()) {
            throw new PaymentGatewayUnavailableException("Razorpay is not configured. Payments cannot be initiated or verified.");
        }
    }
}

