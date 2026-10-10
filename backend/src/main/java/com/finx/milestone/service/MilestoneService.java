package com.finx.milestone.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import java.math.BigDecimal;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.dto.request.CreateMilestoneRequest;
import com.finx.milestone.dto.request.SubmitDeliverableRequest;
import com.finx.milestone.dto.response.DeliverableResponse;
import com.finx.milestone.dto.response.MilestoneResponse;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MilestoneService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneService.class);

    private final MilestoneRepository milestoneRepository;
    private final DeliverableRepository deliverableRepository;
    private final DealRepository dealRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final EscrowService escrowService;
    private final com.finx.milestone.reminder.MilestoneReminderService milestoneReminderService;
    private final com.finx.common.time.FinxClock clock;
    private final com.finx.approval.service.MilestoneApprovalBindingService approvalBindingService;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    public MilestoneService(MilestoneRepository milestoneRepository,
                            DeliverableRepository deliverableRepository,
                            DealRepository dealRepository,
                            AuditService auditService,
                            NotificationService notificationService,
                            EscrowService escrowService,
                            com.finx.milestone.reminder.MilestoneReminderService milestoneReminderService,
                            com.finx.common.time.FinxClock clock,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) com.finx.approval.service.MilestoneApprovalBindingService approvalBindingService) {
        this.milestoneRepository = milestoneRepository;
        this.deliverableRepository = deliverableRepository;
        this.dealRepository = dealRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.escrowService = escrowService;
        this.milestoneReminderService = milestoneReminderService;
        this.clock = clock;
        this.approvalBindingService = approvalBindingService;
    }

    public MilestoneService(MilestoneRepository milestoneRepository,
                            DeliverableRepository deliverableRepository,
                            DealRepository dealRepository,
                            AuditService auditService,
                            NotificationService notificationService,
                            EscrowService escrowService,
                            com.finx.milestone.reminder.MilestoneReminderService milestoneReminderService,
                            com.finx.common.time.FinxClock clock) {
        this(milestoneRepository, deliverableRepository, dealRepository, auditService, notificationService, escrowService, milestoneReminderService, clock, null);
    }

    @Transactional
    public MilestoneResponse createMilestone(UUID dealId, CreateMilestoneRequest request, UserPrincipal currentUser) {
        Deal deal = dealRepository.findByIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the buyer or an admin can create milestones for this deal");
        }

        if (deal.getStatus() != DealStatus.ACCEPTED && 
            deal.getStatus() != DealStatus.MILESTONES_PENDING && 
            deal.getStatus() != DealStatus.AWAITING_FUNDING &&
            deal.getStatus() != DealStatus.IN_PROGRESS) {
            throw new BadRequestException("Cannot create milestones for a " + deal.getStatus() + " deal");
        }

        List<Milestone> existingMilestones = milestoneRepository.findByDealIdOrderBySequenceAsc(dealId);
        BigDecimal currentAllocated = existingMilestones.stream()
                .filter(m -> m.getStatus() != MilestoneStatus.CANCELLED)
                .map(Milestone::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal projectedTotal = currentAllocated.add(request.getAmount());
        if (deal.getTotalAmount() != null && projectedTotal.compareTo(deal.getTotalAmount()) > 0) {
            throw new BadRequestException(String.format(
                    "Total milestone allocations (%s %s) cannot exceed deal total value (%s %s). Already allocated: %s %s.",
                    projectedTotal, deal.getCurrency(),
                    deal.getTotalAmount(), deal.getCurrency(),
                    currentAllocated, deal.getCurrency()
            ));
        }

        int sequence = (request.getSequence() != null && request.getSequence() > 0)
                ? request.getSequence()
                : (int) (milestoneRepository.countByDealId(dealId) + 1);

        String currency = (request.getCurrency() != null && !request.getCurrency().isBlank())
                ? request.getCurrency().toUpperCase()
                : deal.getCurrency();

        Instant deadline = request.getSubmissionDeadline() != null ? request.getSubmissionDeadline() : request.getDueDate();
        Instant dueDate = request.getDueDate() != null ? request.getDueDate() : deadline;

        Milestone milestone = new Milestone(
                deal.getId(),
                request.getTitle().trim(),
                request.getDescription() != null ? request.getDescription().trim() : null,
                sequence,
                request.getAmount(),
                currency,
                dueDate,
                deadline
        );

        milestone.setInvoiceRequired(request.isInvoiceRequired());
        Milestone saved = milestoneRepository.saveAndFlush(milestone);
        escrowService.ensureEscrowAccount(deal.getId(), deal.getCurrency());

        // Schedule persistent deadline reminders
        if (saved.getSubmissionDeadline() != null) {
            milestoneReminderService.scheduleRemindersForMilestone(saved, deal, 1);
            auditService.logEvent(
                    currentUser.getId(),
                    "MILESTONE_DEADLINE_CREATED",
                    "MILESTONE",
                    saved.getId().toString(),
                    "Created milestone submission deadline: " + saved.getSubmissionDeadline()
            );
        }

        auditService.logEvent(
                currentUser.getId(),
                "MILESTONE_CREATED",
                "MILESTONE",
                saved.getId().toString(),
                "Created milestone '" + saved.getTitle() + "' (" + saved.getAmount() + " " + saved.getCurrency() + ") for deal: " + deal.getTitle()
        );

        notificationService.sendNotification(
                deal.getSellerId(),
                "New Milestone Added",
                "Buyer added milestone '" + saved.getTitle() + "' to deal '" + deal.getTitle() + "'.",
                "/vendor/projects/" + deal.getId()
        );

        log.info("Milestone created: id={} dealId={} sequence={} amount={}", saved.getId(), dealId, sequence, saved.getAmount());
        return MilestoneResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<MilestoneResponse> getMilestonesByDealId(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        validatePartyAccess(deal, currentUser);

        List<Milestone> milestones = milestoneRepository.findByDealIdOrderBySequenceAsc(dealId);
        return milestones.stream()
                .map(m -> {
                    List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(m.getId());
                    m.setDeliverables(deliverables);
                    return MilestoneResponse.fromEntity(m);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public MilestoneResponse getMilestoneById(UUID milestoneId, UserPrincipal currentUser) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        validatePartyAccess(deal, currentUser);

        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId());
        milestone.setDeliverables(deliverables);
        return MilestoneResponse.fromEntity(milestone);
    }

    @Transactional(readOnly = true)
    public List<MilestoneResponse> getAllMilestonesForCurrentUser(UserPrincipal currentUser) {
        List<Deal> userDeals;
        if (currentUser.getRole() == Role.ADMIN) {
            userDeals = dealRepository.findAll();
        } else if (currentUser.getRole() == Role.BUYER) {
            userDeals = dealRepository.findByBuyerId(currentUser.getId());
        } else if (currentUser.getRole() == Role.PROJECT_MANAGER) {
            userDeals = dealRepository.findByProjectManagerId(currentUser.getId());
        } else {
            userDeals = dealRepository.findBySellerId(currentUser.getId());
        }

        if (userDeals.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> dealIds = userDeals.stream().map(Deal::getId).collect(Collectors.toList());
        List<Milestone> milestones = milestoneRepository.findByDealIdInOrderByCreatedAtDesc(dealIds);
        return milestones.stream()
                .map(m -> {
                    List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(m.getId());
                    m.setDeliverables(deliverables);
                    return MilestoneResponse.fromEntity(m);
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public MilestoneResponse startMilestone(UUID milestoneId, UserPrincipal currentUser) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (currentUser.getRole() != Role.ADMIN && !deal.getSellerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the assigned vendor can start working on this milestone");
        }

        if (milestone.getStatus() != MilestoneStatus.PENDING) {
            throw new BadRequestException("Milestone cannot be started from current status: " + milestone.getStatus());
        }

        milestone.setStatus(MilestoneStatus.IN_PROGRESS);
        Milestone updated = milestoneRepository.saveAndFlush(milestone);

        auditService.logEvent(
                currentUser.getId(),
                "MILESTONE_STARTED",
                "MILESTONE",
                updated.getId().toString(),
                "Vendor started milestone: " + updated.getTitle()
        );

        notificationService.sendNotification(
                deal.getBuyerId(),
                "Milestone Work Started",
                "Vendor has commenced work on milestone '" + updated.getTitle() + "'.",
                "/corporate/projects/" + deal.getId()
        );

        return MilestoneResponse.fromEntity(updated);
    }

    @Transactional
    public DeliverableResponse submitDeliverable(UUID milestoneId, SubmitDeliverableRequest request, UserPrincipal currentUser) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (currentUser.getRole() != Role.ADMIN && !deal.getSellerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the assigned vendor can submit deliverables for this milestone");
        }

        if (deal.getStatus() != com.finx.deal.entity.DealStatus.ACCEPTED && deal.getStatus() != DealStatus.IN_PROGRESS && deal.getStatus() != DealStatus.FUNDED) {
            throw new BadRequestException("Cannot submit deliverables: project is not active (current status: " + deal.getStatus() + ")");
        }

        if (milestone.getStatus() == MilestoneStatus.APPROVED || milestone.getStatus() == MilestoneStatus.COMPLETED) {
            throw new BadRequestException("Cannot submit deliverables for an already approved or completed milestone");
        }

        Deliverable deliverable = new Deliverable(
                milestone.getId(),
                currentUser.getId(),
                request.getFileName().trim(),
                request.getFileUrl().trim(),
                request.getDescription() != null ? request.getDescription().trim() : null
        );

        Deliverable savedDeliverable = deliverableRepository.saveAndFlush(deliverable);

        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);
        milestoneRepository.saveAndFlush(milestone);

        // Cancel all remaining pre-deadline reminders since submission has been made
        milestoneReminderService.cancelPendingPreDeadlineReminders(milestoneId);

        auditService.logEvent(
                currentUser.getId(),
                "DELIVERABLE_SUBMITTED",
                "DELIVERABLE",
                savedDeliverable.getId().toString(),
                "Submitted deliverable: " + savedDeliverable.getFileName() + " for milestone: " + milestone.getTitle()
        );

        auditService.logEvent(
                currentUser.getId(),
                "MILESTONE_SUBMITTED",
                "MILESTONE",
                milestoneId.toString(),
                "Vendor submitted deliverable for milestone: " + milestone.getTitle()
        );

        notificationService.sendNotification(
                deal.getBuyerId(),
                "Deliverable Ready for Review",
                "Vendor submitted deliverable for milestone '" + milestone.getTitle() + "'. Please review and approve.",
                "/corporate/projects/" + deal.getId()
        );

        log.info("Deliverable submitted: id={} milestoneId={} submitter={}", savedDeliverable.getId(), milestoneId, currentUser.getId());
        return DeliverableResponse.fromEntity(savedDeliverable);
    }

    @Transactional
    public MilestoneResponse approveMilestone(UUID milestoneId, UserPrincipal currentUser) {
        // Canonical lock order: Deal -> Milestone.
        // Step 1: Read milestone ID -> dealId without write lock.
        UUID dealId = milestoneRepository.findDealIdById(milestoneId)
                .or(() -> milestoneRepository.findById(milestoneId).map(Milestone::getDealId))
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        // Step 2: Lock Deal.
        Deal deal = dealRepository.findByIdForUpdate(dealId)
                .or(() -> dealRepository.findById(dealId))
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        // Step 3: Lock Milestone.
        Milestone milestone = milestoneRepository.findByIdForUpdate(milestoneId)
                .or(() -> milestoneRepository.findById(milestoneId))
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));
        if (entityManager != null) {
            try {
                entityManager.refresh(milestone);
            } catch (Exception ignored) {}
        }

        // Step 4: Reverify milestone.dealId == locked Deal.id
        if (!deal.getId().equals(milestone.getDealId())) {
            throw new BadRequestException("Milestone does not belong to deal");
        }

        boolean isBuyer = deal.getBuyerId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getRole() == Role.ADMIN;
        boolean isAssignedPm = currentUser.getRole() == Role.PROJECT_MANAGER &&
                deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(currentUser.getId());

        if (!isAdmin && !isBuyer && !isAssignedPm) {
            throw new UnauthorizedException("Only the corporate buyer, assigned project manager, or an administrator can approve this milestone");
        }

        if (milestone.getStatus() != MilestoneStatus.UNDER_REVIEW && milestone.getStatus() != MilestoneStatus.SUBMITTED) {
            throw new BadRequestException("Only submitted milestones under review can be approved. Current status: " + milestone.getStatus());
        }

        Instant approvalTime = (clock != null) ? clock.now() : Instant.now();

        // Update pending deliverables first
        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId);
        for (Deliverable d : deliverables) {
            if (d.getStatus() == DeliverableStatus.PENDING) {
                d.setStatus(DeliverableStatus.APPROVED);
                d.setReviewedAt(approvalTime);
                d.setReviewedBy(currentUser.getId());
                deliverableRepository.save(d);
            }
        }
        deliverableRepository.flush();

        milestone.setStatus(MilestoneStatus.APPROVED);
        Milestone updated = milestoneRepository.saveAndFlush(milestone);

        // Snapshot current post-approval persisted state
        List<Deliverable> persistedDeliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId);
        updated.setDeliverables(persistedDeliverables);

        // Create immutable approval binding for modern org-bound deals
        if (deal.getBuyerOrganizationId() != null && approvalBindingService != null) {
            approvalBindingService.createBinding(deal, updated, persistedDeliverables, currentUser, approvalTime);
        } else {
            log.warn("Legacy unbound milestone approval for milestone {} on deal {} without buyerOrganizationId is nonauthoritative for release", milestoneId, deal.getId());
            auditService.logEvent(
                    currentUser.getId(),
                    "LEGACY_UNBOUND_MILESTONE_APPROVED",
                    "MILESTONE",
                    updated.getId().toString(),
                    "Legacy unbound milestone approved without organization binding - nonauthoritative for release"
            );
        }

        auditService.logEvent(
                currentUser.getId(),
                "MILESTONE_APPROVED",
                "MILESTONE",
                updated.getId().toString(),
                "Approved milestone: " + updated.getTitle()
        );

        notificationService.sendNotification(
                deal.getSellerId(),
                "Milestone Approved!",
                "Milestone '" + updated.getTitle() + "' was approved. Payment/Release is now eligible.",
                "/vendor/projects/" + deal.getId()
        );

        log.info("Milestone approved: id={} dealId={} reviewer={}", updated.getId(), deal.getId(), currentUser.getId());
        return MilestoneResponse.fromEntity(updated);
    }

    @Transactional
    public MilestoneResponse rejectMilestone(UUID milestoneId, String reason, UserPrincipal currentUser) {
        // Canonical lock order: Deal -> Milestone.
        // Step 1: Read milestone ID -> dealId without write lock.
        UUID dealId = milestoneRepository.findDealIdById(milestoneId)
                .or(() -> milestoneRepository.findById(milestoneId).map(Milestone::getDealId))
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        // Step 2: Lock Deal.
        Deal deal = dealRepository.findByIdForUpdate(dealId)
                .or(() -> dealRepository.findById(dealId))
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        // Step 3: Lock Milestone.
        Milestone milestone = milestoneRepository.findByIdForUpdate(milestoneId)
                .or(() -> milestoneRepository.findById(milestoneId))
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));
        if (entityManager != null) {
            try {
                entityManager.refresh(milestone);
            } catch (Exception ignored) {}
        }

        // Step 4: Reverify milestone.dealId == locked Deal.id
        if (!deal.getId().equals(milestone.getDealId())) {
            throw new BadRequestException("Milestone does not belong to deal");
        }

        boolean isBuyer = deal.getBuyerId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getRole() == Role.ADMIN;
        boolean isAssignedPm = currentUser.getRole() == Role.PROJECT_MANAGER &&
                deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(currentUser.getId());

        if (!isAdmin && !isBuyer && !isAssignedPm) {
            throw new UnauthorizedException("Only the corporate buyer, assigned project manager, or an administrator can reject this milestone");
        }

        if (milestone.getStatus() != MilestoneStatus.UNDER_REVIEW && milestone.getStatus() != MilestoneStatus.SUBMITTED) {
            throw new BadRequestException("Only submitted milestones under review can be rejected. Current status: " + milestone.getStatus());
        }

        String rejectionReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Revision requested by buyer";

        milestone.setStatus(MilestoneStatus.REJECTED);
        Milestone updated = milestoneRepository.saveAndFlush(milestone);

        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId);
        for (Deliverable d : deliverables) {
            if (d.getStatus() == DeliverableStatus.PENDING) {
                d.setStatus(DeliverableStatus.REJECTED);
                d.setReviewedAt(Instant.now());
                d.setReviewedBy(currentUser.getId());
                d.setRejectionReason(rejectionReason);
                deliverableRepository.save(d);
            }
        }
        updated.setDeliverables(deliverables);

        auditService.logEvent(
                currentUser.getId(),
                "MILESTONE_REJECTED",
                "MILESTONE",
                updated.getId().toString(),
                "Rejected milestone '" + updated.getTitle() + "'. Reason: " + rejectionReason
        );

        notificationService.sendNotification(
                deal.getSellerId(),
                "Milestone Changes Requested",
                "Changes requested on milestone '" + updated.getTitle() + "': " + rejectionReason,
                "/vendor/projects/" + deal.getId()
        );

        log.info("Milestone rejected: id={} dealId={} reason={}", updated.getId(), deal.getId(), rejectionReason);
        return MilestoneResponse.fromEntity(updated);
    }

    @Transactional
    public MilestoneResponse updateMilestoneDeadline(UUID milestoneId, com.finx.milestone.dto.request.UpdateDeadlineRequest request, UserPrincipal currentUser) {
        Milestone updated = milestoneReminderService.updateMilestoneDeadline(
                milestoneId, request.getSubmissionDeadline(), currentUser, request.getReason());
        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId);
        updated.setDeliverables(deliverables);
        return MilestoneResponse.fromEntity(updated);
    }

    @Transactional(readOnly = true)
    public List<MilestoneResponse> getUpcomingMilestones(UserPrincipal currentUser) {
        List<Deal> userDeals = getUserDeals(currentUser);
        if (userDeals.isEmpty()) return Collections.emptyList();
        List<UUID> dealIds = userDeals.stream().map(Deal::getId).collect(Collectors.toList());
        List<Milestone> milestones = milestoneRepository.findUpcomingByDealIds(dealIds, clock.now());
        return milestones.stream()
                .map(m -> {
                    List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(m.getId());
                    m.setDeliverables(deliverables);
                    return MilestoneResponse.fromEntity(m);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MilestoneResponse> getOverdueMilestones(UserPrincipal currentUser) {
        List<Deal> userDeals = getUserDeals(currentUser);
        if (userDeals.isEmpty()) return Collections.emptyList();
        List<UUID> dealIds = userDeals.stream().map(Deal::getId).collect(Collectors.toList());
        List<Milestone> milestones = milestoneRepository.findOverdueByDealIds(dealIds, clock.now());
        return milestones.stream()
                .map(m -> {
                    List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(m.getId());
                    m.setDeliverables(deliverables);
                    return MilestoneResponse.fromEntity(m);
                })
                .collect(Collectors.toList());
    }

    private List<Deal> getUserDeals(UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.FINANCE) {
            return dealRepository.findAll();
        } else if (currentUser.getRole() == Role.BUYER) {
            return dealRepository.findByBuyerId(currentUser.getId());
        } else if (currentUser.getRole() == Role.PROJECT_MANAGER) {
            return dealRepository.findByProjectManagerId(currentUser.getId());
        } else {
            return dealRepository.findBySellerId(currentUser.getId());
        }
    }

    private void validatePartyAccess(Deal deal, UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.FINANCE) {
            return;
        }
        boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
        boolean isAssignedPm = currentUser.getRole() == Role.PROJECT_MANAGER &&
                deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(currentUser.getId());
        if (!isParty && !isAssignedPm) {
            throw new UnauthorizedException("You do not have permission to view milestones for this deal");
        }
    }
}
