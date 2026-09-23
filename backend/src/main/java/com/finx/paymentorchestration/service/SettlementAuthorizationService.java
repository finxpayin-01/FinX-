package com.finx.paymentorchestration.service;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.SettlementRequest;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
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
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.SettlementResponse;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.risk.enums.RiskEnums.RiskEntityType;
import com.finx.risk.enums.RiskEnums.RiskLevel;
import com.finx.risk.repository.RiskAssessmentRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.KybProfileRepository;
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
public class SettlementAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementAuthorizationService.class);
    private static final AtomicLong SETTLEMENT_SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    private final SettlementInstructionRepository settlementRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final OrganizationBankAccountRepository bankAccountRepository;
    private final KybProfileRepository kybProfileRepository;
    private final DisputeRepository disputeRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final AgreementService agreementService;
    private final EscrowService escrowService;
    private final LedgerService ledgerService;
    private final BankingProvider bankingProvider;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public SettlementAuthorizationService(SettlementInstructionRepository settlementRepository,
                                          DealRepository dealRepository,
                                          MilestoneRepository milestoneRepository,
                                          OrganizationBankAccountRepository bankAccountRepository,
                                          KybProfileRepository kybProfileRepository,
                                          DisputeRepository disputeRepository,
                                          RiskAssessmentRepository riskAssessmentRepository,
                                          AgreementService agreementService,
                                          EscrowService escrowService,
                                          LedgerService ledgerService,
                                          BankingProvider bankingProvider,
                                          AuditService auditService,
                                          NotificationService notificationService) {
        this.settlementRepository = settlementRepository;
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.kybProfileRepository = kybProfileRepository;
        this.disputeRepository = disputeRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.agreementService = agreementService;
        this.escrowService = escrowService;
        this.ledgerService = ledgerService;
        this.bankingProvider = bankingProvider;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public SettlementResponse authorizeAndInitiateSettlement(PayoutAuthorizationRequest request, UserPrincipal currentUser) {
        Milestone milestone = milestoneRepository.findByIdForUpdate(request.milestoneId())
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", request.milestoneId()));

        Deal deal = dealRepository.findByIdForUpdate(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        // Check idempotency if key provided
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<SettlementInstruction> existing = settlementRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Duplicate settlement request ignored via idempotencyKey: {}", request.idempotencyKey());
                return mapToResponse(existing.get());
            }
        }

        // ==========================================
        // STRICT 10-STEP PAYOUT AUTHORIZATION GATE
        // ==========================================

        // Check 10: Authorization Check
        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer or a FinX administrator can authorize settlement release");
        }

        // Check 1: Seller Organization KYB == VERIFIED
        UUID sellerOrgId = deal.getSellerOrganizationId() != null ? deal.getSellerOrganizationId() : deal.getSellerId();
        boolean sellerKybVerified = kybProfileRepository.existsByOrganizationIdAndStatus(sellerOrgId, VerificationStatus.VERIFIED);
        if (!sellerKybVerified) {
            // Check if seller user is marked verified in legacy
            if (!kybProfileRepository.existsByOrganizationIdAndStatus(deal.getSellerId(), VerificationStatus.VERIFIED)) {
                throw new BadRequestException("Payout authorization blocked: Vendor KYB is not verified. Regulated settlement cannot proceed.");
            }
        }

        // Check 2: Seller Bank Account == VERIFIED
        Optional<OrganizationBankAccount> verifiedBankOpt = bankAccountRepository.findByOrganizationIdAndVerificationStatus(
                sellerOrgId, BankVerificationStatus.VERIFIED);
        if (verifiedBankOpt.isEmpty()) {
            verifiedBankOpt = bankAccountRepository.findByOrganizationIdAndVerificationStatus(deal.getSellerId(), BankVerificationStatus.VERIFIED);
        }
        if (verifiedBankOpt.isEmpty()) {
            throw new BadRequestException("Payout authorization blocked: Vendor verified bank account not found or penny drop not completed.");
        }
        OrganizationBankAccount beneficiaryBank = verifiedBankOpt.get();

        // Check 4: Deal Status == ACTIVE, FUNDED, or ACCEPTED
        if (deal.getStatus() == DealStatus.CANCELLED || deal.getStatus() == DealStatus.REJECTED || deal.getStatus() == DealStatus.COMPLETED) {
            throw new BadRequestException("Payout authorization blocked: Deal status is " + deal.getStatus());
        }

        // Check 5: Deal Agreement == SIGNED
        if (!agreementService.isAgreementFullySigned(deal.getId())) {
            throw new BadRequestException("Payout authorization blocked: Deal agreement is not signed by both parties.");
        }

        // Check 6: Milestone Status == APPROVED
        if (milestone.getStatus() != MilestoneStatus.APPROVED) {
            throw new BadRequestException("Payout authorization blocked: Milestone must be APPROVED before settlement. Current status: " + milestone.getStatus());
        }

        // Check 7: Funds Confirmation / Escrow Balance
        EscrowAccount escrowAccount = escrowService.getOrCreateEscrowAccount(deal.getId(), deal.getCurrency());
        if (escrowAccount.getBalance().compareTo(milestone.getAmount()) < 0) {
            throw new BadRequestException("Payout authorization blocked: Insufficient escrow pool balance. Required: " +
                    milestone.getAmount() + " " + deal.getCurrency() + ", Available: " + escrowAccount.getBalance());
        }

        // Check 8: No open dispute
        if (deal.getStatus() == DealStatus.DISPUTED ||
                disputeRepository.existsByDealIdAndStatus(deal.getId(), DisputeStatus.OPEN) ||
                disputeRepository.existsByMilestoneIdAndStatus(milestone.getId(), DisputeStatus.OPEN)) {
            throw new BadRequestException("Payout authorization blocked: There is an active open dispute on this deal or milestone.");
        }

        // Check 9: Risk Status == CLEARED
        riskAssessmentRepository.findByEntityTypeAndEntityId(RiskEntityType.DEAL, deal.getId()).ifPresent(assessment -> {
            if (!assessment.isCleared() || assessment.getRiskLevel() == RiskLevel.CRITICAL) {
                throw new BadRequestException("Payout authorization blocked: Deal flagged by Risk Engine (" + assessment.getRiskLevel() + ")");
            }
        });

        // Double-settlement check
        Optional<SettlementInstruction> existingSettled = settlementRepository.findByMilestoneIdAndStatus(
                milestone.getId(), SettlementStatus.SETTLED);
        if (existingSettled.isPresent()) {
            throw new BadRequestException("Payout authorization blocked: Milestone has already been settled under reference " +
                    existingSettled.get().getSettlementReference());
        }

        // ==========================================
        // ALL 10 GATES PASSED -> INITIATE SETTLEMENT
        // ==========================================

        String settlementRef = String.format("FINX-SET-%d-%06d",
                java.time.Year.now().getValue(), SETTLEMENT_SEQ.incrementAndGet());

        SettlementRequest bankSettlementReq = new SettlementRequest(
                settlementRef,
                deal.getId(),
                milestone.getId(),
                milestone.getAmount(),
                deal.getCurrency(),
                beneficiaryBank.getAccountHolderName(),
                beneficiaryBank.getAccountNumberMasked(),
                beneficiaryBank.getIfscCode(),
                request.idempotencyKey()
        );

        SettlementResult bankResult = bankingProvider.initiateSettlement(bankSettlementReq);

        SettlementInstruction instruction = new SettlementInstruction(
                settlementRef,
                deal.getId(),
                milestone.getId(),
                sellerOrgId,
                beneficiaryBank.getId(),
                milestone.getAmount(),
                deal.getCurrency(),
                bankingProvider.getProviderName(),
                request.idempotencyKey(),
                currentUser.getId()
        );

        instruction.setProviderSettlementId(bankResult.providerSettlementId());
        instruction.setUtrNumber(bankResult.utrNumber());
        instruction.setStatus(SettlementStatus.SETTLED);
        instruction.setSettledAt(Instant.now());

        SettlementInstruction savedInstruction = settlementRepository.save(instruction);

        // Post balanced double-entry ledger entry:
        // DEBIT: 2010-ESCROW-LIABILITY (Liability decreased)
        // CREDIT: 1010-ESCROW-POOL (Asset decreased, paid out through bank nodal account)
        PostJournalEntryRequest journalReq = new PostJournalEntryRequest(
                LedgerTransactionType.MILESTONE_SETTLEMENT,
                savedInstruction.getId(),
                deal.getId(),
                milestone.getId(),
                deal.getCurrency(),
                "Bank settlement payout of " + milestone.getAmount() + " " + deal.getCurrency() + " to vendor for milestone " + milestone.getTitle() + " (UTR: " + bankResult.utrNumber() + ")",
                currentUser.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.DEBIT, milestone.getAmount(), "Escrow liability relieved on settlement"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.CREDIT, milestone.getAmount(), "Escrow bank nodal funds disbursed to vendor")
                )
        );
        ledgerService.postJournalEntry(journalReq);

        // Deduct from internal escrow balance & complete milestone
        escrowService.releaseEscrow(milestone.getId(), request.comment(), currentUser);

        auditService.logEvent(
                currentUser.getId(),
                "SETTLEMENT_COMPLETED",
                "SETTLEMENT",
                savedInstruction.getId().toString(),
                "Settlement instruction " + settlementRef + " completed: " + milestone.getAmount() + " " + deal.getCurrency() + " UTR: " + bankResult.utrNumber()
        );

        notificationService.sendNotification(
                deal.getSellerId(),
                "Settlement Disbursed",
                "Bank settlement payout of " + milestone.getAmount() + " " + deal.getCurrency() + " disbursed to verified bank account (UTR: " + bankResult.utrNumber() + ").",
                "/vendor/projects/" + deal.getId()
        );

        log.info("Settlement authorized and executed: ref={} dealId={} milestoneId={} amount={} UTR={}",
                settlementRef, deal.getId(), milestone.getId(), milestone.getAmount(), bankResult.utrNumber());

        return mapToResponse(savedInstruction);
    }

    @Transactional(readOnly = true)
    public List<SettlementResponse> getSettlementsForDeal(UUID dealId, UserPrincipal currentUser) {
        return settlementRepository.findByDealIdOrderByCreatedAtDesc(dealId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private SettlementResponse mapToResponse(SettlementInstruction s) {
        return new SettlementResponse(
                s.getId(),
                s.getSettlementReference(),
                s.getDealId(),
                s.getMilestoneId(),
                s.getAmount(),
                s.getCurrency(),
                s.getProvider(),
                s.getUtrNumber(),
                s.getStatus(),
                s.getAuthorizedAt() != null ? s.getAuthorizedAt().toString() : null,
                s.getSettledAt() != null ? s.getSettledAt().toString() : null,
                s.getFailureReason()
        );
    }
}
