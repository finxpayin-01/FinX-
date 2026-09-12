package com.finx.escrow.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.dto.response.EscrowAccountResponse;
import com.finx.escrow.dto.response.EscrowLedgerResponse;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EscrowService {

    private static final Logger log = LoggerFactory.getLogger(EscrowService.class);

    private final EscrowAccountRepository escrowAccountRepository;
    private final EscrowLedgerRepository escrowLedgerRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DisputeRepository disputeRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public EscrowService(EscrowAccountRepository escrowAccountRepository,
                         EscrowLedgerRepository escrowLedgerRepository,
                         DealRepository dealRepository,
                         MilestoneRepository milestoneRepository,
                         DisputeRepository disputeRepository,
                         AuditService auditService,
                         NotificationService notificationService) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.escrowLedgerRepository = escrowLedgerRepository;
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.disputeRepository = disputeRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public EscrowAccount getOrCreateEscrowAccount(UUID dealId, String currency) {
        return escrowAccountRepository.findByDealIdForUpdate(dealId).orElseGet(() -> {
            EscrowAccount account = new EscrowAccount(dealId, BigDecimal.ZERO, currency);
            return escrowAccountRepository.saveAndFlush(account);
        });
    }

    @Transactional
    public EscrowAccountResponse getEscrowAccountForDeal(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        if (currentUser.getRole() != Role.ADMIN) {
            boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
            if (!isParty) {
                throw new UnauthorizedException("You are not authorized to view this deal's escrow account");
            }
        }

        EscrowAccount account = getOrCreateEscrowAccount(dealId, deal.getCurrency());
        return EscrowAccountResponse.fromEntity(account);
    }

    @Transactional(readOnly = true)
    public List<EscrowLedgerResponse> getEscrowLedgerForDeal(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        if (currentUser.getRole() != Role.ADMIN) {
            boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
            if (!isParty) {
                throw new UnauthorizedException("You are not authorized to view this deal's escrow ledger");
            }
        }

        Optional<EscrowAccount> accountOpt = escrowAccountRepository.findByDealId(dealId);
        if (accountOpt.isEmpty()) {
            return List.of();
        }

        return escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(accountOpt.get().getId()).stream()
                .map(EscrowLedgerResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void fundEscrow(UUID dealId, UUID milestoneId, UUID paymentId, BigDecimal amount, String currency, UUID actorId) {
        Deal deal = dealRepository.findByIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        EscrowAccount account = getOrCreateEscrowAccount(dealId, currency);

        BigDecimal newBalance = account.getBalance().add(amount);
        account.setBalance(newBalance);
        escrowAccountRepository.saveAndFlush(account);

        EscrowLedger ledger = new EscrowLedger(
                account.getId(),
                paymentId,
                milestoneId,
                TransactionType.FUND,
                amount,
                newBalance,
                "Fiat escrow funded for milestone " + milestoneId
        );
        escrowLedgerRepository.saveAndFlush(ledger);

        auditService.logEvent(
                actorId,
                "ESCROW_FUNDED",
                "ESCROW",
                account.getId().toString(),
                "Funded " + amount + " " + currency + " into escrow account for deal " + dealId
        );

        notificationService.sendNotification(
                deal.getSellerId(),
                "Escrow Funded",
                "Buyer deposited " + amount + " " + currency + " into escrow for milestone progress.",
                "/vendor/projects/" + dealId
        );

        log.info("Escrow funded: dealId={} amount={} newBalance={}", dealId, amount, newBalance);
    }

    @Transactional
    public EscrowLedgerResponse releaseEscrow(UUID milestoneId, String comment, UserPrincipal currentUser) {
        Milestone milestone = milestoneRepository.findByIdForUpdate(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findByIdForUpdate(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer or an administrator can release escrow funds");
        }

        if (deal.getStatus() == DealStatus.DISPUTED ||
                disputeRepository.existsByDealIdAndStatus(deal.getId(), DisputeStatus.OPEN) ||
                disputeRepository.existsByMilestoneIdAndStatus(milestoneId, DisputeStatus.OPEN)) {
            throw new BadRequestException("Cannot release escrow funds while the deal or milestone is in DISPUTED status. The dispute must be resolved by an administrator first.");
        }

        if (milestone.getStatus() != MilestoneStatus.APPROVED) {
            throw new BadRequestException("Milestone must be APPROVED by the buyer before releasing funds. Current status: " + milestone.getStatus());
        }

        // Double-release prevention check
        Optional<EscrowLedger> existingRelease = escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE);
        if (existingRelease.isPresent()) {
            throw new BadRequestException("Escrow funds for milestone '" + milestone.getTitle() + "' have already been released.");
        }

        EscrowAccount account = escrowAccountRepository.findByDealIdForUpdate(deal.getId())
                .orElseThrow(() -> new BadRequestException("No escrow account found for this deal"));

        if (account.getBalance().compareTo(milestone.getAmount()) < 0) {
            throw new BadRequestException("Insufficient escrow balance. Available: " + account.getBalance() + " " + account.getCurrency() + ", Required: " + milestone.getAmount() + " " + account.getCurrency());
        }

        BigDecimal newBalance = account.getBalance().subtract(milestone.getAmount());
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Escrow balance cannot fall below zero");
        }

        account.setBalance(newBalance);
        escrowAccountRepository.saveAndFlush(account);

        String releaseDescription = (comment != null && !comment.isBlank())
                ? comment.trim()
                : "Escrow funds released to vendor for milestone: " + milestone.getTitle();

        EscrowLedger ledger = new EscrowLedger(
                account.getId(),
                null,
                milestone.getId(),
                TransactionType.RELEASE,
                milestone.getAmount(),
                newBalance,
                releaseDescription
        );
        EscrowLedger savedLedger = escrowLedgerRepository.saveAndFlush(ledger);

        milestone.setStatus(MilestoneStatus.COMPLETED);
        milestoneRepository.saveAndFlush(milestone);

        // Check if all milestones for this deal are now completed
        List<Milestone> allDealMilestones = milestoneRepository.findByDealIdOrderBySequenceAsc(deal.getId());
        boolean allCompleted = !allDealMilestones.isEmpty() &&
                allDealMilestones.stream().allMatch(m -> m.getStatus() == MilestoneStatus.COMPLETED);

        if (allCompleted) {
            deal.setStatus(DealStatus.COMPLETED);
            dealRepository.saveAndFlush(deal);
            auditService.logEvent(
                    currentUser.getId(),
                    "DEAL_COMPLETED",
                    "DEAL",
                    deal.getId().toString(),
                    "All milestones completed and escrow released for deal: " + deal.getTitle()
            );
        }

        auditService.logEvent(
                currentUser.getId(),
                "ESCROW_RELEASED",
                "ESCROW",
                account.getId().toString(),
                "Released " + milestone.getAmount() + " " + account.getCurrency() + " to vendor for milestone: " + milestone.getTitle()
        );

        notificationService.sendNotification(
                deal.getSellerId(),
                "Funds Released!",
                "Buyer released " + milestone.getAmount() + " " + account.getCurrency() + " from escrow for milestone '" + milestone.getTitle() + "'.",
                "/vendor/projects/" + deal.getId()
        );

        log.info("Escrow released: milestoneId={} amount={} remainingBalance={}", milestoneId, milestone.getAmount(), newBalance);
        return EscrowLedgerResponse.fromEntity(savedLedger);
    }

    @Transactional(readOnly = true)
    public List<EscrowAccountResponse> getAllEscrowAccounts(UserPrincipal currentUser) {
        if (currentUser.getRole() != Role.ADMIN) {
            throw new UnauthorizedException("Only administrators can view all escrow accounts");
        }
        return escrowAccountRepository.findAll().stream()
                .map(EscrowAccountResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
