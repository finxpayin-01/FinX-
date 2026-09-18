package com.finx.projectmanager;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.projectmanager.dto.response.ProjectManagerInvitationResponse;
import com.finx.projectmanager.entity.InvitationStatus;
import com.finx.projectmanager.entity.ProjectManagerInvitation;
import com.finx.projectmanager.repository.ProjectManagerInvitationRepository;
import com.finx.projectmanager.service.ProjectManagerInvitationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.user.service.PmIdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ProjectManagerInvitationServiceTest {

    private ProjectManagerInvitationRepository invitationRepository;
    private DealRepository dealRepository;
    private UserRepository userRepository;
    private MilestoneRepository milestoneRepository;
    private PmIdService pmIdService;
    private AuditService auditService;
    private NotificationService notificationService;

    private ProjectManagerInvitationService invitationService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID pmId;
    private UUID otherUserId;
    private Deal testDeal;
    private User buyerUser;
    private User pmUser;

    @BeforeEach
    public void setup() {
        invitationRepository = Mockito.mock(ProjectManagerInvitationRepository.class);
        dealRepository = Mockito.mock(DealRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        milestoneRepository = Mockito.mock(MilestoneRepository.class);
        pmIdService = Mockito.mock(PmIdService.class);
        auditService = Mockito.mock(AuditService.class);
        notificationService = Mockito.mock(NotificationService.class);

        invitationService = new ProjectManagerInvitationService(
                invitationRepository,
                dealRepository,
                userRepository,
                milestoneRepository,
                pmIdService,
                auditService,
                notificationService
        );

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        pmId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();

        testDeal = new Deal("Test Project", "Description", buyerId, sellerId, BigDecimal.valueOf(10000), "INR", DealStatus.ACCEPTED);
        testDeal.setId(UUID.randomUUID());
        testDeal.setProjectId("PRJ-12345678");

        buyerUser = new User("Buyer Corp", "buyer@finx.com", "hash", Role.BUYER, UserStatus.ACTIVE);
        buyerUser.setId(buyerId);

        User sellerUser = new User("Seller Corp", "seller@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        sellerUser.setId(sellerId);

        pmUser = new User("Jane PM", "pm@finx.com", "hash", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        pmUser.setId(pmId);
        pmUser.setPmId("FINX-PM-K7X4Q9");

        User otherUser = new User("Other PM", "other@finx.com", "hash", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        otherUser.setId(otherUserId);
    }

    @Test
    public void testInviteProjectManager_Success() {
        UserPrincipal buyerPrincipal = UserPrincipal.create(buyerUser);

        when(dealRepository.findById(testDeal.getId())).thenReturn(Optional.of(testDeal));
        when(pmIdService.resolveProjectManager(pmUser.getPmId())).thenReturn(Optional.of(pmUser));
        when(invitationRepository.existsByDealIdAndStatus(testDeal.getId(), InvitationStatus.PENDING)).thenReturn(false);
        when(invitationRepository.saveAndFlush(any(ProjectManagerInvitation.class))).thenAnswer(i -> {
            ProjectManagerInvitation inv = i.getArgument(0);
            inv.setId(UUID.randomUUID());
            return inv;
        });

        ProjectManagerInvitationResponse response = invitationService.inviteProjectManager(testDeal.getId(), pmUser.getPmId(), buyerPrincipal);

        assertNotNull(response);
        assertEquals(InvitationStatus.PENDING, response.getStatus());
        assertEquals(pmId, response.getProjectManagerId());
        verify(auditService).logEvent(eq(buyerId), eq("PM_INVITED_TO_PROJECT"), eq("DEAL"), eq(testDeal.getId().toString()), any());
        verify(notificationService).sendNotification(eq(pmId), anyString(), anyString(), anyString());
    }

    @Test
    public void testInviteProjectManager_UnauthorizedUser() {
        User sellerUser = new User("Seller Corp", "seller@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        sellerUser.setId(sellerId);
        UserPrincipal sellerPrincipal = UserPrincipal.create(sellerUser);

        when(dealRepository.findById(testDeal.getId())).thenReturn(Optional.of(testDeal));

        assertThrows(UnauthorizedException.class, () ->
                invitationService.inviteProjectManager(testDeal.getId(), pmUser.getPmId(), sellerPrincipal)
        );
    }

    @Test
    public void testInviteProjectManager_DuplicatePending() {
        UserPrincipal buyerPrincipal = UserPrincipal.create(buyerUser);

        when(dealRepository.findById(testDeal.getId())).thenReturn(Optional.of(testDeal));
        when(pmIdService.resolveProjectManager(pmUser.getPmId())).thenReturn(Optional.of(pmUser));
        when(invitationRepository.existsByDealIdAndStatus(testDeal.getId(), InvitationStatus.PENDING)).thenReturn(true);

        assertThrows(BadRequestException.class, () ->
                invitationService.inviteProjectManager(testDeal.getId(), pmUser.getPmId(), buyerPrincipal)
        );
    }

    @Test
    public void testAcceptInvitation_Success() {
        UserPrincipal pmPrincipal = UserPrincipal.create(pmUser);
        ProjectManagerInvitation invitation = new ProjectManagerInvitation(testDeal.getId(), pmId, buyerId);
        invitation.setId(UUID.randomUUID());

        when(invitationRepository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(dealRepository.findById(testDeal.getId())).thenReturn(Optional.of(testDeal));
        when(invitationRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(dealRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(pmId)).thenReturn(Optional.of(pmUser));

        ProjectManagerInvitationResponse response = invitationService.acceptInvitation(invitation.getId(), pmPrincipal);

        assertEquals(InvitationStatus.ACCEPTED, response.getStatus());
        assertEquals(pmId, testDeal.getProjectManagerId(), "Deal must now have assigned projectManagerId");
        assertNotNull(invitation.getAcceptedAt());
        verify(auditService).logEvent(eq(pmId), eq("PM_INVITATION_ACCEPTED"), eq("DEAL"), eq(testDeal.getId().toString()), any());
        verify(auditService).logEvent(eq(pmId), eq("PM_ASSIGNED_TO_PROJECT"), eq("DEAL"), eq(testDeal.getId().toString()), any());
        verify(notificationService).sendNotification(eq(buyerId), anyString(), anyString(), anyString());
    }

    @Test
    public void testAcceptInvitation_IdorProtection() {
        // Other user attempts to accept PM's invitation
        User otherUser = new User("Other PM", "other@finx.com", "hash", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        otherUser.setId(otherUserId);
        UserPrincipal otherPrincipal = UserPrincipal.create(otherUser);

        ProjectManagerInvitation invitation = new ProjectManagerInvitation(testDeal.getId(), pmId, buyerId);
        invitation.setId(UUID.randomUUID());

        when(invitationRepository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(dealRepository.findById(testDeal.getId())).thenReturn(Optional.of(testDeal));

        assertThrows(UnauthorizedException.class, () ->
                invitationService.acceptInvitation(invitation.getId(), otherPrincipal)
        );
        assertNull(testDeal.getProjectManagerId(), "Deal must NOT be assigned");
    }

    @Test
    public void testDeclineInvitation_Success() {
        UserPrincipal pmPrincipal = UserPrincipal.create(pmUser);
        ProjectManagerInvitation invitation = new ProjectManagerInvitation(testDeal.getId(), pmId, buyerId);
        invitation.setId(UUID.randomUUID());

        when(invitationRepository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(dealRepository.findById(testDeal.getId())).thenReturn(Optional.of(testDeal));
        when(invitationRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(pmId)).thenReturn(Optional.of(pmUser));

        ProjectManagerInvitationResponse response = invitationService.declineInvitation(invitation.getId(), "Busy with other projects", pmPrincipal);

        assertEquals(InvitationStatus.DECLINED, response.getStatus());
        assertEquals("Busy with other projects", response.getDeclineReason());
        assertNull(testDeal.getProjectManagerId(), "Deal must NOT be assigned upon decline");
        verify(auditService).logEvent(eq(pmId), eq("PM_INVITATION_DECLINED"), eq("DEAL"), eq(testDeal.getId().toString()), any());
        verify(notificationService).sendNotification(eq(buyerId), anyString(), anyString(), anyString());
    }

    @Test
    public void testCancelInvitation_Success() {
        UserPrincipal buyerPrincipal = UserPrincipal.create(buyerUser);
        ProjectManagerInvitation invitation = new ProjectManagerInvitation(testDeal.getId(), pmId, buyerId);
        invitation.setId(UUID.randomUUID());

        when(dealRepository.findById(testDeal.getId())).thenReturn(Optional.of(testDeal));
        when(invitationRepository.findById(invitation.getId())).thenReturn(Optional.of(invitation));
        when(invitationRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(pmId)).thenReturn(Optional.of(pmUser));

        ProjectManagerInvitationResponse response = invitationService.cancelInvitation(testDeal.getId(), invitation.getId(), buyerPrincipal);

        assertEquals(InvitationStatus.CANCELLED, response.getStatus());
        assertNotNull(response.getCancelledAt());
        verify(auditService).logEvent(eq(buyerId), eq("PM_INVITATION_CANCELLED"), eq("DEAL"), eq(testDeal.getId().toString()), any());
        verify(notificationService).sendNotification(eq(pmId), anyString(), anyString(), anyString());
    }
}
