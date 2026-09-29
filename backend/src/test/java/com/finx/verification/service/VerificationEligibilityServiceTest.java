package com.finx.verification.service;

import com.finx.exception.BadRequestException;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.verification.entity.AuthorizedRepresentative;
import com.finx.verification.entity.KybProfile;
import com.finx.verification.entity.KycProfile;
import com.finx.verification.enums.AuthorityRelationshipType;
import com.finx.verification.enums.AuthorityStatus;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.provider.mock.MockVerificationProvider;
import com.finx.verification.repository.AuthorizedRepresentativeRepository;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class VerificationEligibilityServiceTest {

    private OrganizationRepository orgRepository;
    private KybProfileRepository kybRepository;
    private KycProfileRepository kycRepository;
    private AuthorizedRepresentativeRepository repRepository;
    private OrganizationBankAccountRepository bankRepository;
    private VerificationEligibilityService eligibilityService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    public void setUp() {
        orgRepository = mock(OrganizationRepository.class);
        kybRepository = mock(KybProfileRepository.class);
        kycRepository = mock(KycProfileRepository.class);
        repRepository = mock(AuthorizedRepresentativeRepository.class);
        bankRepository = mock(OrganizationBankAccountRepository.class);

        MockVerificationProvider mockProvider = new MockVerificationProvider(new NameMatchService());

        eligibilityService = new VerificationEligibilityService(
                orgRepository, kybRepository, kycRepository, repRepository, bankRepository, mockProvider
        );
    }

    @Test
    public void testOverallVerification_AllComponentsVerified() {
        Organization org = new Organization();
        org.setId(orgId);
        org.setLegalName("Acme Technologies Pvt Ltd");
        org.setEntityType(EntityType.PRIVATE_LIMITED);
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));

        KybProfile kyb = new KybProfile();
        kyb.setPanStatus(VerificationStatus.VERIFIED);
        kyb.setGstStatus(VerificationStatus.VERIFIED);
        kyb.setRegistrationStatus(VerificationStatus.VERIFIED);
        when(kybRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(kyb));

        AuthorizedRepresentative rep = new AuthorizedRepresentative(orgId, userId, AuthorityRelationshipType.DIRECTOR);
        rep.setAuthorityStatus(AuthorityStatus.VERIFIED);
        when(repRepository.findByOrganizationId(orgId)).thenReturn(List.of(rep));

        KycProfile userKyc = new KycProfile();
        userKyc.setStatus(VerificationStatus.VERIFIED);
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(userKyc));

        OrganizationBankAccount bank = new OrganizationBankAccount();
        bank.setVerificationStatus(BankVerificationStatus.VERIFIED);
        when(bankRepository.findByOrganizationId(orgId)).thenReturn(List.of(bank));

        var response = eligibilityService.getOrganizationVerification(orgId);

        assertTrue(response.kybVerified());
        assertEquals(VerificationStatus.VERIFIED, response.overallStatus());
        assertTrue(response.canActivateDeal());
        assertTrue(response.canSignAgreement());
        assertFalse(response.canFundRealTransaction(), "Production money movement must remain disabled");
        assertEquals("BANKING_PARTNER_NOT_CONFIGURED", response.moneyMovementStatus());
    }

    @Test
    public void testDealActivation_FailsWhenKybIncomplete() {
        Organization org = new Organization();
        org.setId(orgId);
        org.setLegalName("Incomplete Corp");
        org.setEntityType(EntityType.PRIVATE_LIMITED);
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));

        KybProfile kyb = new KybProfile();
        kyb.setPanStatus(VerificationStatus.VERIFIED);
        kyb.setGstStatus(VerificationStatus.PENDING_REVIEW); // Not verified
        when(kybRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(kyb));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                eligibilityService.validateDealActivationEligibility(orgId));

        assertTrue(ex.getMessage().contains("Deal activation requires verified business status"));
    }

    @Test
    public void testAgreementSigning_RequiresKycAndAuthority() {
        KycProfile unverifiedKyc = new KycProfile();
        unverifiedKyc.setStatus(VerificationStatus.IN_PROGRESS);
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(unverifiedKyc));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                eligibilityService.validateAgreementSigningEligibility(userId, orgId));

        assertTrue(ex.getMessage().contains("Agreement signing requires completed individual KYC"));
    }

    @Test
    public void testProductionMoneyMovement_AlwaysBlocked() {
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                eligibilityService.validateProductionMoneyMovement());

        assertTrue(ex.getMessage().contains("BANKING_PARTNER_NOT_CONFIGURED"));
    }
}
