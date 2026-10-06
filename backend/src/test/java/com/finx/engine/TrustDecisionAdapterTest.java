package com.finx.engine;

import com.finx.engine.adapter.TrustDecisionAdapter;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.enums.OrgEnums.OrgVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.verification.entity.AuthorizedRepresentative;
import com.finx.verification.entity.KybProfile;
import com.finx.verification.entity.KycProfile;
import com.finx.verification.enums.AuthorityRelationshipType;
import com.finx.verification.enums.AuthorityStatus;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.AuthorizedRepresentativeRepository;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import com.finx.verification.service.VerificationEligibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrustDecisionAdapterTest {

    @Mock private OrganizationRepository orgRepository;
    @Mock private KybProfileRepository kybRepository;
    @Mock private KycProfileRepository kycRepository;
    @Mock private AuthorizedRepresentativeRepository repRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationBankAccountRepository bankRepository;
    @Mock private VerificationEligibilityService eligibilityService;

    private TrustDecisionAdapter adapter;

    private UUID orgId;
    private UUID userId;
    private EvaluationContext context;
    private Organization organization;
    private KybProfile kybProfile;
    private AuthorizedRepresentative rep;
    private OrganizationMember member;
    private KycProfile kycProfile;
    private OrganizationBankAccount bankAccount;

    @BeforeEach
    void setUp() {
        adapter = new TrustDecisionAdapter(
                orgRepository,
                kybRepository,
                kycRepository,
                repRepository,
                memberRepository,
                bankRepository,
                eligibilityService
        );

        orgId = UUID.randomUUID();
        userId = UUID.randomUUID();

        context = new EvaluationContext(
                UUID.randomUUID(),
                orgId,
                UUID.randomUUID(),
                DecisionSubjectType.ORGANIZATION.name(),
                orgId,
                Instant.now()
        );

        organization = new Organization("Acme Corp", "Acme Corp Ltd", EntityType.PRIVATE_LIMITED,
                null, null, null, null, userId);
        organization.setId(orgId);
        organization.setVerificationStatus(OrgVerificationStatus.VERIFIED);

        kybProfile = new KybProfile(orgId, "ABCDE1234F", "27ABCDE1234F1Z5", "U12345MH2020PTC123456", null);
        kybProfile.setStatus(VerificationStatus.VERIFIED);
        kybProfile.setPanStatus(VerificationStatus.VERIFIED);
        kybProfile.setGstStatus(VerificationStatus.VERIFIED);
        kybProfile.setRegistrationStatus(VerificationStatus.VERIFIED);
        kybProfile.setVerifiedAt(Instant.now().minusSeconds(86400));

        rep = new AuthorizedRepresentative(orgId, userId, AuthorityRelationshipType.DIRECTOR);
        rep.setAuthorityStatus(AuthorityStatus.VERIFIED);
        rep.setVerifiedAt(Instant.now().minusSeconds(86400));

        member = new OrganizationMember(orgId, userId, OrgRole.OWNER);
        member.setStatus(MemberStatus.ACTIVE);

        kycProfile = new KycProfile(userId, "John Doe", "ABCDE1234F");
        kycProfile.setStatus(VerificationStatus.VERIFIED);
        kycProfile.setPanStatus(VerificationStatus.VERIFIED);
        kycProfile.setIdentityStatus(VerificationStatus.VERIFIED);
        kycProfile.setVerifiedAt(Instant.now().minusSeconds(86400));

        bankAccount = new OrganizationBankAccount(orgId, "Acme Corp", "****1234", "hash123", "HDFC0000001", "HDFC Bank", "Main Branch", true);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount.setEncryptedAccountNumber("enc_acc_1234");
        bankAccount.setVerifiedAt(Instant.now().minusSeconds(86400));
        bankAccount.setNameMatchStatus("MATCH");
    }

    private void setupFullVerifiedMock() {
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(kybRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(kybProfile));
        when(repRepository.findByOrganizationId(orgId)).thenReturn(List.of(rep));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kycProfile));
        when(bankRepository.findByOrganizationId(orgId)).thenReturn(List.of(bankAccount));
        when(eligibilityService.evaluateBeneficiaryEligibility(orgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(true, bankAccount, null, false));
    }

    @Test
    @DisplayName("1. Engine type is TRUST")
    void engineTypeIsTrust() {
        assertThat(adapter.type()).isEqualTo(EngineType.TRUST);
    }

    @Test
    @DisplayName("2. Subject type is ORGANIZATION and Subject ID is organization UUID")
    void subjectIsOrganization() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.subjectType()).isEqualTo("ORGANIZATION");
        assertThat(command.subjectId()).isEqualTo(orgId);
    }

    @Test
    @DisplayName("3. Owner is evaluated organization UUID")
    void ownerIsEvaluatedOrganization() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.organizationId()).isEqualTo(orgId);
    }

    @Test
    @DisplayName("4. Fully verified identity + authority + beneficiary -> ALLOW")
    void fullyVerifiedAllows() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.reasonCodes()).containsExactly("TRUST_COUNTERPARTY_VERIFIED");
    }

    @Test
    @DisplayName("5. Numeric trust score is null")
    void scoreIsNull() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.score()).isNull();
    }

    @Test
    @DisplayName("6. KYB hard failure -> BLOCK with TRUST_KYB_FAILED")
    void kybHardFailureBlocks() {
        kybProfile.setStatus(VerificationStatus.FAILED);
        kybProfile.setPanStatus(VerificationStatus.FAILED);
        setupFullVerifiedMock();

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(command.reasonCodes()).contains("TRUST_KYB_FAILED");
    }

    @Test
    @DisplayName("7. KYB missing/pending -> REVIEW with TRUST_KYB_PENDING")
    void kybPendingReviews() {
        kybProfile.setStatus(VerificationStatus.PENDING_REVIEW);
        kybProfile.setPanStatus(VerificationStatus.PENDING_REVIEW);
        setupFullVerifiedMock();

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_KYB_PENDING");
    }

    @Test
    @DisplayName("8. Explicit expired KYB -> REVIEW with TRUST_VERIFICATION_EXPIRED")
    void expiredKybReviews() {
        kybProfile.setExpiresAt(Instant.now().minusSeconds(3600));
        setupFullVerifiedMock();

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_VERIFICATION_EXPIRED");
    }

    @Test
    @DisplayName("9. Unverified representative -> REVIEW with TRUST_SIGNATORY_UNVERIFIED")
    void unverifiedRepresentativeReviews() {
        rep.setAuthorityStatus(AuthorityStatus.PENDING);
        setupFullVerifiedMock();

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_SIGNATORY_UNVERIFIED");
    }

    @Test
    @DisplayName("10. Unverified KYC on representative -> REVIEW with TRUST_SIGNATORY_UNVERIFIED")
    void unverifiedKycReviews() {
        kycProfile.setStatus(VerificationStatus.IN_PROGRESS);
        setupFullVerifiedMock();

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_SIGNATORY_UNVERIFIED");
    }

    @Test
    @DisplayName("11. Inactive signatory membership -> REVIEW with TRUST_MEMBERSHIP_INACTIVE")
    void inactiveSignatoryMembershipReviews() {
        member.setStatus(MemberStatus.SUSPENDED);
        setupFullVerifiedMock();

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_MEMBERSHIP_INACTIVE");
    }

    @Test
    @DisplayName("12. Missing bank account -> REVIEW with TRUST_BENEFICIARY_UNVERIFIED")
    void missingBankAccountReviews() {
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(kybRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(kybProfile));
        when(repRepository.findByOrganizationId(orgId)).thenReturn(List.of(rep));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kycProfile));
        when(bankRepository.findByOrganizationId(orgId)).thenReturn(List.of());
        when(eligibilityService.evaluateBeneficiaryEligibility(orgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "No accounts", false));

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_BENEFICIARY_UNVERIFIED");
    }

    @Test
    @DisplayName("13. Masked-only bank account without encryption cannot ALLOW")
    void maskedOnlyBankAccountCannotAllow() {
        bankAccount.setEncryptedAccountNumber(null);
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(kybRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(kybProfile));
        when(repRepository.findByOrganizationId(orgId)).thenReturn(List.of(rep));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kycProfile));
        when(bankRepository.findByOrganizationId(orgId)).thenReturn(List.of(bankAccount));
        when(eligibilityService.evaluateBeneficiaryEligibility(orgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "No eligible encrypted account", false));

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_BENEFICIARY_UNVERIFIED");
    }

    @Test
    @DisplayName("14. Beneficiary name mismatch -> BLOCK with TRUST_BENEFICIARY_MISMATCH")
    void beneficiaryNameMismatchBlocks() {
        bankAccount.setNameMatchStatus("MISMATCH");
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(kybRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(kybProfile));
        when(repRepository.findByOrganizationId(orgId)).thenReturn(List.of(rep));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kycProfile));
        when(bankRepository.findByOrganizationId(orgId)).thenReturn(List.of(bankAccount));
        when(eligibilityService.evaluateBeneficiaryEligibility(orgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "Name mismatch", false));

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(command.reasonCodes()).contains("TRUST_BENEFICIARY_MISMATCH");
    }

    @Test
    @DisplayName("15. Multiple eligible beneficiaries -> REVIEW with TRUST_BENEFICIARY_AMBIGUOUS")
    void ambiguousBeneficiariesReviews() {
        OrganizationBankAccount secondAcc = new OrganizationBankAccount(orgId, "Acme Corp", "****5678", "hash567", "ICIC0000001", "ICICI Bank", "Main Branch", false);
        secondAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        secondAcc.setEncryptedAccountNumber("enc_acc_5678");
        secondAcc.setVerifiedAt(Instant.now().minusSeconds(86400));
        secondAcc.setNameMatchStatus("MATCH");

        when(orgRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(kybRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(kybProfile));
        when(repRepository.findByOrganizationId(orgId)).thenReturn(List.of(rep));
        when(memberRepository.findByOrganizationIdAndUserId(orgId, userId)).thenReturn(Optional.of(member));
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kycProfile));
        when(bankRepository.findByOrganizationId(orgId)).thenReturn(List.of(bankAccount, secondAcc));
        when(eligibilityService.evaluateBeneficiaryEligibility(orgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "Ambiguous beneficiary: multiple eligible accounts", false));

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("TRUST_BENEFICIARY_AMBIGUOUS");
    }

    @Test
    @DisplayName("16. Zero transaction history does not BLOCK")
    void zeroTransactionHistoryDoesNotBlock() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.decision()).isNotEqualTo(DecisionStatus.BLOCK);
    }

    @Test
    @DisplayName("17. Organization not found -> BLOCK with TRUST_ORGANIZATION_NOT_FOUND")
    void organizationNotFoundBlocks() {
        when(orgRepository.findById(orgId)).thenReturn(Optional.empty());

        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(command.reasonCodes()).contains("TRUST_ORGANIZATION_NOT_FOUND");
    }

    @Test
    @DisplayName("18. Persisted evidence references exist with correct source types")
    void persistedEvidenceReferencesExist() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.evidenceReferences()).isNotEmpty();
        assertThat(command.evidenceReferences()).extracting("sourceType")
                .contains("ORGANIZATION", "KYB_PROFILE", "AUTHORIZED_REPRESENTATIVE",
                        "ORGANIZATION_MEMBER", "KYC_PROFILE", "ORGANIZATION_BANK_ACCOUNT");
    }

    @Test
    @DisplayName("19. Deterministic input fingerprint generated")
    void deterministicInputFingerprint() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command1 = adapter.evaluate(context);
        RecordEngineDecisionCommand command2 = adapter.evaluate(context);
        assertThat(command1.inputFingerprint()).isNotBlank();
        assertThat(command1.inputFingerprint()).isEqualTo(command2.inputFingerprint());
    }

    @Test
    @DisplayName("20. No sensitive account data in explanation")
    void noSensitiveDataInExplanation() {
        setupFullVerifiedMock();
        RecordEngineDecisionCommand command = adapter.evaluate(context);
        assertThat(command.explanation()).doesNotContain("enc_acc_1234");
        assertThat(command.explanation()).doesNotContain("ABCDE1234F");
        assertThat(command.explanation()).doesNotContain("27ABCDE1234F1Z5");
    }
}
