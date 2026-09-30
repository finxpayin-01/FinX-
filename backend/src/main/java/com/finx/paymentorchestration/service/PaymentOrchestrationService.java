package com.finx.paymentorchestration.service;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.PaymentInstructionRequest;
import com.finx.banking.dto.BankingDtos.PaymentInstructionResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.*;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import com.finx.deal.service.DealAccessService;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class PaymentOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrationService.class);
    private static final AtomicLong INSTRUCTION_SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    private final PaymentInstructionRepository instructionRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final BankingProvider bankingProvider;
    private final LedgerService ledgerService;
    private final EscrowService escrowService;
    private final AgreementService agreementService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final DealAccessService dealAccessService;

    public PaymentOrchestrationService(PaymentInstructionRepository instructionRepository,
                                       PaymentTransactionRepository transactionRepository,
                                       DealRepository dealRepository,
                                       MilestoneRepository milestoneRepository,
                                       BankingProvider bankingProvider,
                                       LedgerService ledgerService,
                                       EscrowService escrowService,
                                       AgreementService agreementService,
                                       AuditService auditService,
                                       NotificationService notificationService) {
        this(instructionRepository, transactionRepository, dealRepository, milestoneRepository,
                bankingProvider, ledgerService, escrowService, agreementService, auditService,
                notificationService, null);
    }

    @Autowired
    public PaymentOrchestrationService(PaymentInstructionRepository instructionRepository,
                                       PaymentTransactionRepository transactionRepository,
                                       DealRepository dealRepository,
                                       MilestoneRepository milestoneRepository,
                                       BankingProvider bankingProvider,
                                       LedgerService ledgerService,
                                       EscrowService escrowService,
                                       AgreementService agreementService,
                                       AuditService auditService,
                                       NotificationService notificationService,
                                       @Autowired(required = false) DealAccessService dealAccessService) {
        this.instructionRepository = instructionRepository;
        this.transactionRepository = transactionRepository;
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.bankingProvider = bankingProvider;
        this.ledgerService = ledgerService;
        this.escrowService = escrowService;
        this.agreementService = agreementService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.dealAccessService = dealAccessService;
    }

    @Transactional
    public PaymentInstructionResponse createPaymentInstruction(CreateInstructionRequest request, UserPrincipal currentUser) {
        Deal deal = dealRepository.findByIdForUpdate(request.dealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", request.dealId()));

        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer can initiate payment instructions");
        }

        if (deal.getSellerId() == null) {
            throw new BadRequestException("Cannot initiate payment: No vendor assigned to deal");
        }

        if (deal.getStatus() == DealStatus.CANCELLED || deal.getStatus() == DealStatus.REJECTED || deal.getStatus() == DealStatus.COMPLETED) {
            throw new BadRequestException("Cannot initiate payment for a " + deal.getStatus() + " deal");
        }

        if (deal.getBuyerOrganizationId() == null || deal.getSellerOrganizationId() == null) {
            throw new BadRequestException("Cannot create payment instruction for deal without verified buyer and seller organizations");
        }

        // Agreement gate check
        if (!agreementService.isAgreementFullySigned(deal.getId())) {
            throw new BadRequestException("Cannot generate payment instruction: Deal agreement is not signed by both parties");
        }

        BigDecimal instructionAmount;
        UUID milestoneId = request.milestoneId();

        if (milestoneId != null) {
            Milestone milestone = milestoneRepository.findById(milestoneId)
                    .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));
            if (!milestone.getDealId().equals(deal.getId())) {
                throw new BadRequestException("Milestone does not belong to specified deal");
            }
            if (milestone.getStatus() == MilestoneStatus.COMPLETED) {
                throw new BadRequestException("Milestone has already been completed and settled");
            }
            instructionAmount = milestone.getAmount();
        } else {
            // Whole deal funding: ensure milestone sum matches totalAmount
            List<Milestone> milestones = milestoneRepository.findByDealIdOrderBySequenceAsc(deal.getId());
            BigDecimal milestoneSum = milestones.stream()
                    .map(Milestone::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (milestoneSum.compareTo(deal.getTotalAmount()) != 0) {
                throw new BadRequestException("Invariant violation: sum(milestones) [" + milestoneSum +
                        "] does not match deal totalAmount [" + deal.getTotalAmount() + "]");
            }
            instructionAmount = deal.getTotalAmount();
        }

        // Idempotency check
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<PaymentInstruction> existing = instructionRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                PaymentInstruction existingInstruction = existing.get();
                if (!existingInstruction.getDealId().equals(deal.getId())) {
                    throw new BadRequestException("Idempotency key already exists for another deal");
                }
                return mapToResponse(existingInstruction);
            }
        }

        String instructionRef = String.format("FINX-TXN-%d-%06d",
                java.time.Year.now().getValue(), INSTRUCTION_SEQ.incrementAndGet());

        PaymentInstructionRequest bankReq = new PaymentInstructionRequest(
                deal.getId(),
                milestoneId,
                instructionRef,
                instructionAmount,
                deal.getCurrency(),
                "BUYER-CORP",
                "VENDOR-CORP"
        );

        PaymentInstructionResult bankResult = bankingProvider.createPaymentInstruction(bankReq);

        PaymentInstruction instruction = new PaymentInstruction(
                instructionRef,
                deal.getId(),
                milestoneId,
                deal.getBuyerOrganizationId(),
                deal.getSellerOrganizationId(),
                instructionAmount,
                deal.getCurrency(),
                bankingProvider.getProviderName(),
                request.idempotencyKey(),
                currentUser.getId()
        );

        instruction.setProviderInstructionId(bankResult.providerInstructionId());
        instruction.setVirtualAccountNumber(bankResult.virtualAccountNumber());
        instruction.setVirtualIfsc(bankResult.ifscCode());
        instruction.setStatus(InstructionStatus.SUBMITTED);

        PaymentInstruction saved = instructionRepository.save(instruction);

        auditService.logEvent(
                currentUser.getId(),
                "PAYMENT_INSTRUCTION_CREATED",
                "PAYMENT_INSTRUCTION",
                saved.getId().toString(),
                "Created bank payment instruction: " + instructionRef + " (" + instructionAmount + " " + deal.getCurrency() + ") VA: " + bankResult.virtualAccountNumber()
        );

        log.info("Bank payment instruction created: ref={} dealId={} amount={}",
                instructionRef, deal.getId(), instructionAmount);

        return mapToResponse(saved);
    }

    /**
     * Confirmation is disabled until authenticated bank ingestion, credit identity,
     * and atomic financial posting are remediated. Neither a user role nor a null
     * principal establishes bank evidence. Deny before lookup, including replays
     * of historical SUCCESS records; never fabricate a UTR or funding event.
     */
    public PaymentInstructionResponse confirmPayment(UUID instructionId, ConfirmPaymentRequest request, UserPrincipal currentUser) {
        throw new AccessDeniedException("Payment confirmation is disabled pending trusted bank ingestion");
    }

    @Transactional(readOnly = true)
    public List<PaymentInstructionResponse> getInstructionsForDeal(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        if (dealAccessService != null) {
            dealAccessService.requireDealAccess(deal, currentUser);
        } else if (currentUser != null && currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE) {
            boolean isParty = (deal.getBuyerId() != null && deal.getBuyerId().equals(currentUser.getId())) ||
                    (deal.getSellerId() != null && deal.getSellerId().equals(currentUser.getId()));
            if (!isParty) {
                throw new UnauthorizedException("You are not authorized to view payment instructions for this deal");
            }
        }
        return instructionRepository.findByDealIdOrderByCreatedAtDesc(dealId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private PaymentInstructionResponse mapToResponse(PaymentInstruction i) {
        return new PaymentInstructionResponse(
                i.getId(),
                i.getInstructionReference(),
                i.getDealId(),
                i.getMilestoneId(),
                i.getAmount(),
                i.getCurrency(),
                i.getProvider(),
                i.getProviderInstructionId(),
                i.getStatus(),
                i.getVirtualAccountNumber(),
                i.getVirtualIfsc(),
                i.getConfirmedAt() != null ? i.getConfirmedAt().toString() : null,
                i.getCreatedAt().toString()
        );
    }
}
