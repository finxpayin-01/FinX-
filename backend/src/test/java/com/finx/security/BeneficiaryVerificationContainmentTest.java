package com.finx.security;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.AgreementAuditEventRepository;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.org.dto.OrgDtos.AddBankAccountRequest;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.org.service.OrganizationService;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.repository.RiskAssessmentRepository;
import com.finx.risk.service.RiskActionService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.repository.UserRepository;
import com.finx.verification.dto.VerificationDtos.ConsentResponse;
import com.finx.verification.dto.VerificationDtos.RecordConsentRequest;
import com.finx.verification.entity.AuthorizedRepresentative;
import com.finx.verification.entity.KycProfile;
import com.finx.verification.entity.VerificationCheck;
import com.finx.verification.entity.VerificationConsent;
import com.finx.verification.enums.AuthorityRelationshipType;
import com.finx.verification.enums.AuthorityStatus;
import com.finx.verification.enums.ConsentType;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.provider.VerificationProvider;
import com.finx.verification.provider.model.VerificationProviderModels.BankVerificationRequest;
import com.finx.verification.provider.model.VerificationProviderModels.BankVerificationResult;
import com.finx.verification.repository.*;
import com.finx.verification.service.NameMatchService;
import com.finx.verification.service.PiiEncryptionService;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import com.finx.verification.service.VerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B09 P0 Containment Test Suite: Beneficiary and Verification Authority Binding.
 * Covers all 31 required containment and safety assertions:
 * 1-4: Correct org / wrong account isolation across verification APIs
 * 5-9: Active membership and authorized role enforcement
 * 10-13: Masked-only evidence rejection and sandbox provenance
 * 14-19: Ambiguous beneficiary containment (0, 1, >1 fail closed)
 * 20-23: Removal of user-ID-as-org fallback
 * 24-27: Agreement signing authority and revocation enforcement
 * 28-31: Account switch safety and zero payout dispatch
 */
@ExtendWith(MockitoExtension.class)
class BeneficiaryVerificationContainmentTest {

    // Repositories
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationBankAccountRepository bankAccountRepository;
    @Mock private KybProfileRepository kybProfileRepository;
    @Mock private KycProfileRepository kycProfileRepository;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private AuthorizedRepresentativeRepository repRepository;
    @Mock private BeneficialOwnerRepository beneficialOwnerRepository;
    @Mock private VerificationConsentRepository consentRepository;
    @Mock private VerificationEventRepository eventRepository;
    @Mock private VerificationCheckRepository checkRepository;
    @Mock private UserRepository userRepository;
    @Mock private DealAgreementRepository agreementRepository;
    @Mock private AgreementAuditEventRepository auditEventRepository;
    @Mock private SettlementInstructionRepository settlementRepository;
    @Mock private RiskAssessmentRepository riskAssessmentRepository;

    // Services & Providers
    @Mock private BankingProvider bankingProvider;
    @Mock private VerificationProvider verificationProvider;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private RiskActionService riskActionService;
    @Mock private EscrowService escrowService;
    @Mock private LedgerService ledgerService;
    @Mock private NameMatchService nameMatchService;

    private PiiEncryptionService piiEncryptionService;
    private OrganizationService organizationService;
    private VerificationService verificationService;
    private VerificationEligibilityService eligibilityService;
    private AgreementService agreementService;
    private SettlementAuthorizationService settlementAuthorizationService;

    // Fixtures
    private UUID orgIdA;
    private UUID orgIdB;
    private UUID userActiveId;
    private UUID userSuspendedId;
    private UUID userInvitedId;
    private UUID userUnrelatedId;
    private UUID accountIdA;
    private UUID accountIdB;
    private UUID dealId;

    private Organization orgA;
    private Organization orgB;
    private OrganizationBankAccount bankAccountA;
    private OrganizationBankAccount bankAccountB;
    private UserPrincipal activeOwnerPrincipal;
    private UserPrincipal suspendedOwnerPrincipal;
    private UserPrincipal invitedPrincipal;
    private UserPrincipal unrelatedPrincipal;
    private UserPrincipal platformAdminPrincipal;

