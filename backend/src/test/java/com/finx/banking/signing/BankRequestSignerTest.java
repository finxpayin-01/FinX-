package com.finx.banking.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankRequestSignerTest {

    private static final String TEST_SECRET = "super-secure-test-signing-secret-key-32chars";
    private final HmacSha256BankRequestSigner signer = new HmacSha256BankRequestSigner("test-key-id", TEST_SECRET);

    @Test
    @DisplayName("Signer rejects null or blank secret")
    void testRejectsBlankSecret() {
        assertThatThrownBy(() -> new HmacSha256BankRequestSigner("k", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HmacSha256BankRequestSigner("k", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Identical canonical requests produce identical signatures")
    void testIdenticalRequestsProduceSameSignature() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        byte[] body = "{\"amount\": 1000.00}".getBytes(StandardCharsets.UTF_8);

        BankSigningRequest req1 = new BankSigningRequest("POST", "/api/v1/settlements", now, "REQ-001", body);
        BankSigningRequest req2 = new BankSigningRequest("POST", "/api/v1/settlements", now, "REQ-001", body);

        BankSigningResult res1 = signer.sign(req1);
        BankSigningResult res2 = signer.sign(req2);

        assertThat(res1.signature()).isEqualTo(res2.signature());
        assertThat(res1.algorithm()).isEqualTo("HMAC-SHA256");
        assertThat(res1.keyId()).isEqualTo("test-key-id");
        // Ensure secret is never included in the result
        assertThat(res1.signature()).doesNotContain(TEST_SECRET);
    }

    @Test
    @DisplayName("Altered request body produces different signature")
    void testAlteredBodyProducesDifferentSignature() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        byte[] body1 = "{\"amount\": 1000.00}".getBytes(StandardCharsets.UTF_8);
        byte[] body2 = "{\"amount\": 2000.00}".getBytes(StandardCharsets.UTF_8);

        BankSigningRequest req1 = new BankSigningRequest("POST", "/api/v1/settlements", now, "REQ-001", body1);
        BankSigningRequest req2 = new BankSigningRequest("POST", "/api/v1/settlements", now, "REQ-001", body2);

        assertThat(signer.sign(req1).signature()).isNotEqualTo(signer.sign(req2).signature());
    }

    @Test
    @DisplayName("Altered request path produces different signature")
    void testAlteredPathProducesDifferentSignature() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        byte[] body = "{\"amount\": 1000.00}".getBytes(StandardCharsets.UTF_8);

        BankSigningRequest req1 = new BankSigningRequest("POST", "/api/v1/settlements", now, "REQ-001", body);
        BankSigningRequest req2 = new BankSigningRequest("POST", "/api/v1/payouts", now, "REQ-001", body);

        assertThat(signer.sign(req1).signature()).isNotEqualTo(signer.sign(req2).signature());
    }

    @Test
    @DisplayName("Altered request timestamp produces different signature")
    void testAlteredTimestampProducesDifferentSignature() {
        Instant t1 = Instant.parse("2026-10-17T12:00:00Z");
        Instant t2 = Instant.parse("2026-10-17T12:00:01Z");
        byte[] body = "{\"amount\": 1000.00}".getBytes(StandardCharsets.UTF_8);

        BankSigningRequest req1 = new BankSigningRequest("POST", "/api/v1/settlements", t1, "REQ-001", body);
        BankSigningRequest req2 = new BankSigningRequest("POST", "/api/v1/settlements", t2, "REQ-001", body);

        assertThat(signer.sign(req1).signature()).isNotEqualTo(signer.sign(req2).signature());
    }
}
