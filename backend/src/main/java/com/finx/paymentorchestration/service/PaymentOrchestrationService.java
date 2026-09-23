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
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.*;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
                return mapToResponse(existing.get());
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
                null,
                null,
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

    @Transactional
    public PaymentInstructionResponse confirmPayment(UUID instructionId, ConfirmPaymentRequest request, UserPrincipal currentUser) {
        PaymentInstruction instruction = instructionRepository.findById(instructionId)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentInstruction", "id", instructionId));

        if (instruction.getStatus() == InstructionStatus.SUCCESS) {
            log.info("Payment instruction {} already marked SUCCESS (idempotent)", instructionId);
            return mapToResponse(instruction);
        }

        String utr = (request.utrNumber() != null && !request.utrNumber().isBlank())
                ? request.utrNumber().trim()
                : "UTR-BANK-CONF-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        String txnRef = "TXN-" + instruction.getInstructionReference();

        // Record payment transaction
        PaymentTransaction tx = new PaymentTransaction(
                txnRef,
                instruction.getId(),
                request.providerReference() != null ? request.providerReference() : instruction.getProviderInstructionId(),
                instruction.getAmount(),
                instruction.getCurrency(),
                "SUCCESS",
                utr,
                "{\"status\": \"SUCCESS\", \"confirmedAt\": \"" + Instant.now() + "\"}"
        );
        transactionRepository.save(tx);

        instruction.setStatus(InstructionStatus.SUCCESS);
        instruction.setConfirmedAt(Instant.now());
        PaymentInstruction savedInstruction = instructionRepository.save(instruction);

        // Post balanced double-entry ledger entry:
        // DEBIT: 1010-ESCROW-POOL (Asset)
        // CREDIT: 2010-ESCROW-LIABILITY (Liability)
        PostJournalEntryRequest journalReq = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                savedInstruction.getId(),
                savedInstruction.getDealId(),
                savedInstruction.getMilestoneId(),
                savedInstruction.getCurrency(),
                "Bank inbound deposit confirmed via UTR " + utr + " for deal " + savedInstruction.getDealId(),
                currentUser != null ? currentUser.getId() : savedInstruction.getInitiatedBy(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, savedInstruction.getAmount(), "Escrow pool bank nodal deposit"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, savedInstruction.getAmount(), "Deal escrow liability created")
                )
        );
        ledgerService.postJournalEntry(journalReq);

        // Update Escrow balance
        escrowService.fundEscrow(
                savedInstruction.getDealId(),
                savedInstruction.getMilestoneId(),
                savedInstruction.getId(),
                savedInstruction.getAmount(),
                savedInstruction.getCurrency(),
                currentUser != null ? currentUser.getId() : savedInstruction.getInitiatedBy()
        );

        // Update Deal status
        Deal deal = dealRepository.findById(savedInstruction.getDealId()).orElse(null);
        if (deal != null) {
            deal.setStatus(DealStatus.FUNDED);
            dealRepository.save(deal);

            notificationService.sendNotification(
                    deal.getSellerId(),
                    "Escrow Funds Deposited",
                    "Bank deposit of " + savedInstruction.getAmount() + " " + savedInstruction.getCurrency() + " verified and deposited into FinX escrow nodal pool (UTR: " + utr + ").",
                    "/vendor/projects/" + deal.getId()
            );
        }

        auditService.logEvent(
                currentUser != null ? currentUser.getId() : savedInstruction.getInitiatedBy(),
                "PAYMENT_CONFIRMED",
                "PAYMENT_INSTRUCTION",
                savedInstruction.getId().toString(),
                "Confirmed bank deposit of " + savedInstruction.getAmount() + " " + savedInstruction.getCurrency() + " UTR: " + utr
        );

        log.info("Payment confirmed and balanced ledger posted: instructionId={} UTR={}", instructionId, utr);
        return mapToResponse(savedInstruction);
    }

    @Transactional(readOnly = true)
    public List<PaymentInstructionResponse> getInstructionsForDeal(UUID dealId, UserPrincipal currentUser) {
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