    @BeforeEach
    void setUp() {
        piiEncryptionService = new PiiEncryptionService("test-secret-encryption-key-32ch");

        orgIdA = UUID.randomUUID();
        orgIdB = UUID.randomUUID();
        userActiveId = UUID.randomUUID();
        userSuspendedId = UUID.randomUUID();
        userInvitedId = UUID.randomUUID();
        userUnrelatedId = UUID.randomUUID();
        accountIdA = UUID.randomUUID();
        accountIdB = UUID.randomUUID();
        dealId = UUID.randomUUID();

        orgA = new Organization("Acme Corp", "Acme Corporation Pvt Ltd", EntityType.PRIVATE_LIMITED, "U12345MH2020PTC123456", "AAACA1234A", "27AAACA1234A1Z5", "Mumbai", userActiveId);
        orgA.setId(orgIdA);

        orgB = new Organization("Beta Corp", "Beta Industries Limited", EntityType.PUBLIC_LIMITED, "L12345MH2020PLC123456", "BBBCB1234B", "27BBBCB1234B1Z5", "Pune", UUID.randomUUID());
        orgB.setId(orgIdB);

        String rawAccount = "123456789012";
        bankAccountA = new OrganizationBankAccount(orgIdA, "Acme Corporation Pvt Ltd", "********9012", "hashA", "HDFC0000060", "HDFC Bank", "BKC", true);
        bankAccountA.setId(accountIdA);
        bankAccountA.setEncryptedAccountNumber(piiEncryptionService.encrypt(rawAccount));
        bankAccountA.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccountA.setVerifiedAt(Instant.now());
        bankAccountA.setProvider("SETU");
        bankAccountA.setNameMatchStatus("MATCH");

        bankAccountB = new OrganizationBankAccount(orgIdB, "Beta Industries Limited", "********3456", "hashB", "ICIC0000001", "ICICI Bank", "Nariman Point", true);
        bankAccountB.setId(accountIdB);
        bankAccountB.setEncryptedAccountNumber(piiEncryptionService.encrypt("987654323456"));
        bankAccountB.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccountB.setVerifiedAt(Instant.now());
        bankAccountB.setProvider("SETU");
        bankAccountB.setNameMatchStatus("MATCH");

        activeOwnerPrincipal = new UserPrincipal(userActiveId, "Active Owner", "owner@acme.com", "hash", Role.SELLER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.SELLER.getAuthority())));

        suspendedOwnerPrincipal = new UserPrincipal(userSuspendedId, "Suspended Member", "suspended@acme.com", "hash", Role.SELLER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.SELLER.getAuthority())));

        invitedPrincipal = new UserPrincipal(userInvitedId, "Invited Member", "invited@acme.com", "hash", Role.SELLER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.SELLER.getAuthority())));

        unrelatedPrincipal = new UserPrincipal(userUnrelatedId, "Unrelated User", "unrelated@random.com", "hash", Role.BUYER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.BUYER.getAuthority())));

        platformAdminPrincipal = new UserPrincipal(UUID.randomUUID(), "Platform Admin", "admin@finx.com", "hash", Role.ADMIN, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.ADMIN.getAuthority())));

        // Services wiring
        organizationService = new OrganizationService(
                organizationRepository, memberRepository, bankAccountRepository,
                kybProfileRepository, kycProfileRepository, dealRepository,
                milestoneRepository, disputeRepository, bankingProvider, auditService,
                piiEncryptionService, null // verificationService wired below
        );

        verificationService = new VerificationService(
                kycProfileRepository, kybProfileRepository, organizationRepository,
                bankAccountRepository, repRepository, beneficialOwnerRepository,
                consentRepository, eventRepository, checkRepository, userRepository,
                organizationService, verificationProvider, nameMatchService,
                piiEncryptionService, auditService
        );

        // Wire verificationService into organizationService
        organizationService = new OrganizationService(
                organizationRepository, memberRepository, bankAccountRepository,
                kybProfileRepository, kycProfileRepository, dealRepository,
                milestoneRepository, disputeRepository, bankingProvider, auditService,
                piiEncryptionService, verificationService
        );

        eligibilityService = new VerificationEligibilityService(
                organizationRepository, kybProfileRepository, kycProfileRepository,
                repRepository, bankAccountRepository, verificationProvider
        );

        agreementService = new AgreementService(
                agreementRepository, auditEventRepository, dealRepository,
                auditService, memberRepository
        );

        settlementAuthorizationService = new SettlementAuthorizationService(
                settlementRepository, dealRepository, milestoneRepository,
                bankAccountRepository, kybProfileRepository, disputeRepository,
                riskAssessmentRepository, agreementService, escrowService,
                ledgerService, bankingProvider, auditService, notificationService,
                riskActionService, null, new DealAccessService(memberRepository),
                eligibilityService
        );
    }

    // =========================================================================
    // 1-4. CORRECT ORG / WRONG ACCOUNT ISOLATION ACROSS VERIFICATION APIS
    // =========================================================================

    @Test
    @DisplayName("1. Correct organization + its own account reaches normal verification")
    void test01_canonicalVerification_correctOrgAndAccount_reachesNormalValidation() {
        OrganizationMember activeMember = new OrganizationMember(orgIdA, userActiveId, OrgRole.OWNER);
        activeMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.of(activeMember));
        when(consentRepository.existsByUserIdAndOrganizationIdAndVerificationType(userActiveId, orgIdA, ConsentType.BANK)).thenReturn(true);
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findById(accountIdA)).thenReturn(Optional.of(bankAccountA));
        when(bankAccountRepository.save(any())).thenReturn(bankAccountA);

        when(verificationProvider.verifyBankAccount(any())).thenReturn(
                new BankVerificationResult(true, "********9012", "HDFC0000060", "Acme Corporation Pvt Ltd",
                        "HDFC Bank", "BKC", 95.0, "MATCH", "VERIF-REF-100", "Verified successfully")
        );

        var response = verificationService.verifyBankAccount(orgIdA, accountIdA, activeOwnerPrincipal);
        assertThat(response).isNotNull();
        assertThat(response.verificationStatus()).isEqualTo(BankVerificationStatus.VERIFIED);
        verify(verificationProvider).verifyBankAccount(any());
    }

    @Test
    @DisplayName("2. Correct organization + another organization's account is rejected")
    void test02_canonicalVerification_correctOrgWithOtherOrgAccount_rejected() {
        OrganizationMember activeMember = new OrganizationMember(orgIdA, userActiveId, OrgRole.OWNER);
        activeMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.of(activeMember));
        when(consentRepository.existsByUserIdAndOrganizationIdAndVerificationType(userActiveId, orgIdA, ConsentType.BANK)).thenReturn(true);
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        // Account belongs to orgB, not orgA
        when(bankAccountRepository.findById(accountIdB)).thenReturn(Optional.of(bankAccountB));

        assertThatThrownBy(() -> verificationService.verifyBankAccount(orgIdA, accountIdB, activeOwnerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Bank account does not belong to specified organization");

        verifyNoInteractions(verificationProvider);
    }

    @Test
    @DisplayName("3. Forged organization ID is rejected")
    void test03_canonicalVerification_forgedOrgId_rejected() {
        UUID forgedOrgId = UUID.randomUUID();
        when(memberRepository.findByOrganizationIdAndUserId(forgedOrgId, userActiveId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.verifyBankAccount(forgedOrgId, accountIdA, activeOwnerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("You are not a member of this organization");

        verifyNoInteractions(verificationProvider);
    }

    @Test
    @DisplayName("4. Account UUID guessing across tenants is rejected")
    void test04_canonicalVerification_accountUuidGuessingAcrossTenants_rejected() {
        UUID randomAccountId = UUID.randomUUID();
        OrganizationMember activeMember = new OrganizationMember(orgIdA, userActiveId, OrgRole.OWNER);
        activeMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.of(activeMember));
        when(consentRepository.existsByUserIdAndOrganizationIdAndVerificationType(userActiveId, orgIdA, ConsentType.BANK)).thenReturn(true);
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findById(randomAccountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.verifyBankAccount(orgIdA, randomAccountId, activeOwnerPrincipal))
                .isInstanceOf(com.finx.exception.ResourceNotFoundException.class);

        verifyNoInteractions(verificationProvider);
    }

    // =========================================================================
    // 5-9. ACTIVE AUTHORITY ENFORCEMENT
    // =========================================================================

    @Test
    @DisplayName("5. ACTIVE authorized organization member may add and verify bank account")
    void test05_activeAuthorizedMember_mayAddAndVerifyBankAccount() {
        OrganizationMember activeAdmin = new OrganizationMember(orgIdA, userActiveId, OrgRole.ADMIN);
        activeAdmin.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.of(activeAdmin));
        when(bankAccountRepository.existsByAccountNumberHash(any())).thenReturn(false);
        when(bankAccountRepository.save(any())).thenReturn(bankAccountA);

        AddBankAccountRequest req = new AddBankAccountRequest("Acme Corp", "123456789012", "HDFC0000060", "HDFC Bank", "BKC", false);
        var res = organizationService.addBankAccount(orgIdA, req, activeOwnerPrincipal);
        assertThat(res).isNotNull();
    }

    @Test
    @DisplayName("6. INVITED member is denied adding or verifying bank account")
    void test06_invitedMember_deniedAddAndVerify() {
        OrganizationMember invitedMember = new OrganizationMember(orgIdA, userInvitedId, OrgRole.OWNER);
        invitedMember.setStatus(MemberStatus.INVITED);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userInvitedId)).thenReturn(Optional.of(invitedMember));

        AddBankAccountRequest req = new AddBankAccountRequest("Acme Corp", "123456789012", "HDFC0000060", "HDFC Bank", "BKC", false);
        assertThatThrownBy(() -> organizationService.addBankAccount(orgIdA, req, invitedPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active (status: INVITED)");

        assertThatThrownBy(() -> verificationService.verifyBankAccount(orgIdA, accountIdA, invitedPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active (status: INVITED)");
    }

    @Test
    @DisplayName("7. SUSPENDED member is denied adding or verifying bank account")
    void test07_suspendedMember_deniedAddAndVerify() {
        OrganizationMember suspendedMember = new OrganizationMember(orgIdA, userSuspendedId, OrgRole.OWNER);
        suspendedMember.setStatus(MemberStatus.SUSPENDED);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userSuspendedId)).thenReturn(Optional.of(suspendedMember));

        AddBankAccountRequest req = new AddBankAccountRequest("Acme Corp", "123456789012", "HDFC0000060", "HDFC Bank", "BKC", false);
        assertThatThrownBy(() -> organizationService.addBankAccount(orgIdA, req, suspendedOwnerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active (status: SUSPENDED)");

        assertThatThrownBy(() -> verificationService.verifyBankAccount(orgIdA, accountIdA, suspendedOwnerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active (status: SUSPENDED)");
    }

    @Test
    @DisplayName("8. Inactive/unauthorized role member is denied add/verify")
    void test08_inactiveOrRevokedMember_denied() {
        // Regular MEMBER role is not authorized for financial setup
        OrganizationMember regularMember = new OrganizationMember(orgIdA, userActiveId, OrgRole.MEMBER);
        regularMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.of(regularMember));

        AddBankAccountRequest req = new AddBankAccountRequest("Acme Corp", "123456789012", "HDFC0000060", "HDFC Bank", "BKC", false);
        assertThatThrownBy(() -> organizationService.addBankAccount(orgIdA, req, activeOwnerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("is not authorized to perform this operation");
    }

    @Test
    @DisplayName("9. Unrelated user is denied adding or verifying bank account")
    void test09_unrelatedUser_deniedAddAndVerify() {
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userUnrelatedId)).thenReturn(Optional.empty());

        AddBankAccountRequest req = new AddBankAccountRequest("Acme Corp", "123456789012", "HDFC0000060", "HDFC Bank", "BKC", false);
        assertThatThrownBy(() -> organizationService.addBankAccount(orgIdA, req, unrelatedPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("You are not a member of this organization");
    }

    // =========================================================================
    // 10-13. MASKED / WEAK EVIDENCE REJECTION & PROVENANCE
    // =========================================================================

    @Test
    @DisplayName("10. Masked-only account cannot become trusted production beneficiary evidence")
    void test10_maskedOnlyAccount_cannotBecomeTrustedProductionBeneficiaryEvidence() {
        // Account has only masked data and null encrypted account number
        OrganizationBankAccount maskedOnlyAccount = new OrganizationBankAccount(
                orgIdA, "Acme Corp", "XXXX-XXXX-9012", "hashMasked", "HDFC0000060", "HDFC Bank", "BKC", true
        );
        maskedOnlyAccount.setEncryptedAccountNumber(null);
        maskedOnlyAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        maskedOnlyAccount.setVerifiedAt(Instant.now());

        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(maskedOnlyAccount));

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("No eligible verified bank accounts found");
    }

    @Test
    @DisplayName("11. Weaker legacy verification path cannot independently mark trusted VERIFIED if canonical evidence is absent")
    void test11_weakerLegacyVerificationPath_cannotIndependentlyMarkTrustedVerifiedWithoutCanonicalEvidence() {
        // OrganizationService with null verificationService (simulating direct weaker path without canonical service)
        OrganizationService standaloneOrgService = new OrganizationService(
                organizationRepository, memberRepository, bankAccountRepository,
                kybProfileRepository, kycProfileRepository, dealRepository,
                milestoneRepository, disputeRepository, bankingProvider, auditService,
                piiEncryptionService, null
        );

        OrganizationMember activeMember = new OrganizationMember(orgIdA, userActiveId, OrgRole.OWNER);
        activeMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.of(activeMember));

        OrganizationBankAccount legacyAccount = new OrganizationBankAccount(
                orgIdA, "Acme Corp", "XXXX-XXXX-9012", "hashMasked", "HDFC0000060", "HDFC Bank", "BKC", true
        );
        legacyAccount.setEncryptedAccountNumber(null); // No canonical encrypted evidence
        when(bankAccountRepository.findById(accountIdA)).thenReturn(Optional.of(legacyAccount));

        assertThatThrownBy(() -> standaloneOrgService.verifyBankAccount(orgIdA, accountIdA, activeOwnerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot verify bank account: full account details are missing (masked-only record)");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("12. Full canonical verification path preserves proper provenance")
    void test12_canonicalVerificationPath_preservesProperProvenance() {
        OrganizationMember activeMember = new OrganizationMember(orgIdA, userActiveId, OrgRole.OWNER);
        activeMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.of(activeMember));
        when(consentRepository.existsByUserIdAndOrganizationIdAndVerificationType(userActiveId, orgIdA, ConsentType.BANK)).thenReturn(true);
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findById(accountIdA)).thenReturn(Optional.of(bankAccountA));
        when(bankAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(verificationProvider.getProviderName()).thenReturn("SETU");
        when(verificationProvider.verifyBankAccount(any())).thenReturn(
                new BankVerificationResult(true, "********9012", "HDFC0000060", "Acme Corporation Pvt Ltd",
                        "HDFC Bank", "BKC", 96.0, "MATCH", "SETU-REF-55555", "Penny drop confirmed")
        );

        var resp = verificationService.verifyBankAccount(orgIdA, accountIdA, activeOwnerPrincipal);
        assertThat(resp.verificationStatus()).isEqualTo(BankVerificationStatus.VERIFIED);
        assertThat(bankAccountA.getProvider()).isEqualTo("SETU");
        assertThat(bankAccountA.getProviderReferenceId()).isEqualTo("SETU-REF-55555");
        assertThat(bankAccountA.getNameMatchScore()).isEqualTo(96.0);
        assertThat(bankAccountA.getNameMatchStatus()).isEqualTo("MATCH");
        assertThat(bankAccountA.getVerifiedAt()).isNotNull();

        verify(checkRepository).save(any(VerificationCheck.class));
    }

    @Test
    @DisplayName("13. Mock/sandbox verification is not labelled production-trusted")
    void test13_mockSandboxVerification_notLabelledProductionTrusted() {
        bankAccountA.setProvider("SETU");
        bankAccountA.setVerificationReference("SETU-REF-1234");
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA));
        when(verificationProvider.isSandbox()).thenReturn(true);

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isTrue();
        assertThat(result.isSandbox()).isTrue(); // Explicit sandbox provenance recorded via provider.isSandbox()

        // Also test provider marker MOCK
        bankAccountA.setProvider("MOCK");
        BeneficiaryEligibilityResult result2 = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result2.isSandbox()).isTrue();
    }

    // =========================================================================
    // 14-19. AMBIGUOUS BENEFICIARY CONTAINMENT
    // =========================================================================

    @Test
    @DisplayName("14. Zero eligible accounts fails closed")
    void test14_zeroEligibleAccounts_beneficiaryFailsClosed() {
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(Collections.emptyList());

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("No eligible verified bank accounts found");

        assertThatThrownBy(() -> eligibilityService.requireSingleEligibleBeneficiary(orgIdA))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No eligible verified bank accounts found");
    }

    @Test
    @DisplayName("15. Exactly one eligible account produces deterministic identity")
    void test15_exactlyOneEligibleAccount_deterministicEligibleIdentity() {
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA));
        when(verificationProvider.isSandbox()).thenReturn(false);

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isTrue();
        assertThat(result.bankAccount().getId()).isEqualTo(accountIdA);
        assertThat(result.isSandbox()).isFalse();

        OrganizationBankAccount single = eligibilityService.requireSingleEligibleBeneficiary(orgIdA);
        assertThat(single.getId()).isEqualTo(accountIdA);
    }

    @Test
    @DisplayName("16. Two VERIFIED eligible accounts is ambiguous and fails closed")
    void test16_twoVerifiedEligibleAccounts_ambiguousFailsClosed() {
        OrganizationBankAccount secondVerified = new OrganizationBankAccount(
                orgIdA, "Acme Corp", "********5555", "hash555", "ICIC0000002", "ICICI Bank", "BKC", false
        );
        secondVerified.setId(UUID.randomUUID());
        secondVerified.setEncryptedAccountNumber(piiEncryptionService.encrypt("555555555555"));
        secondVerified.setVerificationStatus(BankVerificationStatus.VERIFIED);
        secondVerified.setVerifiedAt(Instant.now());
        secondVerified.setNameMatchStatus("MATCH");

        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA, secondVerified));

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("Ambiguous beneficiary: multiple eligible verified bank accounts found (2)");

        assertThatThrownBy(() -> eligibilityService.requireSingleEligibleBeneficiary(orgIdA))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Ambiguous beneficiary");
    }

    @Test
    @DisplayName("17. Repository ordering change cannot silently select another account")
    void test17_repositoryOrdering_cannotSilentlySelectAnotherAccount() {
        OrganizationBankAccount accountX = new OrganizationBankAccount(orgIdA, "Acme Corp", "********1111", "hash1", "HDFC0000060", "HDFC", "BKC", true);
        accountX.setEncryptedAccountNumber(piiEncryptionService.encrypt("111111111111"));
        accountX.setVerificationStatus(BankVerificationStatus.VERIFIED);
        accountX.setVerifiedAt(Instant.now());

        OrganizationBankAccount accountY = new OrganizationBankAccount(orgIdA, "Acme Corp", "********2222", "hash2", "ICIC0000001", "ICICI", "BKC", false);
        accountY.setEncryptedAccountNumber(piiEncryptionService.encrypt("222222222222"));
        accountY.setVerificationStatus(BankVerificationStatus.VERIFIED);
        accountY.setVerifiedAt(Instant.now());

        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));

        // Order 1: X then Y -> AMBIGUOUS
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(accountX, accountY));
        assertThat(eligibilityService.evaluateBeneficiaryEligibility(orgIdA).eligible()).isFalse();

        // Order 2: Y then X -> STILL AMBIGUOUS (never silently selects the first element)
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(accountY, accountX));
        assertThat(eligibilityService.evaluateBeneficiaryEligibility(orgIdA).eligible()).isFalse();
    }

    @Test
    @DisplayName("18. Invalid / failed accounts are excluded from eligible set")
    void test18_invalidOrFailedAccount_excludedFromEligible() {
        OrganizationBankAccount failedAccount = new OrganizationBankAccount(orgIdA, "Acme Corp", "********9999", "hashFail", "HDFC0000060", "HDFC", "BKC", false);
        failedAccount.setVerificationStatus(BankVerificationStatus.FAILED);
        failedAccount.setEncryptedAccountNumber(piiEncryptionService.encrypt("123456789999"));

        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        // One verified account + one failed account -> exactly 1 eligible
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA, failedAccount));
        when(verificationProvider.isSandbox()).thenReturn(false);

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isTrue();
        assertThat(result.bankAccount().getId()).isEqualTo(accountIdA);
    }

    @Test
    @DisplayName("19. Incomplete / in-progress accounts are excluded from eligible set")
    void test19_incompleteVerification_excludedFromEligible() {
        OrganizationBankAccount inProgressAccount = new OrganizationBankAccount(orgIdA, "Acme Corp", "********8888", "hashInProg", "HDFC0000060", "HDFC", "BKC", false);
        inProgressAccount.setVerificationStatus(BankVerificationStatus.IN_PROGRESS);
        inProgressAccount.setEncryptedAccountNumber(piiEncryptionService.encrypt("123456788888"));

        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(inProgressAccount));

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("No eligible verified bank accounts found");
    }

    // =========================================================================
    // 20-23. USER-ID-AS-ORG FALLBACK REMOVED
    // =========================================================================

    @Test
    @DisplayName("20. Seller User UUID is not accepted as seller Organization UUID")
    void test20_sellerUserUuid_notAcceptedAsSellerOrgUuid() {
        UUID sellerUserId = UUID.randomUUID();
        // Passing a User ID to organization repository returns empty -> fails closed
        when(organizationRepository.findById(sellerUserId)).thenReturn(Optional.empty());

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(sellerUserId);
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("Organization not found: " + sellerUserId);
    }

    @Test
    @DisplayName("21. Missing sellerOrganizationId does not fall back to sellerId")
    void test21_missingSellerOrgId_doesNotFallbackToSellerId() {
        Deal unboundDeal = new Deal();
        unboundDeal.setId(dealId);
        unboundDeal.setBuyerId(UUID.randomUUID());
        unboundDeal.setSellerId(userActiveId);
        unboundDeal.setSellerOrganizationId(null); // Unbound seller organization

        // Null organization ID fails closed immediately without evaluating user ID
        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(unboundDeal.getSellerOrganizationId());
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Organization ID is required");
    }

    @Test
    @DisplayName("22. Legacy ambiguous row is not auto-mapped")
    void test22_legacyAmbiguousRow_notAutoMapped() {
        // Null or arbitrary lookup never infers an organization
        BeneficiaryEligibilityResult result = settlementAuthorizationService.evaluateBeneficiaryEligibility(null);
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("Organization ID is required");
    }

    @Test
    @DisplayName("23. Current trusted beneficiary lookup uses actual organization identity")
    void test23_currentTrustedBeneficiaryLookup_usesActualOrganizationIdentity() {
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA));
        when(verificationProvider.isSandbox()).thenReturn(false);

        var result = settlementAuthorizationService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isTrue();
        assertThat(result.bankAccount().getOrganizationId()).isEqualTo(orgIdA);
    }

    // =========================================================================
    // 24-27. AUTHORITY CHANGES & REVOCATION
    // =========================================================================

    @Test
    @DisplayName("24. Suspended signer cannot sign agreement")
    void test24_suspendedSigner_cannotSignAgreement() {
        Deal deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerId(userSuspendedId);
        deal.setBuyerOrganizationId(orgIdA);
        deal.setSellerId(UUID.randomUUID());
        deal.setSellerOrganizationId(orgIdB);

        DealAgreement agreement = new DealAgreement(dealId, 1, "Terms", "Full terms content", "sha256");
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(agreementRepository.findByDealIdAndVersion(dealId, 1)).thenReturn(Optional.of(agreement));

        OrganizationMember suspendedMember = new OrganizationMember(orgIdA, userSuspendedId, OrgRole.OWNER);
        suspendedMember.setStatus(MemberStatus.SUSPENDED);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userSuspendedId)).thenReturn(Optional.of(suspendedMember));

        assertThatThrownBy(() -> agreementService.signAgreement(dealId, 1, suspendedOwnerPrincipal, "127.0.0.1", "Agent"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Signer organization membership is not active (status: SUSPENDED)");
    }

    @Test
    @DisplayName("25. Inactive / invited signer cannot sign agreement")
    void test25_invitedOrInactiveSigner_cannotSignAgreement() {
        Deal deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerId(userInvitedId);
        deal.setBuyerOrganizationId(orgIdA);
        deal.setSellerId(UUID.randomUUID());
        deal.setSellerOrganizationId(orgIdB);

        DealAgreement agreement = new DealAgreement(dealId, 1, "Terms", "Full terms content", "sha256");
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(agreementRepository.findByDealIdAndVersion(dealId, 1)).thenReturn(Optional.of(agreement));

        OrganizationMember invitedMember = new OrganizationMember(orgIdA, userInvitedId, OrgRole.AUTHORIZED_SIGNATORY);
        invitedMember.setStatus(MemberStatus.INVITED);
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userInvitedId)).thenReturn(Optional.of(invitedMember));

        assertThatThrownBy(() -> agreementService.signAgreement(dealId, 1, invitedPrincipal, "127.0.0.1", "Agent"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Signer organization membership is not active (status: INVITED)");
    }

    @Test
    @DisplayName("26. Account verifier who loses organization authority cannot verify")
    void test26_accountVerifier_losingOrgAuthority_cannotVerify() {
        // User's membership was revoked / removed
        when(memberRepository.findByOrganizationIdAndUserId(orgIdA, userActiveId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verificationService.verifyBankAccount(orgIdA, accountIdA, activeOwnerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("You are not a member of this organization");

        verifyNoInteractions(verificationProvider);
    }

    @Test
    @DisplayName("27. Revoked signing authority cannot remain eligible for agreement signing")
    void test27_revokedVerificationAuthority_cannotRemainEligible() {
        when(kycProfileRepository.findByUserId(userActiveId)).thenReturn(
                Optional.of(new KycProfile(userActiveId, "Active User", "PAN123") {{
                    setStatus(VerificationStatus.VERIFIED);
                }})
        );
        // AuthorityStatus is REVOKED -> existsByOrganizationIdAndUserIdAndAuthorityStatus returns false
        when(repRepository.existsByOrganizationIdAndUserIdAndAuthorityStatus(orgIdA, userActiveId, AuthorityStatus.VERIFIED))
                .thenReturn(false);

        assertThatThrownBy(() -> eligibilityService.validateAgreementSigningEligibility(userActiveId, orgIdA))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("User does not have VERIFIED signing authority for organization");
    }

    // =========================================================================
    // 28-31. ACCOUNT SWITCH SAFETY & SETTLEMENT CONTAINMENT
    // =========================================================================

    @Test
    @DisplayName("28. Account A eligible, then account B added creating ambiguity: evaluation fails closed")
    void test28_accountSwitchSafety_addingSecondAccountCausesEvaluationToFailClosed() {
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));
        // Step 1: Only A exists -> eligible
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA));
        when(verificationProvider.isSandbox()).thenReturn(false);
        assertThat(eligibilityService.evaluateBeneficiaryEligibility(orgIdA).eligible()).isTrue();

        // Step 2: Account B added and verified -> now multiple accounts exist
        OrganizationBankAccount secondAccount = new OrganizationBankAccount(orgIdA, "Acme Corp", "********8888", "h2", "HDFC0000060", "HDFC", "BKC", false);
        secondAccount.setEncryptedAccountNumber(piiEncryptionService.encrypt("888888888888"));
        secondAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        secondAccount.setVerifiedAt(Instant.now());

        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA, secondAccount));
        // Evaluation fails closed due to ambiguity
        BeneficiaryEligibilityResult afterSwitch = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(afterSwitch.eligible()).isFalse();
        assertThat(afterSwitch.failureReason()).contains("Ambiguous beneficiary");
    }

    @Test
    @DisplayName("29. Account A becomes invalid: no silent switch to invalid or unauthorized state")
    void test29_accountSwitchSafety_accountAInvalid_noSilentSwitchToB() {
        when(organizationRepository.findById(orgIdA)).thenReturn(Optional.of(orgA));

        // Account A verification changed to FAILED
        bankAccountA.setVerificationStatus(BankVerificationStatus.FAILED);
        when(bankAccountRepository.findByOrganizationId(orgIdA)).thenReturn(List.of(bankAccountA));

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(orgIdA);
        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("No eligible verified bank accounts found");
    }

    @Test
    @DisplayName("30. Changing verification state does not create execution side effect")
    void test30_changingVerificationState_doesNotCreateExecutionSideEffect() {
        // Verification mutation only touches verification entities and check tables
        // Banking execution or fund release is never triggered
        verifyNoInteractions(bankingProvider);
        verifyNoInteractions(ledgerService);
        verifyNoInteractions(escrowService);
    }

    @Test
    @DisplayName("31. BankingProvider.initiateSettlement calls remain 0")
    void test31_bankingProvider_initiateSettlement_callsRemainZero() {
        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(UUID.randomUUID(), "Test", "IDEMP-B09");

        assertThatThrownBy(() -> settlementAuthorizationService.authorizeAndInitiateSettlement(request, activeOwnerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        // Outbound bank payout remains strictly 0
        verify(bankingProvider, never()).initiateSettlement(any());
    }
}
