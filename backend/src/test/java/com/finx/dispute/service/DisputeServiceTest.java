package com.finx.dispute.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.dto.request.CreateDisputeRequest;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.exception.BadRequestException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    @Mock
    private DisputeRepository disputeRepository;
    @Mock
    private DealRepository dealRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private MilestoneRepository milestoneRepository;

    private DisputeService service;
    private UUID dealId;
    private UUID buyerId;
    private UUID sellerId;
    private Deal deal;

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputeRepository, dealRepository, auditService, notificationService);
        ReflectionTestUtils.setField(service, "milestones", milestoneRepository);
        dealId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        deal = new Deal("Escrow project", "Test project", buyerId, sellerId,
                new BigDecimal("5000.00"), "INR", DealStatus.ACCEPTED);
        deal.setId(dealId);
    }

    @Test
    void createDisputeTrimsReasonMarksDealDisputedAndNotifiesOtherParty() {
        CreateDisputeRequest request = new CreateDisputeRequest();
        request.setDealId(dealId);
        request.setReason("  Delivery is incomplete  ");
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.saveAndFlush(any(Dispute.class))).thenAnswer(invocation -> {
            Dispute saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        var response = service.createDispute(request, principal(buyerId, Role.BUYER));

        assertThat(response.getReason()).isEqualTo("Delivery is incomplete");
        assertThat(response.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(deal.getStatus()).isEqualTo(DealStatus.DISPUTED);
        verify(dealRepository).save(deal);
        verify(notificationService).sendNotification(eq(sellerId), eq("Dispute Raised on Deal"),
                contains("Escrow project"), eq("/corporate/disputes"));
        verify(auditService).logEvent(eq(buyerId), eq("DISPUTE_CREATED"), eq("DISPUTE"),
                eq(response.getId().toString()), contains("Delivery is incomplete"));
    }

    @Test
    void createDisputeRejectsNonParty() {
        UUID outsiderId = UUID.randomUUID();
        CreateDisputeRequest request = new CreateDisputeRequest();
        request.setDealId(dealId);
        request.setReason("Issue");
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> service.createDispute(request, principal(outsiderId, Role.BUYER)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only participating parties");

        verifyNoInteractions(disputeRepository, notificationService, auditService);
        assertThat(deal.getStatus()).isEqualTo(DealStatus.ACCEPTED);
    }

    @Test
    void createDisputeRejectsMilestoneFromAnotherDeal() {
        UUID milestoneId = UUID.randomUUID();
        UUID otherDealId = UUID.randomUUID();
        Milestone milestone = mock(Milestone.class);
        when(milestone.getDealId()).thenReturn(otherDealId);
        CreateDisputeRequest request = new CreateDisputeRequest();
        request.setDealId(dealId);
        request.setMilestoneId(milestoneId);
        request.setReason("Issue");
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));

        assertThatThrownBy(() -> service.createDispute(request, principal(buyerId, Role.BUYER)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Milestone is not in this deal");

        verifyNoInteractions(disputeRepository, notificationService, auditService);
    }

    @Test
    void resolveDisputeRestoresDealWhenNoOpenDisputesRemain() {
        UUID disputeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Dispute dispute = new Dispute(dealId, null, buyerId, "Issue");
        dispute.setId(disputeId);
        deal.setStatus(DealStatus.DISPUTED);
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(disputeRepository.saveAndFlush(dispute)).thenReturn(dispute);
        when(disputeRepository.existsByDealIdAndStatus(dealId, DisputeStatus.OPEN)).thenReturn(false);
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        var response = service.resolveDispute(disputeId, "  Resolved in favor of seller  ", principal(adminId, Role.ADMIN));

        assertThat(response.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(dispute.getResolutionNotes()).isEqualTo("Resolved in favor of seller");
        assertThat(dispute.getResolvedBy()).isEqualTo(adminId);
        assertThat(dispute.getResolvedAt()).isNotNull();
        assertThat(deal.getStatus()).isEqualTo(DealStatus.ACCEPTED);
        verify(dealRepository).saveAndFlush(deal);
        verify(auditService).logEvent(adminId, "DISPUTE_RESOLVED", "DISPUTE", disputeId.toString(),
                "Dispute resolved:   Resolved in favor of seller  ");
    }

    @Test
    void resolveDisputeRejectsNonAdministratorBeforeLoadingDispute() {
        UUID disputeId = UUID.randomUUID();

        assertThatThrownBy(() -> service.resolveDispute(disputeId, "Resolution", principal(buyerId, Role.BUYER)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only an administrator");

        verifyNoInteractions(disputeRepository, dealRepository, auditService);
    }

    private static UserPrincipal principal(UUID id, Role role) {
        return new UserPrincipal(id, "Test user", id + "@finx.test", "unused", role,
                UserStatus.ACTIVE, java.util.List.of());
    }
}