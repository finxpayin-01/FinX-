package com.finx.security;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.PaymentInstructionRequest;
import com.finx.banking.dto.BankingDtos.PaymentInstructionResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.deal.dto.request.CreateDealRequest;
import com.finx.deal.dto.response.DealResponse;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.deal.service.DealService;
import com.finx.dispute.dto.response.DisputeResponse;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.dispute.service.DisputeService;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.org.dto.OrgDtos.VerifiedBusinessProfile;
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
import com.finx.org.service.OrganizationService;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.CreateInstructionRequest;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PaymentInstructionResponse;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.SettlementResponse;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.ReconciliationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.common.enums.UserStatus;
import com.finx.user.repository.UserRepository;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TenantIsolationContainmentTest {

    // Repositories
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationBankAccountRepository bankAccountRepository;
    @Mock private KybProfileRepository kybProfileRepository;
    @Mock private KycProfileRepository kycProfileRepository;
    @Mock private DealRepository dealRepository;
    @Mock private UserRepository userRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private PaymentInstructionRepository instructionRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private SettlementInstructionRepository settlementRepository;
    @Mock private ReconciliationRunRepository runRepository;
    @Mock private ReconciliationItemRepository itemRepository;

    // Services
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EscrowService escrowService;
    @Mock private AgreementService agreementService;
    @Mock private LedgerService ledgerService;
    @Mock private BankingProvider bankingProvider;

    private DealAccessService dealAccessService;
    private OrganizationService organizationService;
    private DealService dealService;
    private PaymentOrchestrationService paymentOrchestrationService;
    private SettlementAuthorizationService settlementAuthorizationService;
    private DisputeService disputeService;
    private ReconciliationService reconciliationService;

    // Fixtures
    private UUID orgAId;
    private UUID orgBId;
    private UUID buyerUserId;
    private UUID sellerUserId;
    private UUID pmUserId;
    private UUID unassignedPmUserId;
    private UUID outsiderUserId;
    private UUID dealId;

    private User buyerUser;
    private User sellerUser;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;
    private UserPrincipal assignedPmPrincipal;
    private UserPrincipal unassignedPmPrincipal;
    private UserPrincipal outsiderPrincipal;
    private UserPrincipal platformAdminPrincipal;
    private UserPrincipal platformFinancePrincipal;

    private Organization orgA;
    private Organization orgB;
    private Deal boundDeal;
    private Deal unboundDeal;

    @BeforeEach
    void setUp() {
        dealAccessService = new DealAccessService(memberRepository);

        organizationService = new OrganizationService(
                organizationRepository, memberRepository, bankAccountRepository,
                kybProfileRepository, kycProfileRepository, dealRepository,
                milestoneRepository, disputeRepository, bankingProvider, auditService
        );

        dealService = new DealService(
                dealRepository, userRepository, auditService, notificationService,
                eventPublisher, escrowService, null, organizationRepository,
                memberRepository, dealAccessService
        );

        paymentOrchestrationService = new PaymentOrchestrationService(
                instructionRepository, transactionRepository, dealRepository,
                milestoneRepository, bankingProvider, ledgerService, escrowService,
                agreementService, auditService, notificationService, dealAccessService
        );

        settlementAuthorizationService = new SettlementAuthorizationService(
                settlementRepository, dealRepository, milestoneRepository,
                bankAccountRepository, kybProfileRepository, disputeRepository,
                null, agreementService, escrowService, ledgerService, bankingProvider,
                auditService, notificationService, null, null, dealAccessService
        );

        disputeService = new DisputeService(
                disputeRepository, dealRepository, auditService, notificationService, dealAccessService
        );

        reconciliationService = new ReconciliationService(
                runRepository, itemRepository, transactionRepository,
                settlementRepository, bankingProvider, auditService
        );

        // Identifiers
        orgAId = UUID.randomUUID();
        orgBId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        sellerUserId = UUID.randomUUID();
        pmUserId = UUID.randomUUID();
        unassignedPmUserId = UUID.randomUUID();
        outsiderUserId = UUID.randomUUID();
        dealId = UUID.randomUUID();

        // Organizations
        orgA = new Organization("Org A", "Org A Legal", EntityType.PRIVATE_LIMITED, "REG-A", "TAX-A", "GST-A", "Addr A", buyerUserId);
        orgA.setId(orgAId);
        orgA.setVerificationStatus(OrgVerificationStatus.VERIFIED);

        orgB = new Organization("Org B", "Org B Legal", EntityType.PRIVATE_LIMITED, "REG-B", "TAX-B", "GST-B", "Addr B", sellerUserId);
        orgB.setId(orgBId);
        orgB.setVerificationStatus(OrgVerificationStatus.VERIFIED);

        when(organizationRepository.findById(orgAId)).thenReturn(Optional.of(orgA));
        when(organizationRepository.findById(orgBId)).thenReturn(Optional.of(orgB));

        // Users
        buyerUser = new User("Buyer One", "buyer@orga.com", "hash", Role.BUYER, UserStatus.ACTIVE);
        buyerUser.setId(buyerUserId);

        sellerUser = new User("Seller One", "seller@orgb.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        sellerUser.setId(sellerUserId);

        when(userRepository.findById(buyerUserId)).thenReturn(Optional.of(buyerUser));
        when(userRepository.findById(sellerUserId)).thenReturn(Optional.of(sellerUser));

        // Principals
        buyerPrincipal = makePrincipal(buyerUserId, "buyer@orga.com", Role.BUYER);
        sellerPrincipal = makePrincipal(sellerUserId, "seller@orgb.com", Role.SELLER);
        assignedPmPrincipal = makePrincipal(pmUserId, "pm@finx.com", Role.PROJECT_MANAGER);
        unassignedPmPrincipal = makePrincipal(unassignedPmUserId, "unassigned-pm@finx.com", Role.PROJECT_MANAGER);
        outsiderPrincipal = makePrincipal(outsiderUserId, "outsider@other.com", Role.BUYER);
        platformAdminPrincipal = makePrincipal(UUID.randomUUID(), "admin@finx.com", Role.ADMIN);
        platformFinancePrincipal = makePrincipal(UUID.randomUUID(), "finance@finx.com", Role.FINANCE);

        // Deals
        boundDeal = new Deal("Bound Deal", "Desc", buyerUserId, sellerUserId, new BigDecimal("100000.00"), "INR", DealStatus.ACCEPTED);
        boundDeal.setId(dealId);
        boundDeal.setBuyerOrganizationId(orgAId);
        boundDeal.setSellerOrganizationId(orgBId);
        boundDeal.setProjectManagerId(pmUserId);

        unboundDeal = new Deal("Unbound Deal", "Desc", buyerUserId, sellerUserId, new BigDecimal("50000.00"), "INR", DealStatus.ACCEPTED);
        unboundDeal.setId(UUID.randomUUID());
        unboundDeal.setBuyerOrganizationId(null);
        unboundDeal.setSellerOrganizationId(null);

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(boundDeal));
        when(dealRepository.findById(unboundDeal.getId())).thenReturn(Optional.of(unboundDeal));

        // Default active memberships for Org A and Org B
        OrganizationMember activeBuyerMember = new OrganizationMember(orgAId, buyerUserId, OrgRole.OWNER);
        activeBuyerMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgAId, buyerUserId)).thenReturn(Optional.of(activeBuyerMember));

        OrganizationMember activeSellerMember = new OrganizationMember(orgBId, sellerUserId, OrgRole.OWNER);
        activeSellerMember.setStatus(MemberStatus.ACTIVE);
        when(memberRepository.findByOrganizationIdAndUserId(orgBId, sellerUserId)).thenReturn(Optional.of(activeSellerMember));
    }

    private UserPrincipal makePrincipal(UUID id, String email, Role role) {
        return new UserPrincipal(id, "Test User", email, "hash", role, UserStatus.ACTIVE,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role.getAuthority())));
    }


    // =========================================================================
    // ORGANIZATION MEMBERSHIP (Tests 1-4)
    // =========================================================================

    @Test
    @DisplayName("01. ACTIVE member accepted in organization validation")
    void test01_activeMemberAccepted() {
        organizationService.validateOrgMembership(orgAId, buyerPrincipal);
        organizationService.validateOrgRole(orgAId, buyerPrincipal, Set.of(OrgRole.OWNER));
    }

    @Test
    @DisplayName("02. INVITED member denied organization authority")
    void test02_invitedMemberDenied() {
        UUID invitedUserId = UUID.randomUUID();
        UserPrincipal invitedPrincipal = makePrincipal(invitedUserId, "invited@orga.com", Role.BUYER);
        OrganizationMember invitedMember = new OrganizationMember(orgAId, invitedUserId, OrgRole.MEMBER);
        invitedMember.setStatus(MemberStatus.INVITED);
        when(memberRepository.findByOrganizationIdAndUserId(orgAId, invitedUserId)).thenReturn(Optional.of(invitedMember));

        assertThatThrownBy(() -> organizationService.validateOrgMembership(orgAId, invitedPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active");

        assertThatThrownBy(() -> organizationService.validateOrgRole(orgAId, invitedPrincipal, Set.of(OrgRole.MEMBER)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active");
    }

    @Test
    @DisplayName("03. SUSPENDED member denied organization authority")
    void test03_suspendedMemberDenied() {
        UUID suspendedUserId = UUID.randomUUID();
        UserPrincipal suspendedPrincipal = makePrincipal(suspendedUserId, "suspended@orga.com", Role.BUYER);
        OrganizationMember suspendedMember = new OrganizationMember(orgAId, suspendedUserId, OrgRole.MEMBER);
        suspendedMember.setStatus(MemberStatus.SUSPENDED);
        when(memberRepository.findByOrganizationIdAndUserId(orgAId, suspendedUserId)).thenReturn(Optional.of(suspendedMember));

        assertThatThrownBy(() -> organizationService.validateOrgMembership(orgAId, suspendedPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active");
    }

    @Test
    @DisplayName("04. Non-member denied organization authority")
    void test04_nonMemberDenied() {
        assertThatThrownBy(() -> organizationService.validateOrgMembership(orgAId, outsiderPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("You are not a member of this organization");
    }

    // =========================================================================
    // DEAL CREATION (Tests 5-10)
    // =========================================================================

    @Test
    @DisplayName("05. User in Org A cannot create deal specifying buyerOrganizationId = Org B")
    void test05_orgAUserCannotCreateDealWithBuyerOrgB() {
        CreateDealRequest req = new CreateDealRequest("Test Deal", "Desc", buyerUserId, sellerUserId.toString(), new BigDecimal("10000.00"), "INR");
        req.setBuyerOrganizationId(orgBId); // Cross-org spoofing attempt

        assertThatThrownBy(() -> dealService.createDeal(req, buyerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Caller does not have active membership in the specified buyer organization");
    }

    @Test
    @DisplayName("06. Active Org A buyer can create correct Org A deal")
    void test06_activeOrgABuyerCanCreateCorrectOrgADeal() {
        CreateDealRequest req = new CreateDealRequest("Valid Deal", "Desc", buyerUserId, sellerUserId.toString(), new BigDecimal("10000.00"), "INR");
        req.setBuyerOrganizationId(orgAId);
        req.setSellerOrganizationId(orgBId);

        when(dealRepository.saveAndFlush(any(Deal.class))).thenAnswer(i -> {
            Deal d = i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        DealResponse res = dealService.createDeal(req, buyerPrincipal);
        assertThat(res).isNotNull();
        assertThat(res.getTitle()).isEqualTo("Valid Deal");
    }

    @Test
    @DisplayName("07. Valid User UUID passed as buyer organization UUID is rejected")
    void test07_userUuidPassedAsBuyerOrgUuidRejected() {
        CreateDealRequest req = new CreateDealRequest("Invalid Org Deal", "Desc", buyerUserId, sellerUserId.toString(), new BigDecimal("10000.00"), "INR");
        UUID validUserUuidAsOrg = buyerUserId;
        req.setBuyerOrganizationId(validUserUuidAsOrg);
        when(organizationRepository.findById(validUserUuidAsOrg)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dealService.createDeal(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Buyer organization not found");
    }

    @Test
    @DisplayName("08. Nonexistent seller organization UUID is rejected")
    void test08_nonexistentSellerOrgRejected() {
        CreateDealRequest req = new CreateDealRequest("Invalid Seller Org", "Desc", buyerUserId, sellerUserId.toString(), new BigDecimal("10000.00"), "INR");
        req.setBuyerOrganizationId(orgAId);
        UUID fakeSellerOrgId = UUID.randomUUID();
        req.setSellerOrganizationId(fakeSellerOrgId);
        when(organizationRepository.findById(fakeSellerOrgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dealService.createDeal(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Seller organization not found");
    }

    @Test
    @DisplayName("09. Identical buyer and seller organization rejected")
    void test09_identicalOrganizationAbuseRejected() {
        CreateDealRequest req = new CreateDealRequest("Self Deal", "Desc", buyerUserId, sellerUserId.toString(), new BigDecimal("10000.00"), "INR");
        req.setBuyerOrganizationId(orgAId);
        req.setSellerOrganizationId(orgAId);

        assertThatThrownBy(() -> dealService.createDeal(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Buyer and Seller organization cannot be the same");
    }

    @Test
    @DisplayName("10. Legacy / unbound deal identities are not auto-mapped or synthesized")
    void test10_legacyUnboundIdentityIsNotAutoMapped() {
        CreateDealRequest req = new CreateDealRequest("Legacy Deal", "Desc", buyerUserId, sellerUserId.toString(), new BigDecimal("10000.00"), "INR");

        when(dealRepository.saveAndFlush(any(Deal.class))).thenAnswer(i -> {
            Deal d = i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        dealService.createDeal(req, buyerPrincipal);

        ArgumentCaptor<Deal> captor = ArgumentCaptor.forClass(Deal.class);
        verify(dealRepository).saveAndFlush(captor.capture());
        Deal saved = captor.getValue();

        assertThat(saved.getBuyerOrganizationId()).isNull();
        assertThat(saved.getSellerOrganizationId()).isNull();
    }

    // =========================================================================
    // PAYMENT INSTRUCTIONS (Tests 11-20)
    // =========================================================================

    @Test
    @DisplayName("11. Buyer participant reads own deal payment instructions")
    void test11_buyerParticipantReadsOwnDealInstructions() {
        PaymentInstruction inst = new PaymentInstruction(
                "FINX-TXN-01", dealId, null, orgAId, orgBId,
                new BigDecimal("100000.00"), "INR", "MOCK", "IDEMP-1", buyerUserId
        );
        when(instructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(inst));

        List<PaymentInstructionResponse> list = paymentOrchestrationService.getInstructionsForDeal(dealId, buyerPrincipal);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).instructionReference()).isEqualTo("FINX-TXN-01");
    }

    @Test
    @DisplayName("12. Seller participant reads own deal payment instructions")
    void test12_sellerParticipantReadsOwnDealInstructions() {
        PaymentInstruction inst = new PaymentInstruction(
                "FINX-TXN-01", dealId, null, orgAId, orgBId,
                new BigDecimal("100000.00"), "INR", "MOCK", "IDEMP-1", buyerUserId
        );
        when(instructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(inst));

        List<PaymentInstructionResponse> list = paymentOrchestrationService.getInstructionsForDeal(dealId, sellerPrincipal);
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("13. Cross-org buyer denied payment instructions for unrelated deal")
    void test13_crossOrgBuyerDeniedPaymentInstructions() {
        assertThatThrownBy(() -> paymentOrchestrationService.getInstructionsForDeal(dealId, outsiderPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not authorized to access this deal");
    }

    @Test
    @DisplayName("14. Cross-org seller denied payment instructions for unrelated deal")
    void test14_crossOrgSellerDeniedPaymentInstructions() {
        UUID otherSellerId = UUID.randomUUID();
        UserPrincipal otherSeller = makePrincipal(otherSellerId, "other-seller@corp.com", Role.SELLER);

        assertThatThrownBy(() -> paymentOrchestrationService.getInstructionsForDeal(dealId, otherSeller))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not authorized to access this deal");
    }

    @Test
    @DisplayName("15. Random authenticated user denied payment instructions")
    void test15_randomAuthenticatedUserDeniedPaymentInstructions() {
        UserPrincipal randomUser = makePrincipal(UUID.randomUUID(), "random@test.com", Role.BUYER);

        assertThatThrownBy(() -> paymentOrchestrationService.getInstructionsForDeal(dealId, randomUser))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("16. Assigned Project Manager permitted to read deal payment instructions")
    void test16_assignedPmPermittedForPaymentInstructions() {
        when(instructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(Collections.emptyList());

        List<PaymentInstructionResponse> list = paymentOrchestrationService.getInstructionsForDeal(dealId, assignedPmPrincipal);
        assertThat(list).isNotNull();
    }

    @Test
    @DisplayName("17. Unassigned Project Manager denied payment instructions")
    void test17_unassignedPmDeniedPaymentInstructions() {
        assertThatThrownBy(() -> paymentOrchestrationService.getInstructionsForDeal(dealId, unassignedPmPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not authorized to access this deal");
    }

    @Test
    @DisplayName("18. New PaymentInstruction contains correct buyerOrganizationId from Deal")
    void test18_newPaymentInstructionContainsCorrectBuyerOrgId() {
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(boundDeal));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(true);
        Milestone m18 = new Milestone();
        m18.setId(UUID.randomUUID());
        m18.setDealId(dealId);
        m18.setAmount(new BigDecimal("100000.00"));
        when(milestoneRepository.findByDealIdOrderBySequenceAsc(dealId)).thenReturn(List.of(m18));
        when(bankingProvider.getProviderName()).thenReturn("MOCK");
        when(bankingProvider.createPaymentInstruction(any(PaymentInstructionRequest.class)))
                .thenReturn(new PaymentInstructionResult("PROV-99", "VA123", "IFSC001", "CREATED", "https://pay.example.com", "{}"));
        when(instructionRepository.save(any(PaymentInstruction.class))).thenAnswer(i -> i.getArgument(0));

        CreateInstructionRequest req = new CreateInstructionRequest(dealId, null, "IDEMP-TEST-18");
        paymentOrchestrationService.createPaymentInstruction(req, buyerPrincipal);

        ArgumentCaptor<PaymentInstruction> captor = ArgumentCaptor.forClass(PaymentInstruction.class);
        verify(instructionRepository).save(captor.capture());
        PaymentInstruction saved = captor.getValue();

        assertThat(saved.getBuyerOrganizationId()).isEqualTo(orgAId);
    }

    @Test
    @DisplayName("19. New PaymentInstruction contains correct sellerOrganizationId from Deal")
    void test19_newPaymentInstructionContainsCorrectSellerOrgId() {
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(boundDeal));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(true);
        Milestone m18 = new Milestone();
        m18.setId(UUID.randomUUID());
        m18.setDealId(dealId);
        m18.setAmount(new BigDecimal("100000.00"));
        when(milestoneRepository.findByDealIdOrderBySequenceAsc(dealId)).thenReturn(List.of(m18));
        when(bankingProvider.getProviderName()).thenReturn("MOCK");
        when(bankingProvider.createPaymentInstruction(any(PaymentInstructionRequest.class)))
                .thenReturn(new PaymentInstructionResult("PROV-99", "VA123", "IFSC001", "CREATED", "https://pay.example.com", "{}"));
        when(instructionRepository.save(any(PaymentInstruction.class))).thenAnswer(i -> i.getArgument(0));

        CreateInstructionRequest req = new CreateInstructionRequest(dealId, null, "IDEMP-TEST-19");
        paymentOrchestrationService.createPaymentInstruction(req, buyerPrincipal);

        ArgumentCaptor<PaymentInstruction> captor = ArgumentCaptor.forClass(PaymentInstruction.class);
        verify(instructionRepository).save(captor.capture());
        PaymentInstruction saved = captor.getValue();

        assertThat(saved.getSellerOrganizationId()).isEqualTo(orgBId);
    }

    @Test
    @DisplayName("20. Null-org unbound deal cannot create a new trusted payment instruction")
    void test20_nullOrgDealCannotCreateNewTrustedInstruction() {
        when(dealRepository.findByIdForUpdate(unboundDeal.getId())).thenReturn(Optional.of(unboundDeal));

        CreateInstructionRequest req = new CreateInstructionRequest(unboundDeal.getId(), null, "IDEMP-TEST-20");

        assertThatThrownBy(() -> paymentOrchestrationService.createPaymentInstruction(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("without verified buyer and seller organizations");
    }

    // =========================================================================
    // SETTLEMENT HISTORY (Tests 21-23)
    // =========================================================================

    @Test
    @DisplayName("21. Legitimate deal participant can read historical settlements")
    void test21_legitimateDealParticipantCanReadHistoricalSettlements() {
        SettlementInstruction inst = new SettlementInstruction(
                "FINX-SET-01", dealId, UUID.randomUUID(), orgBId, UUID.randomUUID(),
                new BigDecimal("50000.00"), "INR", "MOCK", "IDEMP-SET-1", buyerUserId
        );
        inst.setStatus(SettlementStatus.SETTLED);
        when(settlementRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(inst));

        List<SettlementResponse> res = settlementAuthorizationService.getSettlementsForDeal(dealId, buyerPrincipal);
        assertThat(res).hasSize(1);
        assertThat(res.get(0).settlementReference()).isEqualTo("FINX-SET-01");
    }

    @Test
    @DisplayName("22. Cross-tenant caller denied settlement history")
    void test22_crossTenantCallerDeniedSettlementHistory() {
        assertThatThrownBy(() -> settlementAuthorizationService.getSettlementsForDeal(dealId, outsiderPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not authorized to access this deal");
    }

    @Test
    @DisplayName("23. Unassigned Project Manager denied settlement history")
    void test23_unassignedPmDeniedSettlementHistory() {
        assertThatThrownBy(() -> settlementAuthorizationService.getSettlementsForDeal(dealId, unassignedPmPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not authorized to access this deal");
    }

    // =========================================================================
    // DISPUTES (Tests 24-29)
    // =========================================================================

    @Test
    @DisplayName("24. Buyer participant permitted to read disputes")
    void test24_buyerParticipantPermittedToReadDisputes() {
        Dispute dispute = new Dispute(dealId, null, buyerUserId, "Milestone delayed");
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(dispute));

        List<DisputeResponse> list = disputeService.getDisputesForDeal(dealId, buyerPrincipal);
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("25. Seller participant permitted to read disputes")
    void test25_sellerParticipantPermittedToReadDisputes() {
        Dispute dispute = new Dispute(dealId, null, buyerUserId, "Milestone delayed");
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(dispute));

        List<DisputeResponse> list = disputeService.getDisputesForDeal(dealId, sellerPrincipal);
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("26. Cross-org user denied disputes")
    void test26_crossOrgUserDeniedDisputes() {
        assertThatThrownBy(() -> disputeService.getDisputesForDeal(dealId, outsiderPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not authorized to access this deal");
    }

    @Test
    @DisplayName("27. Assigned Project Manager permitted to read disputes")
    void test27_assignedPmPermittedForDisputes() {
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(Collections.emptyList());

        List<DisputeResponse> list = disputeService.getDisputesForDeal(dealId, assignedPmPrincipal);
        assertThat(list).isNotNull();
    }

    @Test
    @DisplayName("28. Unassigned Project Manager denied disputes")
    void test28_unassignedPmDeniedDisputes() {
        assertThatThrownBy(() -> disputeService.getDisputesForDeal(dealId, unassignedPmPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not authorized to access this deal");
    }

    @Test
    @DisplayName("29. Actor principal is enforced by DisputeService")
    void test29_actorPrincipalIsActuallyEnforcedByService() {
        // Passing null actor throws UnauthorizedException
        assertThatThrownBy(() -> disputeService.getDisputesForDeal(dealId, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    // =========================================================================
    // RECONCILIATION (Tests 30-35)
    // =========================================================================

    @Test
    @DisplayName("30. Role BUYER denied reconciliation")
    void test30_buyerDeniedReconciliation() {
        assertThatThrownBy(() -> reconciliationService.triggerReconciliation(buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Platform ADMIN or FINANCE role required");
    }

    @Test
    @DisplayName("31. Role SELLER denied reconciliation")
    void test31_sellerDeniedReconciliation() {
        assertThatThrownBy(() -> reconciliationService.triggerReconciliation(sellerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Platform ADMIN or FINANCE role required");
    }

    @Test
    @DisplayName("32. Role PROJECT_MANAGER denied reconciliation")
    void test32_projectManagerDeniedReconciliation() {
        assertThatThrownBy(() -> reconciliationService.triggerReconciliation(assignedPmPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Platform ADMIN or FINANCE role required");
    }

    @Test
    @DisplayName("33. Ordinary organization member (even with org finance role) denied reconciliation")
    void test33_ordinaryOrgMemberDeniedReconciliation() {
        // User is FINANCE within an Org, but their platform role is BUYER
        UserPrincipal orgFinanceUser = makePrincipal(buyerUserId, "cfo@orga.com", Role.BUYER);

        assertThatThrownBy(() -> reconciliationService.triggerReconciliation(orgFinanceUser))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Platform ADMIN or FINANCE role required");
    }

    @Test
    @DisplayName("34. Platform ADMIN permitted reconciliation")
    void test34_platformAdminAllowedReconciliation() {
        when(bankingProvider.getProviderName()).thenReturn("MOCK");
        when(bankingProvider.fetchTransactions(any(), any())).thenReturn(Collections.emptyList());
        when(transactionRepository.findAll()).thenReturn(Collections.emptyList());
        when(settlementRepository.findAll()).thenReturn(Collections.emptyList());
        when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> {
            ReconciliationRun r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        ReconciliationRun run = reconciliationService.triggerReconciliation(platformAdminPrincipal);
        assertThat(run).isNotNull();
    }

    @Test
    @DisplayName("35. Platform FINANCE permitted reconciliation")
    void test35_platformFinanceAllowedReconciliation() {
        when(bankingProvider.getProviderName()).thenReturn("MOCK");
        when(bankingProvider.fetchTransactions(any(), any())).thenReturn(Collections.emptyList());
        when(transactionRepository.findAll()).thenReturn(Collections.emptyList());
        when(settlementRepository.findAll()).thenReturn(Collections.emptyList());
        when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> {
            ReconciliationRun r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        ReconciliationRun run = reconciliationService.triggerReconciliation(platformFinancePrincipal);
        assertThat(run).isNotNull();
    }

    // =========================================================================
    // VERIFIED BUSINESS PROFILE (Tests 36-40)
    // =========================================================================

    @Test
    @DisplayName("36. Active same-org member allowed verified business profile")
    void test36_activeSameOrgMemberAllowedVerifiedProfile() {
        VerifiedBusinessProfile profile = organizationService.getVerifiedBusinessProfile(orgAId, buyerPrincipal);
        assertThat(profile).isNotNull();
        assertThat(profile.organizationId()).isEqualTo(orgAId);
    }

    @Test
    @DisplayName("37. Inactive / suspended same-org member denied verified business profile")
    void test37_inactiveSameOrgMemberDeniedVerifiedProfile() {
        UUID suspendedUserId = UUID.randomUUID();
        UserPrincipal suspendedPrincipal = makePrincipal(suspendedUserId, "suspended@orga.com", Role.BUYER);
        OrganizationMember suspendedMember = new OrganizationMember(orgAId, suspendedUserId, OrgRole.MEMBER);
        suspendedMember.setStatus(MemberStatus.SUSPENDED);
        when(memberRepository.findByOrganizationIdAndUserId(orgAId, suspendedUserId)).thenReturn(Optional.of(suspendedMember));

        assertThatThrownBy(() -> organizationService.getVerifiedBusinessProfile(orgAId, suspendedPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Organization membership is not active");
    }

    @Test
    @DisplayName("38. Unrelated org user denied verified business profile")
    void test38_unrelatedOrgUserDeniedVerifiedProfile() {
        assertThatThrownBy(() -> organizationService.getVerifiedBusinessProfile(orgAId, outsiderPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("You are not a member of this organization");
    }

    @Test
    @DisplayName("39. Authorized platform role (ADMIN / FINANCE) allowed verified business profile")
    void test39_authorizedPlatformRoleAllowedVerifiedProfile() {
        VerifiedBusinessProfile adminProfile = organizationService.getVerifiedBusinessProfile(orgAId, platformAdminPrincipal);
        assertThat(adminProfile).isNotNull();

        VerifiedBusinessProfile financeProfile = organizationService.getVerifiedBusinessProfile(orgAId, platformFinancePrincipal);
        assertThat(financeProfile).isNotNull();
    }

    @Test
    @DisplayName("40. Verified business profile metrics remain non-authoritative pending D06")
    void test40_metricCalculationItselfIsNotModified() {
        // Documenting boundary: profile metrics calculation is untouched for B07 and remains non-authoritative pending D06.
        VerifiedBusinessProfile profile = organizationService.getVerifiedBusinessProfile(orgAId, buyerPrincipal);
        assertThat(profile).isNotNull();
    }
}
