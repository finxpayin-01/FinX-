package com.finx.verification;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.org.dto.OrgDtos.CreateOrgRequest;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.service.OrganizationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.verification.dto.VerificationDtos.*;
import com.finx.verification.enums.AuthorityRelationshipType;
import com.finx.verification.enums.AuthorityStatus;
import com.finx.verification.enums.ConsentType;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.VerificationEventRepository;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class VerificationServiceIntegrationTest {

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private VerificationEligibilityService eligibilityService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationEventRepository eventRepository;

    private User testUser;
    private UserPrincipal principal;
    private UUID orgId;

    @BeforeEach
    public void setUp() {
        testUser = new User("Rajesh Sharma", "rajesh." + UUID.randomUUID() + "@finx.com", "hashed_pwd", Role.BUYER, UserStatus.ACTIVE);
        testUser = userRepository.saveAndFlush(testUser);
        principal = UserPrincipal.create(testUser);

        // Create organization
        var orgResp = organizationService.createOrganization(
                new CreateOrgRequest("Acme Technologies", "Acme Technologies Private Limited",
                        EntityType.PRIVATE_LIMITED, "U72900MH2020PTC123456", "AAACA1234A",
                        "27AAACA1234A1Z5", "Plot 42, BKC, Mumbai"),
                principal
        );
        orgId = orgResp.id();
    }

    @Test
    public void testConsentEnforcement_WithoutConsent_ThrowsException() {
        assertThrows(RuntimeException.class, () ->
                verificationService.verifyIndividualPan(
                        new VerifyPanRequest("ABCDE1234F", "Rajesh Sharma", LocalDate.of(1990, 1, 1)),
                        principal, "127.0.0.1", "TestAgent"
                )
        );
    }

    @Test
    public void testFullKycAndKybLifecycle_InSandbox() {
        // 1. Record Consent
        var consentResp = verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.PAN, null, "Individual KYC Verification", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );
        assertNotNull(consentResp.id());

        // 2. Start Individual KYC & Verify PAN
        verificationService.startKyc(principal);
        var panResp = verificationService.verifyIndividualPan(
                new VerifyPanRequest("ABCDE1234F", "Rajesh Sharma", LocalDate.of(1990, 1, 1)),
                principal, "127.0.0.1", "TestAgent"
        );
        assertTrue(panResp.valid());
        assertEquals("VALID", panResp.panStatus());
        assertEquals("ABCDE****F", panResp.panMasked());

        // 3. Complete KYC
        var kycStatus = verificationService.getKycStatus(principal);
        assertEquals(VerificationStatus.VERIFIED, kycStatus.status());
        assertTrue(kycStatus.isSandbox(), "Mock verification must be visibly marked as sandbox");

        // 4. Start Corporate KYB
        verificationService.startKyb(orgId, principal);

        // Record Corporate Consents
        verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.PAN, orgId, "Corporate PAN Verification", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );
        verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.GST, orgId, "Corporate GSTIN Verification", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );
        verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.BANK, orgId, "Penny Drop Bank Verification", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );

        // 5. Verify Corporate PAN, GSTIN, and Registration
        var panKyb = verificationService.verifyBusinessPan(
                orgId, new VerifyBusinessPanRequest("AAACA1234A"), principal
        );
        assertEquals(VerificationStatus.VERIFIED, panKyb.panStatus());

        var gstKyb = verificationService.verifyGstin(
                orgId, new VerifyGstinRequest("27AAACA1234A1Z5"), principal
        );
        assertEquals(VerificationStatus.VERIFIED, gstKyb.gstStatus());

        var regKyb = verificationService.verifyRegistration(
                orgId, new VerifyRegistrationRequest("U72900MH2020PTC123456", null), principal
        );
        assertEquals(VerificationStatus.VERIFIED, regKyb.registrationStatus());

        // 6. Link Authorized Representative
        var repResp = verificationService.addRepresentative(
                orgId, new AddRepresentativeRequest(testUser.getId(), AuthorityRelationshipType.DIRECTOR, "DOC-REF-01", "Primary director"), principal
        );
        assertEquals(AuthorityStatus.VERIFIED, repResp.authorityStatus());
        assertTrue(repResp.kycVerified());

        // 7. Add Bank Account & Verify with Penny Drop
        var bankResp = verificationService.addBankAccount(
                orgId, new AddBankAccountRequest("Acme Technologies Private Limited", "123456789012", "HDFC0000060", "HDFC Bank", "BKC", "CURRENT", true), principal
        );
        assertNotNull(bankResp.id());
        assertEquals("********9012", bankResp.accountNumberMasked());

        var verifiedBank = verificationService.verifyBankAccount(
                orgId, bankResp.id(), principal, "127.0.0.1", "TestAgent"
        );
        assertEquals(BankVerificationStatus.VERIFIED, verifiedBank.verificationStatus());
        assertTrue(verifiedBank.nameMatchScore() >= 85.0);

        // 8. Overall Organization Verification & Platform Gating
        var finxStatus = eligibilityService.getOrganizationVerification(orgId);
        assertTrue(finxStatus.kybVerified());
        assertEquals(VerificationStatus.VERIFIED, finxStatus.overallStatus());
        assertTrue(finxStatus.canActivateDeal());
        assertTrue(finxStatus.canSignAgreement());
        assertFalse(finxStatus.canFundRealTransaction(), "Production money movement must stay disabled");
        assertEquals("BANKING_PARTNER_NOT_CONFIGURED", finxStatus.moneyMovementStatus());
        assertTrue(finxStatus.badge().contains("VERIFIED BUSINESS"));

        // 9. Verify Immutable Audit Events
        var events = eventRepository.findBySubjectTypeAndSubjectIdOrderByTimestampDesc("ORGANIZATION", orgId);
        assertFalse(events.isEmpty(), "Verification events must be recorded in append-only log");
    }

    @Test
    public void testBankMismatchScenario() {
        verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.BANK, orgId, "Penny drop consent", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );

        // Account number ending in 9999 simulates name mismatch
        var bank = verificationService.addBankAccount(
                orgId, new AddBankAccountRequest("Acme Technologies Private Limited", "123456789999", "HDFC0000060", "HDFC Bank", "BKC", "CURRENT", true), principal
        );

        var verified = verificationService.verifyBankAccount(orgId, bank.id(), principal, "127.0.0.1", "TestAgent");
        assertEquals(BankVerificationStatus.FAILED, verified.verificationStatus());
        assertEquals("MISMATCH", verified.nameMatchStatus());
    }

    @Test
    public void testAdminReviewActions() {
        User adminUser = new User("Compliance Officer", "admin." + UUID.randomUUID() + "@finx.com", "pwd", Role.ADMIN, UserStatus.ACTIVE);
        adminUser = userRepository.saveAndFlush(adminUser);
        UserPrincipal adminPrincipal = UserPrincipal.create(adminUser);

        // Start KYC
        verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.PAN, null, "KYC", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );
        verificationService.startKyc(principal);

        var kyc = verificationService.getKycStatus(principal);

        // Admin reviews and overrides
        var reviewed = verificationService.reviewKyc(
                kyc.id(),
                new AdminReviewRequest("APPROVE_REVIEW", "Manually verified identity documentation", "Clean record"),
                adminPrincipal
        );

        assertEquals(VerificationStatus.VERIFIED, reviewed.status());
    }

    @Test
    public void testInvalidPan_ProducesFailedStatus() {
        verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.PAN, null, "Individual KYC Consent", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );
        verificationService.startKyc(principal);

        var panResp = verificationService.verifyIndividualPan(
                new VerifyPanRequest("FAIL00000X", "Rajesh Sharma", LocalDate.of(1990, 1, 1)),
                principal, "127.0.0.1", "TestAgent"
        );

        assertFalse(panResp.valid());
        assertEquals("INVALID", panResp.panStatus());

        var kycStatus = verificationService.getKycStatus(principal);
        assertEquals(VerificationStatus.FAILED, kycStatus.panStatus());
        assertEquals(VerificationStatus.FAILED, kycStatus.status());
    }

    @Test
    public void testIdorProtection_UnauthorizedUserCannotModifyAnotherOrgKyb() {
        User attacker = new User("Attacker", "attacker." + UUID.randomUUID() + "@external.com", "pwd", Role.BUYER, UserStatus.ACTIVE);
        attacker = userRepository.saveAndFlush(attacker);
        UserPrincipal attackerPrincipal = UserPrincipal.create(attacker);

        // Attacker attempts to start KYB on victim's org
        assertThrows(com.finx.exception.UnauthorizedException.class, () ->
                verificationService.startKyb(orgId, attackerPrincipal)
        );

        // Attacker attempts to verify business PAN on victim's org
        assertThrows(com.finx.exception.UnauthorizedException.class, () ->
                verificationService.verifyBusinessPan(orgId, new VerifyBusinessPanRequest("AAACA1234A"), attackerPrincipal)
        );
    }

    @Test
    public void testProductionMoneyMovement_AlwaysBlocked() {
        com.finx.exception.BadRequestException ex = assertThrows(com.finx.exception.BadRequestException.class, () ->
                eligibilityService.validateProductionMoneyMovement()
        );
        assertTrue(ex.getMessage().contains("BANKING_PARTNER_NOT_CONFIGURED"));
    }

    @Test
    public void testDuplicateBankAccount_ThrowsBadRequestException() {
        verificationService.recordConsent(
                new RecordConsentRequest(ConsentType.BANK, orgId, "Bank consent", "1.0"),
                principal, "127.0.0.1", "TestAgent"
        );

        verificationService.addBankAccount(
                orgId, new AddBankAccountRequest("Acme Technologies Private Limited", "112233445566", "HDFC0000060", "HDFC Bank", "BKC", "CURRENT", false),
                principal
        );

        // Attempting to register the same account number again
        assertThrows(com.finx.exception.BadRequestException.class, () ->
                verificationService.addBankAccount(
                        orgId, new AddBankAccountRequest("Acme Technologies Private Limited", "112233445566", "HDFC0000060", "HDFC Bank", "BKC", "CURRENT", false),
                        principal
                )
        );
    }

    @Test
    public void testIncompleteChecks_DoesNotAllowDealActivation() {
        // Org freshly created, no verification done
        com.finx.exception.BadRequestException ex = assertThrows(com.finx.exception.BadRequestException.class, () ->
                eligibilityService.validateDealActivationEligibility(orgId)
        );
        assertTrue(ex.getMessage().contains("KYB"));
    }
}
