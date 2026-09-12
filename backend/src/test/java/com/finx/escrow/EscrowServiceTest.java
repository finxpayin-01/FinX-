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
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        deal = new Deal("Escrow Deal", "Desc", buyerId, sellerId, BigDecimal.valueOf(50000), "INR", DealStatus.ACTIVE);
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

    @Test
    @DisplayName("Release escrow funds for approved milestone successfully")
    void releaseEscrow_success() {
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

        EscrowLedgerResponse res = escrowService.releaseEscrow(milestoneId, "Milestone done", buyerPrincipal);

        assertThat(res.getTransactionType()).isEqualTo(TransactionType.RELEASE);
        assertThat(res.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        assertThat(escrowAccount.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.COMPLETED);
        assertThat(deal.getStatus()).isEqualTo(DealStatus.COMPLETED);
        verify(auditService).logEvent(eq(buyerId), eq("ESCROW_RELEASED"), eq("ESCROW"), any(), any());
    }

    @Test
    @DisplayName("Block release if milestone is not APPROVED")
    void releaseEscrow_fails_whenNotApproved() {
        milestone.setStatus(MilestoneStatus.IN_PROGRESS);

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> escrowService.releaseEscrow(milestoneId, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Milestone must be APPROVED");
    }

    @Test
    @DisplayName("Double release prevention: cannot release same milestone twice")
    void releaseEscrow_doubleReleasePrevention() {
        EscrowLedger priorRelease = new EscrowLedger(escrowAccount.getId(), null, milestoneId, TransactionType.RELEASE, BigDecimal.valueOf(10000), BigDecimal.ZERO, "Prior release");

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE)).thenReturn(Optional.of(priorRelease));

        assertThatThrownBy(() -> escrowService.releaseEscrow(milestoneId, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been released");
    }

    @Test
    @DisplayName("Prevent release if escrow balance is insufficient")
    void releaseEscrow_insufficientBalance() {
        escrowAccount.setBalance(BigDecimal.valueOf(5000)); // milestone is 10000

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE)).thenReturn(Optional.empty());
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));

        assertThatThrownBy(() -> escrowService.releaseEscrow(milestoneId, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Insufficient escrow balance");
    }

    @Test
    @DisplayName("Prevent release if deal status is DISPUTED")
    void releaseEscrow_fails_whenDealIsDisputed() {
        deal.setStatus(DealStatus.DISPUTED);

        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> escrowService.releaseEscrow(milestoneId, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DISPUTED");
    }

    @Test
    @DisplayName("Prevent release if open disputes exist for the deal")
    void releaseEscrow_fails_whenOpenDisputesExist() {
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.existsByDealIdAndStatus(dealId, DisputeStatus.OPEN)).thenReturn(true);

        assertThatThrownBy(() -> escrowService.releaseEscrow(milestoneId, "Release", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DISPUTED status");
    }
}
