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
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;
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

    private void validateSupportedCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new BadRequestException("Currency is required");
        }
        if (!"INR".equalsIgnoreCase(currency.trim())) {
            throw new BadRequestException("Unsupported currency: '" + currency + "'. FinX currently supports INR only.");
        }
    }

    @Transactional
    public EscrowAccount ensureEscrowAccount(UUID dealId, String currency) {
        if (dealId == null) {
            throw new BadRequestException("Deal ID is required to ensure escrow account");
        }
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "INR";
        validateSupportedCurrency(curr);

        // Fast check before lock
        Optional<EscrowAccount> existing = escrowAccountRepository.findByDealId(dealId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // B11: Serialize escrow account creation using parent Deal pessimistic lock.
        // This eliminates duplicate insertion races without continuing DB work inside an aborted transaction.
        if (dealRepository != null) {
            dealRepository.findByIdForUpdate(dealId);
            existing = escrowAccountRepository.findByDealId(dealId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        EscrowAccount newAccount = new EscrowAccount(dealId, BigDecimal.ZERO, curr);
        return escrowAccountRepository.saveAndFlush(newAccount);
    }

    @Transactional
    public EscrowAccount getOrCreateEscrowAccount(UUID dealId, String currency) {
        return ensureEscrowAccount(dealId, currency);
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
        if (paymentId == null) {
            throw new BadRequestException("Legacy payment ID is required for legacy escrow funding");
        }
        validateSupportedCurrency(currency);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Funding amount must be greater than zero");
        }

        // B11: Canonical lock order: Deal -> Milestone -> EscrowAccount
        Deal deal = dealRepository.findByIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        validateSupportedCurrency(deal.getCurrency());
        if (!deal.getCurrency().equalsIgnoreCase(currency)) {
            throw new BadRequestException("Currency mismatch: Provided currency '" + currency + "' does not match deal currency '" + deal.getCurrency() + "'");
        }

        if (milestoneId != null) {
            Milestone milestone = milestoneRepository.findByIdForUpdate(milestoneId)
                    .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

            // B06: Explicit one-full-funding invariant
            Optional<EscrowLedger> existingFund = escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.FUND);
            if (existingFund.isPresent()) {
                throw new BadRequestException("Milestone " + milestoneId + " has already been funded. Partial or duplicate funding is not supported.");
            }
            if (milestone.getStatus() == MilestoneStatus.COMPLETED) {
                throw new BadRequestException("Milestone " + milestoneId + " has already been completed.");
            }
        }

        Optional<EscrowLedger> existingPayment = escrowLedgerRepository.findByPaymentId(paymentId);
        if (existingPayment.isPresent()) {
            throw new BadRequestException("Escrow has already been funded for payment " + paymentId);
        }

        EscrowAccount account = getOrCreateEscrowAccount(dealId, currency);
        if (!account.getCurrency().equalsIgnoreCase(currency)) {
            throw new BadRequestException("Currency mismatch: Provided currency '" + currency + "' does not match escrow currency '" + account.getCurrency() + "'");
        }

        BigDecimal newBalance = account.getBalance().add(amount);
        account.setBalance(newBalance);
        account.setTotalFunded(account.getTotalFunded().add(amount));
        escrowAccountRepository.saveAndFlush(account);

        EscrowLedger ledger = new EscrowLedger(
                account.getId(),
                paymentId,
                null,
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

        log.info("Escrow funded (legacy): dealId={} paymentId={} amount={} newBalance={}", dealId, paymentId, amount, newBalance);
    }

    /**
     * B06: Bank-specific escrow funding projection using explicit PaymentInstruction FK.
     * Enforces source exclusivity, currency consistency, and one-full-funding policy.
     */
    @Transactional
    public void fundEscrowForInstruction(UUID dealId, UUID milestoneId, UUID paymentInstructionId, BigDecimal amount, String currency, UUID actorId) {
        if (paymentInstructionId == null) {
            throw new BadRequestException("Bank payment instruction ID is required for bank escrow funding");
        }
        validateSupportedCurrency(currency);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Funding amount must be greater than zero");
        }

        // B11: Canonical lock order: Deal -> Milestone -> EscrowAccount
        Deal deal = dealRepository.findByIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        validateSupportedCurrency(deal.getCurrency());
        if (!deal.getCurrency().equalsIgnoreCase(currency)) {
            throw new BadRequestException("Currency mismatch: Provided currency '" + currency + "' does not match deal currency '" + deal.getCurrency() + "'");
        }

        if (milestoneId != null) {
            Milestone milestone = milestoneRepository.findByIdForUpdate(milestoneId)
                    .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

            // B06: Explicit one-full-funding invariant
            Optional<EscrowLedger> existingFund = escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.FUND);
            if (existingFund.isPresent()) {
                throw new BadRequestException("Milestone " + milestoneId + " has already been funded. Partial or duplicate funding is not supported.");
            }
            if (milestone.getStatus() == MilestoneStatus.COMPLETED) {
                throw new BadRequestException("Milestone " + milestoneId + " has already been completed.");
            }
        }

        Optional<EscrowLedger> existingInstruction = escrowLedgerRepository.findByPaymentInstructionId(paymentInstructionId);
        if (existingInstruction.isPresent()) {
            throw new BadRequestException("Escrow has already been funded for payment instruction " + paymentInstructionId);
        }

        EscrowAccount account = getOrCreateEscrowAccount(dealId, currency);
        if (!account.getCurrency().equalsIgnoreCase(currency)) {
            throw new BadRequestException("Currency mismatch: Provided currency '" + currency + "' does not match escrow currency '" + account.getCurrency() + "'");
        }

        BigDecimal newBalance = account.getBalance().add(amount);
        account.setBalance(newBalance);
        account.setTotalFunded(account.getTotalFunded().add(amount));
        escrowAccountRepository.saveAndFlush(account);

        EscrowLedger ledger = new EscrowLedger(
                account.getId(),
                null,
                paymentInstructionId,
                milestoneId,
                TransactionType.FUND,
                amount,
                newBalance,
                "Bank fiat escrow funded for milestone " + milestoneId
        );
        escrowLedgerRepository.saveAndFlush(ledger);

        auditService.logEvent(
                actorId,
                "ESCROW_FUNDED_BANK",
                "ESCROW",
                account.getId().toString(),
                "Funded " + amount + " " + currency + " into escrow account via bank instruction " + paymentInstructionId + " for deal " + dealId
        );

        notificationService.sendNotification(
                deal.getSellerId(),
                "Escrow Funded",
                "Buyer deposited " + amount + " " + currency + " into escrow via bank transfer.",
                "/vendor/projects/" + dealId
        );

        log.info("Escrow funded via bank instruction: dealId={} instructionId={} amount={} newBalance={}",
                dealId, paymentInstructionId, amount, newBalance);
    }

    @Transactional
    public EscrowLedgerResponse releaseEscrow(UUID milestoneId, String comment, UserPrincipal currentUser) {
        throw new AccessDeniedException("Direct escrow release is disabled pending settlement orchestration");
    }

    /**
     * Internal-only settlement finalization boundary.
     * Requires a verified, persisted SettlementInstruction in SETTLED status with a valid bank UTR.
     * Not reachable via direct user API calls.
     */
    @Transactional
    public EscrowLedgerResponse releaseEscrowForSettlement(
            SettlementInstruction settlement,
            String comment,
            UserPrincipal currentUser) {
        if (settlement == null || settlement.getId() == null) {
            throw new AccessDeniedException("Settlement finalization requires a valid persisted SettlementInstruction");
        }
        if (settlement.getStatus() != SettlementStatus.SETTLED) {
            throw new AccessDeniedException("Settlement finalization requires a SETTLED status instruction");
        }
        if (settlement.getUtrNumber() == null || settlement.getUtrNumber().isBlank()) {
            throw new AccessDeniedException("Settlement finalization requires a valid bank UTR number");
        }

        UUID milestoneId = settlement.getMilestoneId();
        UUID dealId = settlement.getDealId();

        // B11: Canonical lock order: Deal -> Milestone -> EscrowAccount.
        // Lock parent Deal first using settlement dealId, then lock Milestone.
        Deal deal = dealRepository.findByIdForUpdate(dealId)
                .or(() -> dealRepository.findById(dealId))
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        Milestone milestone = milestoneRepository.findByIdForUpdate(milestoneId)
                .or(() -> milestoneRepository.findById(milestoneId))
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

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
                .orElseGet(() -> ensureEscrowAccount(deal.getId(), deal.getCurrency()));

        if (account.getBalance().compareTo(milestone.getAmount()) < 0) {
            throw new BadRequestException("Insufficient escrow balance. Available: " + account.getBalance() + " " + account.getCurrency() + ", Required: " + milestone.getAmount() + " " + account.getCurrency() + ". Please fund the milestone before releasing funds.");
        }

        BigDecimal newBalance = account.getBalance().subtract(milestone.getAmount());
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Escrow balance cannot fall below zero");
        }

        account.setBalance(newBalance);
        account.setTotalReleased(account.getTotalReleased().add(milestone.getAmount()));
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
                    currentUser != null ? currentUser.getId() : null,
                    "DEAL_COMPLETED",
                    "DEAL",
                    deal.getId().toString(),
                    "All milestones completed and escrow released for deal: " + deal.getTitle()
            );
        }

        auditService.logEvent(
                currentUser != null ? currentUser.getId() : null,
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

        log.info("Escrow released for settlement: milestoneId={} amount={} remainingBalance={} utr={}",
                milestoneId, milestone.getAmount(), newBalance, settlement.getUtrNumber());
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
