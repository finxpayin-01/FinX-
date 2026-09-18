package com.finx.projectmanager.service;

import com.finx.audit.service.AuditService;
import com.finx.auth.dto.response.UserSummaryResponse;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.projectmanager.dto.response.DealProjectManagerInfoResponse;
import com.finx.projectmanager.dto.response.ProjectManagerInvitationResponse;
import com.finx.projectmanager.entity.InvitationStatus;
import com.finx.projectmanager.entity.ProjectManagerInvitation;
import com.finx.projectmanager.repository.ProjectManagerInvitationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.user.service.PmIdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectManagerInvitationService {

    private static final Logger log = LoggerFactory.getLogger(ProjectManagerInvitationService.class);

    private final ProjectManagerInvitationRepository invitationRepository;
    private final DealRepository dealRepository;
    private final UserRepository userRepository;
    private final MilestoneRepository milestoneRepository;
    private final PmIdService pmIdService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public ProjectManagerInvitationService(ProjectManagerInvitationRepository invitationRepository,
                                         DealRepository dealRepository,
                                         UserRepository userRepository,
                                         MilestoneRepository milestoneRepository,
                                         PmIdService pmIdService,
                                         AuditService auditService,
                                         NotificationService notificationService) {
        this.invitationRepository = invitationRepository;
        this.dealRepository = dealRepository;
        this.userRepository = userRepository;
        this.milestoneRepository = milestoneRepository;
        this.pmIdService = pmIdService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public ProjectManagerInvitationResponse inviteProjectManager(UUID dealId, String pmIdentifier, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        // 1. RBAC: Only project owner (Buyer) or ADMIN can invite PM
        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer or an administrator can invite a Project Manager to this project");
        }

        // 2. Resolve eligible Project Manager
        User pm = resolvePm(pmIdentifier);
        if (pm.getRole() != Role.PROJECT_MANAGER) {
            throw new BadRequestException("The target user is not a registered Project Manager");
        }

        // 3. Prevent duplicate active project assignment
        if (deal.getProjectManagerId() != null) {
            if (deal.getProjectManagerId().equals(pm.getId())) {
                throw new BadRequestException("This Project Manager is already actively assigned to this project");
            }
            throw new BadRequestException("This project already has an active Project Manager. Reassignment or removal is required before inviting another.");
        }

        // 4. Prevent duplicate PENDING invitations
        if (invitationRepository.existsByDealIdAndStatus(dealId, InvitationStatus.PENDING)) {
            throw new BadRequestException("An active invitation is already pending for this project. Cancel it before creating a new one.");
        }

        // 5. Create invitation
        ProjectManagerInvitation invitation = new ProjectManagerInvitation(dealId, pm.getId(), currentUser.getId());
        ProjectManagerInvitation saved = invitationRepository.saveAndFlush(invitation);

        // 6. AuditLog
        auditService.logEvent(
                currentUser.getId(),
                "PM_INVITED_TO_PROJECT",
                "DEAL",
                deal.getId().toString(),
                "Invited PM " + pm.getName() + " (" + (pm.getPmId() != null ? pm.getPmId() : pm.getId()) + ") to project " + deal.getProjectId()
        );

        // 7. Notification to PM
        notificationService.sendNotification(
                pm.getId(),
                "New Project Invitation",
                "You have been invited to manage project '" + deal.getTitle() + "' (" + deal.getProjectId() + ")",
                "/project-manager/invitations"
        );

        log.info("PM invitation created: id={} dealId={} pmId={} invitedBy={}",
                saved.getId(), deal.getId(), pm.getId(), currentUser.getId());

        return enrichResponse(saved, deal, pm, currentUser.getId());
    }

    @Transactional
    public ProjectManagerInvitationResponse acceptInvitation(UUID invitationId, UserPrincipal currentUser) {
        ProjectManagerInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectManagerInvitation", "id", invitationId));

        Deal deal = dealRepository.findById(invitation.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", invitation.getDealId()));

        // 1. Verify ownership & role
        if (currentUser.getRole() != Role.ADMIN && !invitation.getProjectManagerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You are not authorized to accept this invitation");
        }

        // 2. Verify state
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BadRequestException("Invitation cannot be accepted in its current state: " + invitation.getStatus());
        }

        // 3. Atomically transition invitation and activate deal assignment
        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        ProjectManagerInvitation savedInvitation = invitationRepository.saveAndFlush(invitation);

        deal.setProjectManagerId(invitation.getProjectManagerId());
        dealRepository.saveAndFlush(deal);

        User pm = userRepository.findById(invitation.getProjectManagerId()).orElse(null);

        // 4. AuditLog
        auditService.logEvent(
                currentUser.getId(),
                "PM_INVITATION_ACCEPTED",
                "DEAL",
                deal.getId().toString(),
                "PM accepted invitation for project " + deal.getProjectId()
        );
        auditService.logEvent(
                currentUser.getId(),
                "PM_ASSIGNED_TO_PROJECT",
                "DEAL",
                deal.getId().toString(),
                "PM assigned to project " + deal.getProjectId()
        );

        // 5. Notification to Buyer
        notificationService.sendNotification(
                deal.getBuyerId(),
                "Project Manager Assigned",
                (pm != null ? pm.getName() : "The Project Manager") + " accepted the invitation to manage project '" + deal.getTitle() + "'",
                "/corporate/projects/" + deal.getId()
        );

        log.info("PM invitation accepted: id={} dealId={} pmId={}", savedInvitation.getId(), deal.getId(), currentUser.getId());

        return enrichResponse(savedInvitation, deal, pm, invitation.getInvitedBy());
    }

    @Transactional
    public ProjectManagerInvitationResponse declineInvitation(UUID invitationId, String reason, UserPrincipal currentUser) {
        ProjectManagerInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectManagerInvitation", "id", invitationId));

        Deal deal = dealRepository.findById(invitation.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", invitation.getDealId()));

        // 1. Verify ownership & role
        if (currentUser.getRole() != Role.ADMIN && !invitation.getProjectManagerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You are not authorized to decline this invitation");
        }

        // 2. Verify state
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BadRequestException("Invitation cannot be declined in its current state: " + invitation.getStatus());
        }

        // 3. Atomically transition invitation
        invitation.setStatus(InvitationStatus.DECLINED);
        invitation.setDeclinedAt(Instant.now());
        invitation.setDeclineReason(reason != null && !reason.isBlank() ? reason.trim() : "Declined by Project Manager");
        ProjectManagerInvitation savedInvitation = invitationRepository.saveAndFlush(invitation);

        User pm = userRepository.findById(invitation.getProjectManagerId()).orElse(null);

        // 4. AuditLog
        auditService.logEvent(
                currentUser.getId(),
                "PM_INVITATION_DECLINED",
                "DEAL",
                deal.getId().toString(),
                "PM declined invitation for project " + deal.getProjectId() + (invitation.getDeclineReason() != null ? ". Reason: " + invitation.getDeclineReason() : "")
        );

        // 5. Notification to Buyer
        notificationService.sendNotification(
                deal.getBuyerId(),
                "Project Manager Declined Invitation",
                (pm != null ? pm.getName() : "The Project Manager") + " declined the invitation for project '" + deal.getTitle() + "'",
                "/corporate/projects/" + deal.getId()
        );

        log.info("PM invitation declined: id={} dealId={} pmId={}", savedInvitation.getId(), deal.getId(), currentUser.getId());

        return enrichResponse(savedInvitation, deal, pm, invitation.getInvitedBy());
    }

    @Transactional
    public ProjectManagerInvitationResponse cancelInvitation(UUID dealId, UUID invitationId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        // 1. RBAC: Only project owner (Buyer) or ADMIN can cancel
        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the project owner or administrator can cancel invitations");
        }

        ProjectManagerInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectManagerInvitation", "id", invitationId));

        if (!invitation.getDealId().equals(dealId)) {
            throw new BadRequestException("Invitation does not belong to this project");
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BadRequestException("Only PENDING invitations can be cancelled. Current status: " + invitation.getStatus());
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitation.setCancelledAt(Instant.now());
        ProjectManagerInvitation savedInvitation = invitationRepository.saveAndFlush(invitation);

        // AuditLog
        auditService.logEvent(
                currentUser.getId(),
                "PM_INVITATION_CANCELLED",
                "DEAL",
                deal.getId().toString(),
                "Cancelled PM invitation for project " + deal.getProjectId()
        );

        // Notification to PM
        notificationService.sendNotification(
                invitation.getProjectManagerId(),
                "Project Invitation Cancelled",
                "The invitation to manage project '" + deal.getTitle() + "' was cancelled",
                "/project-manager/invitations"
        );

        User pm = userRepository.findById(invitation.getProjectManagerId()).orElse(null);
        return enrichResponse(savedInvitation, deal, pm, invitation.getInvitedBy());
    }

    @Transactional(readOnly = true)
    public DealProjectManagerInfoResponse getDealProjectManagerInfo(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        UserSummaryResponse activePmSummary = null;
        if (deal.getProjectManagerId() != null) {
            activePmSummary = userRepository.findById(deal.getProjectManagerId())
                    .map(UserSummaryResponse::fromEntity)
                    .orElse(null);
        }

        Optional<ProjectManagerInvitation> latestInvitationOpt = invitationRepository.findFirstByDealIdOrderByCreatedAtDesc(dealId);
        ProjectManagerInvitationResponse latestInvitation = latestInvitationOpt
                .map(inv -> {
                    User pm = userRepository.findById(inv.getProjectManagerId()).orElse(null);
                    return enrichResponse(inv, deal, pm, inv.getInvitedBy());
                })
                .orElse(null);

        return new DealProjectManagerInfoResponse(
                deal.getId(),
                deal.getProjectId(),
                deal.getProjectManagerId() != null,
                activePmSummary,
                latestInvitation
        );
    }

    @Transactional(readOnly = true)
    public List<ProjectManagerInvitationResponse> getMyInvitations(UserPrincipal currentUser, InvitationStatus status) {
        List<ProjectManagerInvitation> list;
        if (status != null) {
            list = invitationRepository.findByProjectManagerIdAndStatusOrderByCreatedAtDesc(currentUser.getId(), status);
        } else {
            list = invitationRepository.findByProjectManagerIdOrderByCreatedAtDesc(currentUser.getId());
        }

        User pm = userRepository.findById(currentUser.getId()).orElse(null);

        return list.stream().map(inv -> {
            Deal deal = dealRepository.findById(inv.getDealId()).orElse(null);
            return enrichResponse(inv, deal, pm, inv.getInvitedBy());
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProjectManagerInvitationResponse getInvitationById(UUID id, UserPrincipal currentUser) {
        ProjectManagerInvitation invitation = invitationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectManagerInvitation", "id", id));

        Deal deal = dealRepository.findById(invitation.getDealId()).orElse(null);

        // Protect IDOR: Only invited PM, project Buyer, or ADMIN can view invitation
        boolean isInvitedPm = invitation.getProjectManagerId().equals(currentUser.getId());
        boolean isDealBuyer = deal != null && deal.getBuyerId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getRole() == Role.ADMIN;

        if (!isInvitedPm && !isDealBuyer && !isAdmin) {
            throw new UnauthorizedException("You are not authorized to view this invitation");
        }

        User pm = userRepository.findById(invitation.getProjectManagerId()).orElse(null);
        return enrichResponse(invitation, deal, pm, invitation.getInvitedBy());
    }

    @Transactional(readOnly = true)
    public long getPendingInvitationCount(UUID pmId) {
        return invitationRepository.countByProjectManagerIdAndStatus(pmId, InvitationStatus.PENDING);
    }

    private User resolvePm(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new BadRequestException("Project Manager identifier cannot be empty");
        }
        return pmIdService.resolveProjectManager(identifier)
                .or(() -> {
                    try {
                        UUID uuid = UUID.fromString(identifier.trim());
                        return userRepository.findById(uuid);
                    } catch (IllegalArgumentException ignored) {
                        return Optional.empty();
                    }
                })
                .orElseThrow(() -> new BadRequestException("Could not find an eligible Project Manager with identifier: " + identifier));
    }

    private ProjectManagerInvitationResponse enrichResponse(ProjectManagerInvitation inv, Deal deal, User pm, UUID invitedById) {
        ProjectManagerInvitationResponse resp = ProjectManagerInvitationResponse.fromEntity(inv);

        if (deal != null) {
            resp.setProjectId(deal.getProjectId());
            resp.setProjectTitle(deal.getTitle());
            resp.setProjectDescription(deal.getDescription());
            resp.setBuyerId(deal.getBuyerId());
            resp.setSellerId(deal.getSellerId());
            resp.setTotalAmount(deal.getTotalAmount());
            resp.setCurrency(deal.getCurrency());

            userRepository.findById(deal.getBuyerId()).ifPresent(buyer -> {
                resp.setBuyerName(buyer.getName());
                resp.setBuyerEmail(buyer.getEmail());
            });

            userRepository.findById(deal.getSellerId()).ifPresent(seller -> {
                resp.setSellerName(seller.getName());
            });

            resp.setMilestoneCount(milestoneRepository.findByDealIdOrderBySequenceAsc(deal.getId()).size());
        }

        if (pm != null) {
            resp.setProjectManagerName(pm.getName());
            resp.setProjectManagerPmId(pm.getPmId());
            resp.setProjectManagerEmail(pm.getEmail());
        }

        if (invitedById != null) {
            userRepository.findById(invitedById).ifPresent(inviter -> {
                resp.setInvitedByName(inviter.getName());
            });
        }

        return resp;
    }
}
