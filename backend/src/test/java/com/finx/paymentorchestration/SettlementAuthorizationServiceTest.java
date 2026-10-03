package com.finx.paymentorchestration;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.SettlementResponse;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.repository.RiskAssessmentRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.repository.KybProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementAuthorizationServiceTest {

    @Mock
    private SettlementInstructionRepository settlementRepository;

    @Mock
    private DealRepository dealRepository;

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private OrganizationBankAccountRepository bankAccountRepository;

    @Mock
    private KybProfileRepository kybProfileRepository;

    @Mock
    private DisputeRepository disputeRepository;

    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;

    @Mock
    private AgreementService agreementService;

    @Mock
    private EscrowService escrowService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private BankingProvider bankingProvider;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private com.finx.risk.service.RiskActionService riskActionService;

    @Mock
    private com.finx.invoice.repository.InvoiceRepository invoiceRepository;

    private SettlementAuthorizationService authorizationService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID sellerOrgId;
    private UUID dealId;
    private UUID milestoneId;

    private Deal deal;
    private Milestone milestone;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal randomPrincipal;
    private OrganizationBankAccount verifiedBank;
    private EscrowAccount escrowAccount;

    @BeforeEach
    void setUp() {
        authorizationService = new SettlementAuthorizationService(
                settlementRepository, dealRepository, milestoneRepository, bankAccountRepository,
                kybProfileRepository, disputeRepository, riskAssessmentRepository, agreementService,
                escrowService, ledgerService, bankingProvider, auditService, notificationService, riskActionService, invoiceRepository
        );

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerId(buyerId);
        deal.setSellerId(sellerId);
        deal.setSellerOrganizationId(sellerOrgId);
        deal.setStatus(DealStatus.FUNDED);
        deal.setTotalAmount(new BigDecimal("100000.00"));
        deal.setCurrency("INR");

        milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setDealId(dealId);
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setAmount(new BigDecimal("50000.00"));

        buyerPrincipal = new UserPrincipal(buyerId, "Buyer Corp", "buyer@corp.com", "pass", Role.BUYER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.BUYER.getAuthority())));
        randomPrincipal = new UserPrincipal(UUID.randomUUID(), "Hacker", "hacker@test.com", "pass", Role.SELLER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.SELLER.getAuthority())));

        verifiedBank = new OrganizationBankAccount(
                sellerOrgId, "Vendor Pvt Ltd", "XXXX-XXXX-7777", "hash123",
                "HDFC0001234", "HDFC Bank", "Main Branch", true
        );
        verifiedBank.setVerificationStatus(BankVerificationStatus.VERIFIED);

        escrowAccount = new EscrowAccount();
        escrowAccount.setDealId(dealId);
        escrowAccount.setBalance(new BigDecimal("100000.00"));
    }

    @Test
    @DisplayName("B04 Containment: authorizeAndInitiateSettlement fails closed with AccessDeniedException for unauthorized user")
    void shouldBlockUnauthorizedUser() {
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", "IDEMP-B04-UNAUTH");

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, randomPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("B04 Containment: authorizeAndInitiateSettlement fails closed with AccessDeniedException for buyer")
    void shouldFailClosedForBuyer() {
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", "IDEMP-B04-BUYER");

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("B04 Containment: authorizeAndInitiateSettlement fails closed with AccessDeniedException for admin")
    void shouldFailClosedForAdmin() {
        UserPrincipal adminPrincipal = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "pass", Role.ADMIN, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.ADMIN.getAuthority())));
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", "IDEMP-B04-ADMIN");

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, adminPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("B04 Containment: authorizeAndInitiateSettlement fails closed with AccessDeniedException for null principal")
    void shouldFailClosedForNullPrincipal() {
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", "IDEMP-B04-NULL");

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("B04 Containment: bankingProvider.initiateSettlement is never called and no ledger/audit mutation occurs")
    void shouldNeverCallBankingProviderOrMutateFinancialState() {
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Milestone 1 work completed", "IDEMP-KEY-999");

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verify(bankingProvider, never()).initiateSettlement(any());
        verify(ledgerService, never()).postJournalEntry(any());
        verify(settlementRepository, never()).save(any());
        verify(auditService, never()).logEvent(any(), any(), any(), any(), any());
        verify(notificationService, never()).sendNotification(any(), any(), any(), any());
    }

    @Test
    @DisplayName("B04 Containment: invoice required or unapproved invoice path fails closed before bank call")
    void requiredInvoiceMissingBlocksBankCall() {
        milestone.setInvoiceRequired(true);
        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(
                new PayoutAuthorizationRequest(milestoneId, null, "Release"), buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verifyNoInteractions(bankingProvider);
    }

    @ParameterizedTest
    @EnumSource(value = com.finx.invoice.enums.InvoiceStatus.class)
    @DisplayName("B04 Containment: any invoice state fails closed before bank call")
    void allInvoiceStatesFailClosedBeforeBankCall(com.finx.invoice.enums.InvoiceStatus status) {
        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(
                new PayoutAuthorizationRequest(milestoneId, null, "Release"), buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("Historical settlement reads getSettlementsForDeal remain functional")
    void historicalSettlementReadsRemainFunctional() {
        SettlementInstruction inst = new SettlementInstruction(
                "FINX-SET-HIST-01", dealId, milestoneId, sellerOrgId, verifiedBank.getId(),
                new BigDecimal("50000.00"), "INR", "MOCK", "IDEMP-01", buyerId
        );
        inst.setStatus(SettlementStatus.SETTLED);
        inst.setUtrNumber("UTR-HIST-999");
        inst.setSettledAt(Instant.now());

        when(dealRepository.findById(dealId)).thenReturn(java.util.Optional.of(deal));
        when(settlementRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(inst));

        List<SettlementResponse> responses = authorizationService.getSettlementsForDeal(dealId, buyerPrincipal);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).settlementReference()).isEqualTo("FINX-SET-HIST-01");
        assertThat(responses.get(0).status()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(responses.get(0).utrNumber()).isEqualTo("UTR-HIST-999");
    }
}
