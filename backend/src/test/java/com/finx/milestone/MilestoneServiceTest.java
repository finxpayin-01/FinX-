package com.finx.milestone;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.dto.request.CreateMilestoneRequest;
import com.finx.milestone.dto.request.SubmitDeliverableRequest;
import com.finx.milestone.dto.response.DeliverableResponse;
import com.finx.milestone.dto.response.MilestoneResponse;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.milestone.service.MilestoneService;
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
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilestoneServiceTest {

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private DeliverableRepository deliverableRepository;

    @Mock
    private DealRepository dealRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private MilestoneService milestoneService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID dealId;
    private Deal deal;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;

    @BeforeEach
    void setUp() {
        milestoneService = new MilestoneService(
                milestoneRepository,
                deliverableRepository,
                dealRepository,
                auditService,
                notificationService
        );

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        dealId = UUID.randomUUID();

        deal = new Deal("Test Deal", "Desc", buyerId, sellerId, BigDecimal.valueOf(50000), "INR", DealStatus.ACTIVE);
        deal.setId(dealId);

        User buyerUser = new User("Buyer", "buyer@finx.test", "pass", Role.BUYER, com.finx.common.enums.UserStatus.ACTIVE);
        buyerUser.setId(buyerId);
        buyerPrincipal = UserPrincipal.create(buyerUser);

        User sellerUser = new User("Vendor", "vendor@finx.test", "pass", Role.SELLER, com.finx.common.enums.UserStatus.ACTIVE);
        sellerUser.setId(sellerId);
        sellerPrincipal = UserPrincipal.create(sellerUser);
    }

    @Test
    @DisplayName("Corporate buyer can create a milestone for active deal")
    void createMilestone_success() {
        CreateMilestoneRequest req = new CreateMilestoneRequest();
        req.setTitle("UI Design");
        req.setAmount(BigDecimal.valueOf(10000));
        req.setCurrency("INR");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.countByDealId(dealId)).thenReturn(0L);
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(invocation -> {
            Milestone m = invocation.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });

        MilestoneResponse response = milestoneService.createMilestone(dealId, req, buyerPrincipal);

        assertThat(response.getTitle()).isEqualTo("UI Design");
        assertThat(response.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        assertThat(response.getStatus()).isEqualTo(MilestoneStatus.PENDING);
        verify(auditService).logEvent(eq(buyerId), eq("MILESTONE_CREATED"), eq("MILESTONE"), any(), any());
    }

    @Test
    @DisplayName("Vendor cannot create a milestone - only buyer or admin")
    void createMilestone_unauthorizedForVendor() {
        CreateMilestoneRequest req = new CreateMilestoneRequest();
        req.setTitle("UI Design");
        req.setAmount(BigDecimal.valueOf(10000));

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> milestoneService.createMilestone(dealId, req, sellerPrincipal))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("Vendor can start work on PENDING milestone")
    void startMilestone_success() {
        Milestone milestone = new Milestone(dealId, "UI Design", "Desc", 1, BigDecimal.valueOf(10000), "INR", null);
        milestone.setId(UUID.randomUUID());

        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        MilestoneResponse response = milestoneService.startMilestone(milestone.getId(), sellerPrincipal);

        assertThat(response.getStatus()).isEqualTo(MilestoneStatus.IN_PROGRESS);
        verify(auditService).logEvent(eq(sellerId), eq("MILESTONE_STARTED"), eq("MILESTONE"), any(), any());
    }

    @Test
    @DisplayName("Vendor submits deliverable and milestone enters UNDER_REVIEW")
    void submitDeliverable_success() {
        Milestone milestone = new Milestone(dealId, "UI Design", "Desc", 1, BigDecimal.valueOf(10000), "INR", null);
        milestone.setId(UUID.randomUUID());
        milestone.setStatus(MilestoneStatus.IN_PROGRESS);

        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(deliverableRepository.saveAndFlush(any(Deliverable.class))).thenAnswer(i -> {
            Deliverable d = i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        SubmitDeliverableRequest req = new SubmitDeliverableRequest();
        req.setFileName("figma_designs.zip");
        req.setFileUrl("https://storage.finx.test/deliverables/figma.zip");

        DeliverableResponse response = milestoneService.submitDeliverable(milestone.getId(), req, sellerPrincipal);

        assertThat(response.getFileName()).isEqualTo("figma_designs.zip");
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.UNDER_REVIEW);
        verify(auditService).logEvent(eq(sellerId), eq("DELIVERABLE_SUBMITTED"), eq("DELIVERABLE"), any(), any());
    }

    @Test
    @DisplayName("Buyer approves milestone under review")
    void approveMilestone_success() {
        Milestone milestone = new Milestone(dealId, "UI Design", "Desc", 1, BigDecimal.valueOf(10000), "INR", null);
        milestone.setId(UUID.randomUUID());
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);

        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        MilestoneResponse response = milestoneService.approveMilestone(milestone.getId(), buyerPrincipal);

        assertThat(response.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        verify(auditService).logEvent(eq(buyerId), eq("MILESTONE_APPROVED"), eq("MILESTONE"), any(), any());
    }

    @Test
    @DisplayName("Vendor cannot approve their own milestone")
    void approveMilestone_unauthorizedForVendor() {
        Milestone milestone = new Milestone(dealId, "UI Design", "Desc", 1, BigDecimal.valueOf(10000), "INR", null);
        milestone.setId(UUID.randomUUID());
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);

        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> milestoneService.approveMilestone(milestone.getId(), sellerPrincipal))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("Fail to create milestone if milestone amount exceeds total deal amount")
    void createMilestone_exceedsDealTotal_ThrowsException() {
        CreateMilestoneRequest req = new CreateMilestoneRequest();
        req.setTitle("Overpriced Milestone");
        req.setAmount(BigDecimal.valueOf(60000)); // Deal total is 50000

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByDealIdOrderBySequenceAsc(dealId)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> milestoneService.createMilestone(dealId, req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot exceed deal total value");
    }

    @Test
    @DisplayName("Fail to create milestone if deal is in terminal status (COMPLETED)")
    void createMilestone_completedDeal_ThrowsException() {
        deal.setStatus(DealStatus.COMPLETED);
        CreateMilestoneRequest req = new CreateMilestoneRequest();
        req.setTitle("Late Milestone");
        req.setAmount(BigDecimal.valueOf(5000));

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> milestoneService.createMilestone(dealId, req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot create milestones for a COMPLETED deal");
    }
}
