package com.finx.paymentorchestration;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
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
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.repository.RiskAssessmentRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.KybProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
                escrowService, ledgerService, bankingProvider, auditService, notificationService
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
    @DisplayName("Gate 10: Unauthorized user cannot authorize payout")
    void shouldBlockUnauthorizedUser() {
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));

        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", null);

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, randomPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Only the corporate buyer or a FinX administrator");
    }

    @Test
    @DisplayName("Gate 1: Unverified vendor KYB blocks settlement disbursement")
    void shouldBlockWhenVendorKybNotVerified() {
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(kybProfileRepository.existsByOrganizationIdAndStatus(sellerOrgId, VerificationStatus.VERIFIED)).thenReturn(false);

        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", null);

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Vendor KYB is not verified");
    }

    @Test
    @DisplayName("Gate 2: Unverified vendor bank account blocks settlement disbursement")
    void shouldBlockWhenVendorBankNotVerified() {
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(kybProfileRepository.existsByOrganizationIdAndStatus(sellerOrgId, VerificationStatus.VERIFIED)).thenReturn(true);
        when(bankAccountRepository.findByOrganizationIdAndVerificationStatus(sellerOrgId, BankVerificationStatus.VERIFIED))
                .thenReturn(Optional.empty());

        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", null);

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Vendor verified bank account not found");
    }

    @Test
    @DisplayName("Gate 5: Unsigned deal agreement blocks settlement disbursement")
    void shouldBlockWhenAgreementNotFullySigned() {
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(kybProfileRepository.existsByOrganizationIdAndStatus(sellerOrgId, VerificationStatus.VERIFIED)).thenReturn(true);
        when(bankAccountRepository.findByOrganizationIdAndVerificationStatus(sellerOrgId, BankVerificationStatus.VERIFIED))
                .thenReturn(Optional.of(verifiedBank));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(false);

        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", null);

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Deal agreement is not signed by both parties");
    }

    @Test
    @DisplayName("Gate 6: Unapproved milestone (e.g. UNDER_REVIEW) blocks settlement disbursement")
    void shouldBlockWhenMilestoneNotApproved() {
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(kybProfileRepository.existsByOrganizationIdAndStatus(sellerOrgId, VerificationStatus.VERIFIED)).thenReturn(true);
        when(bankAccountRepository.findByOrganizationIdAndVerificationStatus(sellerOrgId, BankVerificationStatus.VERIFIED))
                .thenReturn(Optional.of(verifiedBank));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(true);

        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", null);

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Milestone must be APPROVED before settlement");
    }

    @Test
    @DisplayName("Gate 7: Insufficient escrow pool balance blocks settlement disbursement")
    void shouldBlockWhenEscrowBalanceInsufficient() {
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(kybProfileRepository.existsByOrganizationIdAndStatus(sellerOrgId, VerificationStatus.VERIFIED)).thenReturn(true);
        when(bankAccountRepository.findByOrganizationIdAndVerificationStatus(sellerOrgId, BankVerificationStatus.VERIFIED))
                .thenReturn(Optional.of(verifiedBank));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(true);

        escrowAccount.setBalance(new BigDecimal("1000.00")); // Required 50,000
        when(escrowService.getOrCreateEscrowAccount(dealId, "INR")).thenReturn(escrowAccount);

        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Payout auth", null);

        assertThatThrownBy(() -> authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Insufficient escrow pool balance");
    }

    @Test
    @DisplayName("Happy Path: All 10 gates satisfied -> settlement instruction initiated and ledger posted")
    void shouldSettleSuccessfullyWhenAllGatesPassed() {
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(kybProfileRepository.existsByOrganizationIdAndStatus(sellerOrgId, VerificationStatus.VERIFIED)).thenReturn(true);
        when(bankAccountRepository.findByOrganizationIdAndVerificationStatus(sellerOrgId, BankVerificationStatus.VERIFIED))
                .thenReturn(Optional.of(verifiedBank));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(true);
        when(escrowService.getOrCreateEscrowAccount(dealId, "INR")).thenReturn(escrowAccount);
        when(settlementRepository.findByMilestoneIdAndStatus(milestoneId, com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus.SETTLED))
                .thenReturn(Optional.empty());

        when(bankingProvider.initiateSettlement(any())).thenReturn(
                new SettlementResult("EXT-SET-888", "PROCESSED", "UTR9988776655", Instant.now(), "Settlement successful")
        );

        when(settlementRepository.save(any(SettlementInstruction.class))).thenAnswer(i -> {
            SettlementInstruction inst = i.getArgument(0);
            inst.setId(UUID.randomUUID());
            return inst;
        });

        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Milestone 1 work completed", "IDEMP-KEY-999");

        SettlementResponse response = authorizationService.authorizeAndInitiateSettlement(req, buyerPrincipal);

        assertThat(response).isNotNull();
        assertThat(response.settlementReference()).startsWith("FINX-SET-");
        assertThat(response.status().name()).isEqualTo("SETTLED");
        assertThat(response.utrNumber()).isEqualTo("UTR9988776655");

        verify(bankingProvider).initiateSettlement(any());
        verify(ledgerService).postJournalEntry(any());
        verify(auditService).logEvent(eq(buyerId), eq("SETTLEMENT_COMPLETED"), eq("SETTLEMENT"), any(), any());
    }
}
