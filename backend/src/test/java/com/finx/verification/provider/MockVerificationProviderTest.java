package com.finx.verification.provider;

import com.finx.verification.provider.mock.MockVerificationProvider;
import com.finx.verification.provider.model.VerificationProviderModels.*;
import com.finx.verification.service.NameMatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class MockVerificationProviderTest {

    private MockVerificationProvider provider;

    @BeforeEach
    public void setUp() {
        provider = new MockVerificationProvider(new NameMatchService());
    }

    @Test
    public void testProviderIdentity() {
        assertEquals("MOCK", provider.getProviderName());
        assertTrue(provider.isSandbox(), "Mock provider must explicitly identify as sandbox");
    }

    @Test
    public void testPanVerification_Success() {
        var req = new PanVerificationRequest("ABCDE1234F", null, "Rajesh Kumar", LocalDate.of(1990, 1, 1));
        var res = provider.verifyPan(req);

        assertTrue(res.valid());
        assertEquals("VALID", res.panStatus());
        assertEquals("INDIVIDUAL", res.category());
        assertTrue(res.providerReference().startsWith("MOCK-SANDBOX-PAN-"));
        assertEquals("MATCH", res.nameMatchStatus());
    }

    @Test
    public void testPanVerification_InvalidPan() {
        var req = new PanVerificationRequest("FAIL12345F", null, "Rajesh Kumar", LocalDate.of(1990, 1, 1));
        var res = provider.verifyPan(req);

        assertFalse(res.valid());
        assertEquals("INVALID", res.panStatus());
        assertTrue(res.providerReference().contains("FAIL"));
    }

    @Test
    public void testGstVerification_Success() {
        var req = new GstVerificationRequest("27AAACA1234A1Z5", null, "Acme Technologies Private Limited");
        var res = provider.verifyGstin(req);

        assertTrue(res.valid());
        assertEquals("ACTIVE", res.status());
        assertNotNull(res.tradeName());
        assertTrue(res.providerReference().startsWith("MOCK-SANDBOX-GST-"));
    }

    @Test
    public void testGstVerification_Invalid() {
        var req = new GstVerificationRequest("FAIL00000000000", null, "Invalid Corp");
        var res = provider.verifyGstin(req);

        assertFalse(res.valid());
        assertEquals("INACTIVE", res.status());
    }

    @Test
    public void testCinVerification_Success() {
        var req = new CinVerificationRequest("U72900MH2020PTC123456", null, "Acme Technologies Private Limited");
        var res = provider.verifyCin(req);

        assertTrue(res.valid());
        assertEquals("ACTIVE", res.companyStatus());
    }

    @Test
    public void testBankVerification_Success() {
        var req = new BankVerificationRequest("123456789012", "HDFC0000060", null, "Acme Technologies Private Limited");
        var res = provider.verifyBankAccount(req);

        assertTrue(res.valid());
        assertEquals("MATCH", res.nameMatchStatus());
        assertEquals("HDFC Bank Ltd", res.bankName());
    }

    @Test
    public void testBankVerification_NameMismatchScenario() {
        var req = new BankVerificationRequest("123456789999", "HDFC0000060", null, "Acme Technologies Private Limited");
        var res = provider.verifyBankAccount(req);

        assertTrue(res.valid());
        // Simulates mismatch scenario with different registered beneficiary name
        assertEquals("MISMATCH", res.nameMatchStatus());
        assertTrue(res.nameMatchScore() < 50.0);
    }

    @Test
    public void testIdentitySessionAndStatus() {
        var sessReq = new IdentitySessionRequest("user-123", null, "https://redirect", "https://callback");
        var sessRes = provider.createIdentitySession(sessReq);

        assertNotNull(sessRes.sessionId());
        assertTrue(sessRes.verificationUrl().contains("sandbox"));

        var statusRes = provider.getIdentityStatus(sessRes.sessionId());
        assertEquals("VERIFIED", statusRes.status());
        assertNotNull(statusRes.maskedAadhaar());
    }
}
