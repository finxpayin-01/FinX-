package com.finx.dispute.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.dto.request.CreateDisputeRequest;
import com.finx.dispute.dto.response.DisputeResponse;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.notification.service.NotificationService;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final DisputeRepository disputeRepository;
    private final DealRepository dealRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public DisputeService(DisputeRepository disputeRepository,
                          DealRepository dealRepository,
                          AuditService auditService,
                          NotificationService notificationService) {
        this.disputeRepository = disputeRepository;
        this.dealRepository = dealRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public DisputeResponse createDispute(CreateDisputeRequest request, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(request.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", request.getDealId()));

        if (currentUser.getRole() != Role.ADMIN) {
            boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
            if (!isParty) {
                throw new BadRequestException("Only participating parties may raise a dispute on this deal");
            }
        }

        Dispute dispute = new Dispute(deal.getId(), request.getMilestoneId(), currentUser.getId(), request.getReason().trim());
        Dispute saved = disputeRepository.saveAndFlush(dispute);

        deal.setStatus(DealStatus.DISPUTED);
        dealRepository.save(deal);

        auditService.logEvent(
                currentUser.getId(),
                "DISPUTE_CREATED",
                "DISPUTE",
                saved.getId().toString(),
                "Dispute raised on deal: " + deal.getTitle() + " | Reason: " + request.getReason()
        );

        UUID notifyRecipient = deal.getBuyerId().equals(currentUser.getId()) ? deal.getSellerId() : deal.getBuyerId();
        notificationService.sendNotification(
                notifyRecipient,
                "Dispute Raised on Deal",
                "A dispute was raised on deal '" + deal.getTitle() + "' and is under review.",
                "/corporate/disputes"
        );

        log.info("Dispute created: id={} deal={} raisedBy={}", saved.getId(), deal.getId(), currentUser.getId());
        return DisputeResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> getDisputesForDeal(UUID dealId) {
        return disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId).stream()
                .map(DisputeResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> getAllDisputes() {
        return disputeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(DisputeResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public DisputeResponse resolveDispute(UUID disputeId, String resolutionNotes, UserPrincipal currentUser) {
        if (currentUser.getRole() != Role.ADMIN) {
            throw new BadRequestException("Only an administrator can resolve disputes");
        }

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute", "id", disputeId));

        dispute.setStatus(DisputeStatus.RESOLVED);
        dispute.setResolutionNotes(resolutionNotes != null ? resolutionNotes.trim() : "Resolved by Admin");
        dispute.setResolvedAt(Instant.now());
        dispute.setResolvedBy(currentUser.getId());

        Dispute updated = disputeRepository.saveAndFlush(dispute);

        // If no remaining open disputes on this deal, restore deal status to ACTIVE
        boolean hasRemainingDisputes = disputeRepository.existsByDealIdAndStatus(dispute.getDealId(), DisputeStatus.OPEN);
        if (!hasRemainingDisputes) {
            Deal deal = dealRepository.findById(dispute.getDealId()).orElse(null);
            if (deal != null && deal.getStatus() == DealStatus.DISPUTED) {
                deal.setStatus(DealStatus.ACTIVE);
                dealRepository.saveAndFlush(deal);
                log.info("All open disputes resolved on deal {}. Deal status restored to ACTIVE", deal.getId());
            }
        }

        auditService.logEvent(
                currentUser.getId(),
                "DISPUTE_RESOLVED",
                "DISPUTE",
                updated.getId().toString(),
                "Dispute resolved: " + resolutionNotes
        );

        return DisputeResponse.fromEntity(updated);
    }
}
