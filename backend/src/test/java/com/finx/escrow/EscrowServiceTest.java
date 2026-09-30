package com.finx.escrow;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.dto.response.EscrowLedgerResponse;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscrowServiceTest {

    @Mock
    private EscrowAccountRepository escrowAccountRepository;

    @Mock
    private EscrowLedgerRepository escrowLedgerRepository;

    @Mock
    private DealRepository dealRepository;

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private DisputeRepository disputeRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private EscrowService escrowService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID dealId;
    private UUID milestoneId;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;
    private UserPrincipal buyerPrincipal;

    @BeforeEach
    void setUp() {
        escrowService = new EscrowService(
                escrowAccountRepository,
                escrowLedgerRepository,
                dealRepository,
                milestoneRepository,
                disputeRepository,
                auditService,
                notificationService
        );

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();

        deal = new Deal("Escrow Deal", "Desc", buyerId, sellerId, BigDecimal.valueOf(50000), "INR", DealStatus.ACCEPTED);
        deal.setId(dealId);

        milestone = new Milestone(dealId, "Phase 1", "Desc", 1, BigDecimal.valueOf(10000), "INR", null);
        milestone.setId(milestoneId);
        milestone.setStatus(MilestoneStatus.APPROVED);

        escrowAccount = new EscrowAccount(dealId, BigDecimal.valueOf(10000), "INR");
        escrowAccount.setId(UUID.randomUUID());

        User buyerUser = new User("Buyer", "buyer@finx.test", "pass", Role.BUYER, com.finx.common.enums.UserStatus.ACTIVE);
        buyerUser.setId(buyerId);
        buyerPrincipal = UserPrincipal.create(buyerUser);
    }

    private SettlementInstruction createSettledInstruction() {
        SettlementInstruction settlement = new SettlementInstruction(
                "FINX-SET-001", dealId, milestoneId, sellerId, UUID.randomUUID(),
                BigDecimal.valueOf(10000), "INR", "MOCK", "IDEMP-001", buyerId
        );
        settlement.setStatus(SettlementStatus.SETTLED);
        settlement.setUtrNumber("UTR-TEST-12345");
        return settlement;
    }

    @Test
    @DisplayName("Direct releaseEscrow invocation fails closed with AccessDeniedException")
    void releaseEscrow_directInvocationFailsClosed() {
        assertThatThrownBy(() -> escrowService.releaseEscrow(milestoneId, "Milestone done", buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Direct escrow release is disabled pending settlement orchestration");
    }

    @Test
    @DisplayName("Settlement release fails when settlement instruction is null or invalid")
    void releaseEscrowForSettlement_failsWhenInstructionInvalid() {
        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(null, "Done", buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement finalization requires a valid persisted SettlementInstruction");

        SettlementInstruction pending = createSettledInstruction();
        pending.setStatus(SettlementStatus.PENDING);
        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(pending, "Done", buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement finalization requires a SETTLED status instruction");

        SettlementInstruction noUtr = createSettledInstruction();
        noUtr.setUtrNumber("");
        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(noUtr, "Done", buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement finalization requires a valid bank UTR number");
    }

    @Test
    @DisplayName("Release escrow funds for approved milestone via trusted settlement successfully")
    void releaseEscrowForSettlement_success() {
        SettlementInstruction settlement = createSettledInstruction();

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE)).thenReturn(Optional.empty());
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(escrowAccountRepository.saveAndFlush(any(EscrowAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(escrowLedgerRepository.saveAndFlush(any(EscrowLedger.class))).thenAnswer(i -> {
            EscrowLedger l = i.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));
        when(milestoneRepository.findByDealIdOrderBySequenceAsc(dealId)).thenReturn(List.of(milestone));

        EscrowLedgerResponse res = escrowService.releaseEscrowForSettlement(settlement, "Milestone done", buyerPrincipal);

        assertThat(res.getTransactionType()).isEqualTo(TransactionType.RELEASE);
        assertThat(res.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        assertThat(escrowAccount.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.COMPLETED);
        assertThat(deal.getStatus()).isEqualTo(DealStatus.COMPLETED);
        verify(auditService).logEvent(eq(buyerId), eq("ESCROW_RELEASED"), eq("ESCROW"), any(), any());
    }

    @Test
    @DisplayName("Block settlement release if milestone is not APPROVED")
    void releaseEscrowForSettlement_fails_whenNotApproved() {
        SettlementInstruction settlement = createSettledInstruction();
        milestone.setStatus(MilestoneStatus.IN_PROGRESS);

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(settlement, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Milestone must be APPROVED");
    }

    @Test
    @DisplayName("Double release prevention: cannot release same milestone twice via settlement")
    void releaseEscrowForSettlement_doubleReleasePrevention() {
        SettlementInstruction settlement = createSettledInstruction();
        EscrowLedger priorRelease = new EscrowLedger(escrowAccount.getId(), null, milestoneId, TransactionType.RELEASE, BigDecimal.valueOf(10000), BigDecimal.ZERO, "Prior release");

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE)).thenReturn(Optional.of(priorRelease));

        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(settlement, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been released");
    }

    @Test
    @DisplayName("Prevent settlement release if escrow balance is insufficient")
    void releaseEscrowForSettlement_insufficientBalance() {
        SettlementInstruction settlement = createSettledInstruction();
        escrowAccount.setBalance(BigDecimal.valueOf(5000)); // milestone is 10000

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE)).thenReturn(Optional.empty());
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));

        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(settlement, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Insufficient escrow balance");
    }

    @Test
    @DisplayName("Prevent settlement release if deal status is DISPUTED")
    void releaseEscrowForSettlement_fails_whenDealIsDisputed() {
        SettlementInstruction settlement = createSettledInstruction();
        deal.setStatus(DealStatus.DISPUTED);

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(settlement, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DISPUTED");
    }

    @Test
    @DisplayName("Prevent settlement release if open disputes exist for the deal")
    void releaseEscrowForSettlement_fails_whenOpenDisputesExist() {
        SettlementInstruction settlement = createSettledInstruction();

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.existsByDealIdAndStatus(dealId, DisputeStatus.OPEN)).thenReturn(true);

        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(settlement, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DISPUTED status");
    }

    @Test
    @DisplayName("ensureEscrowAccount: creates new escrow account when none exists")
    void ensureEscrowAccount_createsNewWhenMissing() {
        when(escrowAccountRepository.findByDealId(dealId)).thenReturn(Optional.empty());
        when(escrowAccountRepository.saveAndFlush(any(EscrowAccount.class))).thenAnswer(i -> {
            EscrowAccount ea = i.getArgument(0);
            ea.setId(UUID.randomUUID());
            return ea;
        });

        EscrowAccount created = escrowService.ensureEscrowAccount(dealId, "INR");

        assertThat(created).isNotNull();
        assertThat(created.getDealId()).isEqualTo(dealId);
        assertThat(created.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getTotalFunded()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getCurrency()).isEqualTo("INR");
        verify(escrowAccountRepository).saveAndFlush(any(EscrowAccount.class));
    }

    @Test
    @DisplayName("ensureEscrowAccount: returns existing account idempotently without creating duplicate")
    void ensureEscrowAccount_returnsExistingIdempotently() {
        when(escrowAccountRepository.findByDealId(dealId)).thenReturn(Optional.of(escrowAccount));

        EscrowAccount returned = escrowService.ensureEscrowAccount(dealId, "INR");

        assertThat(returned).isSameAs(escrowAccount);
        verify(escrowAccountRepository, never()).saveAndFlush(any(EscrowAccount.class));
    }

    @Test
    @DisplayName("releaseEscrowForSettlement: auto-ensures escrow account if missing and rejects unfunded release with clear message")
    void releaseEscrowForSettlement_autoEnsuresAccountAndRejectsUnfunded() {
        SettlementInstruction settlement = createSettledInstruction();

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE)).thenReturn(Optional.empty());
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.empty());
        when(escrowAccountRepository.findByDealId(dealId)).thenReturn(Optional.empty());
        when(escrowAccountRepository.saveAndFlush(any(EscrowAccount.class))).thenAnswer(i -> {
            EscrowAccount ea = i.getArgument(0);
            ea.setId(UUID.randomUUID());
            return ea;
        });

        assertThatThrownBy(() -> escrowService.releaseEscrowForSettlement(settlement, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Insufficient escrow balance")
                .hasMessageContaining("Please fund the milestone before releasing funds");
    }
}
