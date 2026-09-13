package com.finx.deal.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.dto.request.CreateDealRequest;
import com.finx.deal.dto.response.DealResponse;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.finx.notification.service.NotificationService;
import com.finx.deal.dto.request.RejectDealRequest;
import org.springframework.transaction.annotation.Transactional;
import com.finx.escrow.service.EscrowService;
import com.finx.deal.event.DealCreatedEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DealService {

    private static final Logger log = LoggerFactory.getLogger(DealService.class);

    private final DealRepository dealRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final EscrowService escrowService;

    public DealService(DealRepository dealRepository,
                       UserRepository userRepository,
                       AuditService auditService,
                       NotificationService notificationService,
                       ApplicationEventPublisher eventPublisher,
                       EscrowService escrowService) {
        this.dealRepository = dealRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.escrowService = escrowService;
    }

    @Transactional
    public DealResponse createDeal(CreateDealRequest request, UserPrincipal currentUser) {
        UUID buyerId;
        if (currentUser != null) {
            if (currentUser.getRole() == Role.BUYER) {
                buyerId = currentUser.getId();
            } else if (currentUser.getRole() == Role.ADMIN && request.getBuyerId() != null) {
                buyerId = request.getBuyerId();
            } else {
                buyerId = currentUser.getId();
            }
        } else if (request.getBuyerId() != null) {
            buyerId = request.getBuyerId();
        } else {
            throw new BadRequestException("Buyer ID is required to create a deal");
        }

        String sellerInput = request.getSellerId() != null ? request.getSellerId().trim() : "";
        if (sellerInput.isEmpty()) {
            throw new BadRequestException("Seller ID or UID is required");
        }

        if (sellerInput.equalsIgnoreCase(buyerId.toString())) {
            throw new BadRequestException("Buyer and Seller cannot be the same account");
        }

        User seller = null;

        // 1. Try resolving as UUID
        try {
            UUID sellerUuid = UUID.fromString(sellerInput);
            seller = userRepository.findById(sellerUuid).orElse(null);
        } catch (IllegalArgumentException ignored) {
            // Not a UUID, fallback to UID
        }

        // 2. Try resolving as UID
        if (seller == null) {
            seller = userRepository.findByUidIgnoreCase(sellerInput).orElse(null);
        }

        // 3. Try resolving with "USR-" prefix if missing
        if (seller == null && !sellerInput.toUpperCase().startsWith("USR-")) {
            seller = userRepository.findByUidIgnoreCase("USR-" + sellerInput.toUpperCase()).orElse(null);
        }

        // 4. Try resolving via vendorEmail if provided
        if (seller == null && request.getVendorEmail() != null && !request.getVendorEmail().isBlank()) {
            seller = userRepository.findByEmail(request.getVendorEmail().trim()).orElse(null);
        }

        if (seller == null) {
            throw new BadRequestException("Vendor UID not found: " + sellerInput);
        }

        if (buyerId.equals(seller.getId())) {
            throw new BadRequestException("Buyer and Seller cannot be the same account");
        }

        User buyer = userRepository.findById(buyerId)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer", "id", buyerId));

        if (seller.getRole() != Role.SELLER) {
            throw new BadRequestException("Specified seller must have the SELLER role");
        }

        Deal deal = new Deal(
                request.getTitle().trim(),
                request.getDescription() != null ? request.getDescription().trim() : null,
                buyer.getId(),
                seller.getId(),
                request.getTotalAmount(),
                request.getCurrency(),
                DealStatus.PENDING_VENDOR_ACCEPTANCE
        );

        Deal savedDeal = dealRepository.saveAndFlush(deal);
        escrowService.ensureEscrowAccount(savedDeal.getId(), savedDeal.getCurrency());

        auditService.logEvent(
                buyerId,
                "DEAL_CREATED",
                "DEAL",
                savedDeal.getId().toString(),
                "Created deal: " + savedDeal.getTitle() + " (" + savedDeal.getTotalAmount() + " " + savedDeal.getCurrency() + ")"
        );

        notificationService.sendNotification(
                seller.getId(),
                "New Project Invitation",
                "New project invitation from " + (buyer.getName() != null ? buyer.getName() : "Corporate Buyer") + " for '" + savedDeal.getTitle() + "'",
                "/vendor/projects/" + savedDeal.getId()
        );

        log.info("Deal created successfully: id={} buyer={} seller={} amount={} status={}",
                savedDeal.getId(), buyerId, seller.getId(), savedDeal.getTotalAmount(), savedDeal.getStatus());

        if (request.getVendorEmail() != null && !request.getVendorEmail().trim().isEmpty()) {
            eventPublisher.publishEvent(new DealCreatedEvent(savedDeal, request.getVendorEmail().trim()));
            log.info("Published DealCreatedEvent for deal {}", savedDeal.getId());
        }

        return DealResponse.fromEntity(savedDeal);
    }

    @Transactional(readOnly = true)
    public DealResponse getDealById(UUID id, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", id));

        if (currentUser != null && currentUser.getRole() != Role.ADMIN &&
                currentUser.getRole() != Role.PROJECT_MANAGER &&
                currentUser.getRole() != Role.FINANCE) {
            boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
            if (!isParty) {
                throw new UnauthorizedException("You are not authorized to view this deal");
            }
        }

        return DealResponse.fromEntity(deal);
    }

    @Transactional(readOnly = true)
    public DealResponse getDealById(UUID id) {
        return getDealById(id, null);
    }

    @Transactional(readOnly = true)
    public List<DealResponse> getAllDeals() {
        return dealRepository.findAll().stream()
                .map(DealResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DealResponse> getDealsForBuyer(UUID buyerId) {
        return dealRepository.findByBuyerId(buyerId).stream()
                .map(DealResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DealResponse> getDealsForSeller(UUID sellerId) {
        return dealRepository.findBySellerId(sellerId).stream()
                .map(DealResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<com.finx.auth.dto.response.UserSummaryResponse> getAvailableSellers() {
        return userRepository.findByRole(Role.SELLER).stream()
                .map(com.finx.auth.dto.response.UserSummaryResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public DealResponse acceptDeal(UUID id, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", id));

        if (currentUser == null) {
            throw new UnauthorizedException("Authentication required to accept this deal");
        }

        if (deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Buyer cannot accept their own project invitation");
        }

        if (currentUser.getRole() != Role.ADMIN && !deal.getSellerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the assigned vendor can accept this deal");
        }

        if (deal.getStatus() == DealStatus.ACCEPTED) {
            throw new BadRequestException("Deal is already accepted and active");
        }

        if (deal.getStatus() != DealStatus.DRAFT && deal.getStatus() != DealStatus.PENDING_VENDOR_ACCEPTANCE) {
            throw new BadRequestException("Deal cannot be accepted from status: " + deal.getStatus());
        }

        deal.setStatus(DealStatus.ACCEPTED);
        deal.setAcceptedAt(Instant.now());
        deal.setAcceptedBy(currentUser.getId());
        Deal updated = dealRepository.saveAndFlush(deal);
        escrowService.ensureEscrowAccount(updated.getId(), updated.getCurrency());

        UUID actorId = currentUser.getId();
        auditService.logEvent(
                actorId,
                "PROJECT_ACCEPTED",
                "DEAL",
                updated.getId().toString(),
                "Project accepted and activated by vendor: " + updated.getTitle()
        );

        User seller = userRepository.findById(deal.getSellerId()).orElse(null);
        String sellerName = (seller != null && seller.getName() != null) ? seller.getName() : "Vendor";
        notificationService.sendNotification(
                deal.getBuyerId(),
                "Project Accepted",
                sellerName + " accepted " + updated.getTitle(),
                "/corporate/projects/" + updated.getId()
        );

        log.info("Deal accepted and activated: id={} actor={}", updated.getId(), actorId);
        return DealResponse.fromEntity(updated);
    }

    @Transactional
    public DealResponse rejectDeal(UUID id, RejectDealRequest request, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", id));

        if (currentUser == null) {
            throw new UnauthorizedException("Authentication required to reject this deal");
        }

        if (deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Buyer cannot reject their own project invitation");
        }

        if (currentUser.getRole() != Role.ADMIN && !deal.getSellerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the assigned vendor can reject this deal");
        }

        if (deal.getStatus() == DealStatus.REJECTED) {
            throw new BadRequestException("Deal is already rejected");
        }

        if (deal.getStatus() != DealStatus.DRAFT && deal.getStatus() != DealStatus.PENDING_VENDOR_ACCEPTANCE) {
            throw new BadRequestException("Deal cannot be rejected from status: " + deal.getStatus());
        }

        String reason = (request != null && request.getReason() != null) ? request.getReason().trim() : null;

        deal.setStatus(DealStatus.REJECTED);
        deal.setRejectedAt(Instant.now());
        deal.setRejectedBy(currentUser.getId());
        deal.setRejectionReason(reason);
        Deal updated = dealRepository.saveAndFlush(deal);

        UUID actorId = currentUser.getId();
        auditService.logEvent(
                actorId,
                "PROJECT_REJECTED",
                "DEAL",
                updated.getId().toString(),
                "Project rejected by vendor: " + updated.getTitle() + (reason != null && !reason.isBlank() ? " (Reason: " + reason + ")" : "")
        );

        User seller = userRepository.findById(deal.getSellerId()).orElse(null);
        String sellerName = (seller != null && seller.getName() != null) ? seller.getName() : "Vendor";
        notificationService.sendNotification(
                deal.getBuyerId(),
                "Project Declined",
                sellerName + " rejected " + updated.getTitle() + (reason != null && !reason.isBlank() ? ": " + reason : ""),
                "/corporate/projects/" + updated.getId()
        );

        log.info("Deal rejected: id={} actor={} reason={}", updated.getId(), actorId, reason);
        return DealResponse.fromEntity(updated);
    }

    @Transactional
    public DealResponse cancelDeal(UUID id, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", id));

        if (currentUser != null && currentUser.getRole() != Role.ADMIN) {
            boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
            if (!isParty) {
                throw new BadRequestException("Only parties associated with this deal can cancel it");
            }
        }

        if (deal.getStatus() == DealStatus.COMPLETED || deal.getStatus() == DealStatus.CANCELLED) {
            throw new BadRequestException("Deal cannot be cancelled from status: " + deal.getStatus());
        }

        deal.setStatus(DealStatus.CANCELLED);
        Deal updated = dealRepository.saveAndFlush(deal);

        UUID actorId = currentUser != null ? currentUser.getId() : deal.getBuyerId();
        auditService.logEvent(
                actorId,
                "DEAL_CANCELLED",
                "DEAL",
                updated.getId().toString(),
                "Deal cancelled: " + updated.getTitle()
        );

        log.info("Deal cancelled: id={} actor={}", updated.getId(), actorId);
        return DealResponse.fromEntity(updated);
    }
}
