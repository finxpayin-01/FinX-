package com.finx.milestone.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
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

    public MilestoneService(MilestoneRepository milestoneRepository,
                            DeliverableRepository deliverableRepository,
                            DealRepository dealRepository,
                            AuditService auditService,
                            NotificationService notificationService) {
        this.milestoneRepository = milestoneRepository;
        this.deliverableRepository = deliverableRepository;
        this.dealRepository = dealRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public MilestoneResponse createMilestone(UUID dealId, CreateMilestoneRequest request, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the buyer or an admin can create milestones for this deal");
        }

        if (deal.getStatus() == DealStatus.COMPLETED || deal.getStatus() == DealStatus.CANCELLED) {
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

        Milestone milestone = new Milestone(
                deal.getId(),
                request.getTitle().trim(),
                request.getDescription() != null ? request.getDescription().trim() : null,
                sequence,
                request.getAmount(),
                currency,
                request.getDueDate()
        );

        Milestone saved = milestoneRepository.saveAndFlush(milestone);

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

        auditService.logEvent(
                currentUser.getId(),
                "DELIVERABLE_SUBMITTED",
                "DELIVERABLE",
                savedDeliverable.getId().toString(),
                "Submitted deliverable: " + savedDeliverable.getFileName() + " for milestone: " + milestone.getTitle()
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
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (currentUser.getRole() != Role.ADMIN &&
                currentUser.getRole() != Role.PROJECT_MANAGER &&
                !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer, project manager, or an administrator can approve this milestone");
        }

        if (milestone.getStatus() != MilestoneStatus.UNDER_REVIEW && milestone.getStatus() != MilestoneStatus.SUBMITTED) {
            throw new BadRequestException("Only submitted milestones under review can be approved. Current status: " + milestone.getStatus());
        }

        milestone.setStatus(MilestoneStatus.APPROVED);
        Milestone updated = milestoneRepository.saveAndFlush(milestone);

        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId);
        for (Deliverable d : deliverables) {
            if (d.getStatus() == DeliverableStatus.PENDING) {
                d.setStatus(DeliverableStatus.APPROVED);
                d.setReviewedAt(Instant.now());
                d.setReviewedBy(currentUser.getId());
                deliverableRepository.save(d);
            }
        }
        updated.setDeliverables(deliverables);

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
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (currentUser.getRole() != Role.ADMIN &&
                currentUser.getRole() != Role.PROJECT_MANAGER &&
                !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer, project manager, or an administrator can reject this milestone");
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

    private void validatePartyAccess(Deal deal, UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ADMIN ||
                currentUser.getRole() == Role.PROJECT_MANAGER ||
                currentUser.getRole() == Role.FINANCE) {
            return;
        }
        boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
        if (!isParty) {
            throw new UnauthorizedException("You do not have permission to view milestones for this deal");
        }
    }
}
