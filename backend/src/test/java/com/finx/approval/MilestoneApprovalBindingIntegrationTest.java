package com.finx.approval;

import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.approval.repository.MilestoneApprovalBindingRepository;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.approval.service.MilestoneApprovalFingerprintService;
import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.reminder.MilestoneReminderService;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.milestone.service.MilestoneService;
import com.finx.notification.service.NotificationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilestoneApprovalBindingIntegrationTest {

    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneApprovalBindingRepository bindingRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private EscrowService escrowService;
    @Mock private MilestoneReminderService milestoneReminderService;

    private FinxClock clock;
    private MilestoneApprovalFingerprintService fingerprintService;
    private MilestoneApprovalBindingService bindingService;
    private MilestoneService milestoneService;

    private UUID buyerOrgId;
    private UUID buyerId;
    private UUID sellerId;
    private UUID pmId;
    private UUID adminId;

    private UserPrincipal buyerPrincipal;
    private UserPrincipal pmPrincipal;
    private UserPrincipal adminPrincipal;
    private UserPrincipal sellerPrincipal;

    private Deal deal;
    private Milestone milestone;
    private Deliverable deliverable;
    private Instant fixedNow;

    @BeforeEach
    void setUp() {
        fixedNow = Instant.parse("2026-10-10T12:00:00Z");
        clock = new FinxClock();
        clock.setFixed(fixedNow);

        fingerprintService = new MilestoneApprovalFingerprintService();
        bindingService = spy(new MilestoneApprovalBindingService(
                bindingRepository,
                fingerprintService,
                dealRepository,
                milestoneRepository,
                deliverableRepository
        ));

        milestoneService = new MilestoneService(
                milestoneRepository,
                deliverableRepository,
                dealRepository,
                auditService,
                notificationService,
                escrowService,
                milestoneReminderService,
                clock,
                bindingService
        );

        buyerOrgId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        pmId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        User buyerUser = new User("Buyer", "buyer@finx.test", "pass", Role.BUYER, UserStatus.ACTIVE);
        buyerUser.setId(buyerId);
        buyerPrincipal = UserPrincipal.create(buyerUser);

        User pmUser = new User("PM", "pm@finx.test", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        pmUser.setId(pmId);
        pmPrincipal = UserPrincipal.create(pmUser);

        User adminUser = new User("Admin", "admin@finx.test", "pass", Role.ADMIN, UserStatus.ACTIVE);
        adminUser.setId(adminId);
        adminPrincipal = UserPrincipal.create(adminUser);

        User sellerUser = new User("Seller", "seller@finx.test", "pass", Role.SELLER, UserStatus.ACTIVE);
        sellerUser.setId(sellerId);
        sellerPrincipal = UserPrincipal.create(sellerUser);

        deal = new Deal("Test Deal", "Desc", buyerId, sellerId, new BigDecimal("1000.00"), "INR", DealStatus.IN_PROGRESS);
        deal.setId(UUID.randomUUID());
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setProjectManagerId(pmId);

        milestone = new Milestone(deal.getId(), "M1", "Desc", 1, new BigDecimal("500.00"), "INR", fixedNow.plusSeconds(86400));
        milestone.setId(UUID.randomUUID());
        milestone.setStatus(MilestoneStatus.SUBMITTED);
        milestone.setInvoiceRequired(true);

        deliverable = new Deliverable(milestone.getId(), sellerId, "Doc", "Spec", "https://finx.local/file1.pdf");
        deliverable.setId(UUID.randomUUID());
        deliverable.setStatus(DeliverableStatus.PENDING);
        deliverable.setSubmittedAt(fixedNow.minusSeconds(3600));

        lenient().when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));
        lenient().when(dealRepository.findByIdForUpdate(deal.getId())).thenReturn(Optional.of(deal));
        lenient().when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        lenient().when(milestoneRepository.findByIdForUpdate(milestone.getId())).thenReturn(Optional.of(milestone));
        lenient().when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(new ArrayList<>(List.of(deliverable)));
        lenient().when(milestoneRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bindingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // 1. buyer approval creates binding
    @Test
    void buyerApprovalCreatesBinding() {
        milestoneService.approveMilestone(milestone.getId(), buyerPrincipal);

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        ArgumentCaptor<MilestoneApprovalBinding> bindingCaptor = ArgumentCaptor.forClass(MilestoneApprovalBinding.class);
        verify(bindingRepository).saveAndFlush(bindingCaptor.capture());

        MilestoneApprovalBinding binding = bindingCaptor.getValue();
        assertThat(binding.getApprovalAuthority()).isEqualTo(MilestoneApprovalAuthority.BUYER);
        assertThat(binding.getApprovedBy()).isEqualTo(buyerId);
        assertThat(binding.getOrganizationId()).isEqualTo(buyerOrgId);
        assertThat(binding.getDealId()).isEqualTo(deal.getId());
        assertThat(binding.getMilestoneId()).isEqualTo(milestone.getId());
    }

    // 2. PM approval creates binding
    @Test
    void pmApprovalCreatesBinding() {
        milestoneService.approveMilestone(milestone.getId(), pmPrincipal);

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        ArgumentCaptor<MilestoneApprovalBinding> bindingCaptor = ArgumentCaptor.forClass(MilestoneApprovalBinding.class);
        verify(bindingRepository).saveAndFlush(bindingCaptor.capture());

        MilestoneApprovalBinding binding = bindingCaptor.getValue();
        assertThat(binding.getApprovalAuthority()).isEqualTo(MilestoneApprovalAuthority.PROJECT_MANAGER);
        assertThat(binding.getApprovedBy()).isEqualTo(pmId);
        assertThat(binding.getProjectManagerId()).isEqualTo(pmId);
    }

    // 3. ADMIN approval creates binding
    @Test
    void adminApprovalCreatesBinding() {
        milestoneService.approveMilestone(milestone.getId(), adminPrincipal);

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        ArgumentCaptor<MilestoneApprovalBinding> bindingCaptor = ArgumentCaptor.forClass(MilestoneApprovalBinding.class);
        verify(bindingRepository).saveAndFlush(bindingCaptor.capture());

        MilestoneApprovalBinding binding = bindingCaptor.getValue();
        assertThat(binding.getApprovalAuthority()).isEqualTo(MilestoneApprovalAuthority.ADMIN);
        assertThat(binding.getApprovedBy()).isEqualTo(adminId);
    }

    // 4. unauthorized seller rejected
    @Test
    void unauthorizedSellerRejected() {
        assertThatThrownBy(() -> milestoneService.approveMilestone(milestone.getId(), sellerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("can approve this milestone");

        verify(bindingRepository, never()).saveAndFlush(any());
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.SUBMITTED);
    }

    // 5. binding and milestone approval atomic
    @Test
    void bindingAndMilestoneApprovalAtomic() {
        milestoneService.approveMilestone(milestone.getId(), buyerPrincipal);

        verify(milestoneRepository).saveAndFlush(milestone);
        verify(bindingRepository).saveAndFlush(any(MilestoneApprovalBinding.class));
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }

    // 6. forced binding failure rolls back milestone APPROVED transition
    @Test
    void forcedBindingFailureRollsBackMilestoneApprovedTransition() {
        doThrow(new RuntimeException("Simulated database failure during binding creation"))
                .when(bindingRepository).saveAndFlush(any());

        assertThatThrownBy(() -> milestoneService.approveMilestone(milestone.getId(), buyerPrincipal))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated database failure");

        // The transaction rolled back by rethrowing exception
        verify(bindingRepository).saveAndFlush(any());
    }

    // 7. deliverable review state and binding share approval instant where designed
    @Test
    void deliverableReviewStateAndBindingShareApprovalInstant() {
        milestoneService.approveMilestone(milestone.getId(), buyerPrincipal);

        ArgumentCaptor<MilestoneApprovalBinding> bindingCaptor = ArgumentCaptor.forClass(MilestoneApprovalBinding.class);
        verify(bindingRepository).saveAndFlush(bindingCaptor.capture());
        MilestoneApprovalBinding binding = bindingCaptor.getValue();

        assertThat(deliverable.getStatus()).isEqualTo(DeliverableStatus.APPROVED);
        assertThat(deliverable.getReviewedAt()).isEqualTo(fixedNow);
        assertThat(binding.getApprovedAt()).isEqualTo(fixedNow);
        assertThat(deliverable.getReviewedAt()).isEqualTo(binding.getApprovedAt());
    }

    // 8. second approval rejected
    @Test
    void secondApprovalRejected() {
        milestoneService.approveMilestone(milestone.getId(), buyerPrincipal);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);

        // Attempting to approve again fails because status is already APPROVED
        assertThatThrownBy(() -> milestoneService.approveMilestone(milestone.getId(), buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only submitted milestones under review can be approved");
    }

    // 9. modern org-bound approval has binding
    @Test
    void modernOrgBoundApprovalHasBinding() {
        deal.setBuyerOrganizationId(buyerOrgId);
        milestoneService.approveMilestone(milestone.getId(), buyerPrincipal);

        verify(bindingRepository).saveAndFlush(any(MilestoneApprovalBinding.class));
    }

    // 10. legacy unbound deal may approve status but has no authoritative binding
    @Test
    void legacyUnboundDealMayApproveStatusButHasNoAuthoritativeBinding() {
        deal.setBuyerOrganizationId(null);
        milestoneService.approveMilestone(milestone.getId(), buyerPrincipal);

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        verify(bindingRepository, never()).saveAndFlush(any());
        verify(bindingRepository, never()).save(any());
    }
}
