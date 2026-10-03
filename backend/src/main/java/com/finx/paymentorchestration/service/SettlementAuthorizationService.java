package com.finx.paymentorchestration.service;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.SettlementRequest;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.service.DealAccessService;
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
import com.finx.exception.DuplicateResourceException;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.KybProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SettlementAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementAuthorizationService.class);

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
    private final com.finx.risk.service.RiskActionService riskActionService;
    private final com.finx.invoice.repository.InvoiceRepository invoiceRepository;
    private final DealAccessService dealAccessService;
    private final com.finx.verification.service.VerificationEligibilityService eligibilityService;
    private final ApprovalApplicabilityService approvalApplicabilityService;

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
                                          NotificationService notificationService,
                                          com.finx.risk.service.RiskActionService riskActionService) {
        this(settlementRepository, dealRepository, milestoneRepository, bankAccountRepository,
                kybProfileRepository, disputeRepository, riskAssessmentRepository, agreementService,
                escrowService, ledgerService, bankingProvider, auditService, notificationService,
                riskActionService, null, null, null, null);
    }

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
                                          NotificationService notificationService,
                                          com.finx.risk.service.RiskActionService riskActionService,
                                          com.finx.invoice.repository.InvoiceRepository invoiceRepository) {
        this(settlementRepository, dealRepository, milestoneRepository, bankAccountRepository,
                kybProfileRepository, disputeRepository, riskAssessmentRepository, agreementService,
                escrowService, ledgerService, bankingProvider, auditService, notificationService,
                riskActionService, invoiceRepository, null, null, null);
    }

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
                                          NotificationService notificationService,
                                          com.finx.risk.service.RiskActionService riskActionService,
                                          com.finx.invoice.repository.InvoiceRepository invoiceRepository,
                                          DealAccessService dealAccessService) {
        this(settlementRepository, dealRepository, milestoneRepository, bankAccountRepository,
                kybProfileRepository, disputeRepository, riskAssessmentRepository, agreementService,
                escrowService, ledgerService, bankingProvider, auditService, notificationService,
                riskActionService, invoiceRepository, dealAccessService, null, null);
    }

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
                                          NotificationService notificationService,
                                          com.finx.risk.service.RiskActionService riskActionService,
                                          com.finx.invoice.repository.InvoiceRepository invoiceRepository,
                                          DealAccessService dealAccessService,
                                          com.finx.verification.service.VerificationEligibilityService eligibilityService) {
        this(settlementRepository, dealRepository, milestoneRepository, bankAccountRepository,
                kybProfileRepository, disputeRepository, riskAssessmentRepository, agreementService,
                escrowService, ledgerService, bankingProvider, auditService, notificationService,
                riskActionService, invoiceRepository, dealAccessService, eligibilityService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
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
                                          NotificationService notificationService,
                                          com.finx.risk.service.RiskActionService riskActionService,
                                          @org.springframework.beans.factory.annotation.Autowired(required = false) com.finx.invoice.repository.InvoiceRepository invoiceRepository,
                                          @org.springframework.beans.factory.annotation.Autowired(required = false) DealAccessService dealAccessService,
                                          @org.springframework.beans.factory.annotation.Autowired(required = false) com.finx.verification.service.VerificationEligibilityService eligibilityService,
                                          @org.springframework.beans.factory.annotation.Autowired(required = false) ApprovalApplicabilityService approvalApplicabilityService) {
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
        this.riskActionService = riskActionService;
        this.invoiceRepository = invoiceRepository;
        this.dealAccessService = dealAccessService;
        this.eligibilityService = eligibilityService;
        this.approvalApplicabilityService = approvalApplicabilityService;
    }

    @Transactional
    public SettlementResponse authorizeAndInitiateSettlement(PayoutAuthorizationRequest request, UserPrincipal currentUser) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().trim().isEmpty()) {
            throw new BadRequestException("Mandatory idempotency key is missing or blank");
        }
        String idempotencyKey = request.idempotencyKey().trim();

        if (request.milestoneId() == null) {
            throw new BadRequestException("Milestone ID is required");
        }

        if (currentUser == null) {
            throw new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign");
        }

        Milestone milestone = milestoneRepository.findById(request.milestoneId())
                .orElseThrow(() -> new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign"));

        Deal deal = dealRepository.findByIdForUpdate(milestone.getDealId())
                .orElseThrow(() -> new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign"));

        if (dealAccessService != null) {
            try {
                dealAccessService.requireDealAccess(deal, currentUser);
            } catch (UnauthorizedException e) {
                throw new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign");
            }
        }

        if (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE) {
            throw new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign");
        }

        // Compute deterministic request hash from canonical settlement fields
        String canonicalRequest = deal.getId() + ":" + milestone.getId() + ":" +
                (milestone.getAmount() != null ? milestone.getAmount().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString() : "0.0000") + ":" +
                deal.getCurrency() + ":" + (request.comment() != null ? request.comment().trim() : "");
        String requestHash = computeSha256(canonicalRequest);

        // 1. Scoped idempotency check on same deal
        Optional<SettlementInstruction> scopedExisting = settlementRepository.findByDealIdAndIdempotencyKey(deal.getId(), idempotencyKey);
        if (scopedExisting.isPresent()) {
            SettlementInstruction existing = scopedExisting.get();
            if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
                throw new DuplicateResourceException("Idempotency key collision with different settlement payload");
            }
            log.info("Safe idempotent replay for settlement instruction: ref={} dealId={}",
                    existing.getSettlementReference(), deal.getId());
            return mapToResponse(existing);
        }

        // 2. Cross-deal / cross-tenant check: deny without data disclosure
        Optional<SettlementInstruction> anyExisting = settlementRepository.findByIdempotencyKey(idempotencyKey);
        if (anyExisting.isPresent() && !anyExisting.get().getDealId().equals(deal.getId())) {
            throw new DuplicateResourceException("Idempotency key already exists for another financial context");
        }

        // 3. Business effect uniqueness: active settlement check on milestone
        Optional<SettlementInstruction> activeExisting = settlementRepository.findFirstByMilestoneIdAndStatusIn(
                milestone.getId(),
                List.of(SettlementStatus.PENDING, SettlementStatus.PROCESSING, SettlementStatus.SETTLED)
        );
        if (activeExisting.isPresent()) {
            throw new DuplicateResourceException("An active or completed settlement effect already exists for milestone " + milestone.getId());
        }

        // 4. Fail closed before dispatch (B04 preserved, 0 banking calls)
        throw new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign");
    }

    @Transactional(readOnly = true)
    public ApprovalApplicabilityService.ApprovalApplicabilityResult evaluateApprovalApplicability(UUID dealId, UUID milestoneId) {
        if (approvalApplicabilityService != null) {
            return approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        }
        throw new BadRequestException("Approval applicability service is not configured");
    }

    @Transactional(readOnly = true)
    public com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult evaluateBeneficiaryEligibility(UUID organizationId) {
        if (eligibilityService != null) {
            return eligibilityService.evaluateBeneficiaryEligibility(organizationId);
        }
        if (organizationId == null) {
            return new com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult(
                    false, null, "Organization ID is required", false);
        }
        List<OrganizationBankAccount> accounts = bankAccountRepository.findByOrganizationId(organizationId);
        List<OrganizationBankAccount> eligible = accounts.stream()
                .filter(a -> a.getVerificationStatus() == BankVerificationStatus.VERIFIED)
                .filter(a -> a.getEncryptedAccountNumber() != null && !a.getEncryptedAccountNumber().isBlank())
                .filter(a -> a.getVerifiedAt() != null)
                .filter(a -> !"MISMATCH".equalsIgnoreCase(a.getNameMatchStatus()))
                .collect(Collectors.toList());
        if (eligible.isEmpty()) {
            return new com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult(
                    false, null, "No eligible verified bank accounts found for organization " + organizationId, false);
        }
        if (eligible.size() > 1) {
            return new com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult(
                    false, null, "Ambiguous beneficiary: multiple eligible verified bank accounts found (" + eligible.size() + ") for organization " + organizationId + ". Explicit beneficiary selection required.", false);
        }
        OrganizationBankAccount single = eligible.get(0);
        boolean isSandbox = "MOCK".equalsIgnoreCase(single.getProvider()) || (single.getVerificationReference() != null && single.getVerificationReference().startsWith("MOCK"));
        return new com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult(true, single, null, isSandbox);
    }

    @Transactional(readOnly = true)
    public OrganizationBankAccount requireSingleEligibleBeneficiary(UUID organizationId) {
        var result = evaluateBeneficiaryEligibility(organizationId);
        if (!result.eligible()) {
            throw new BadRequestException("Beneficiary eligibility check failed: " + result.failureReason());
        }
        return result.bankAccount();
    }

    @Transactional(readOnly = true)
    public List<SettlementResponse> getSettlementsForDeal(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        if (dealAccessService != null) {
            dealAccessService.requireDealAccess(deal, currentUser);
        } else if (currentUser != null && currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE) {
            boolean isParty = (deal.getBuyerId() != null && deal.getBuyerId().equals(currentUser.getId())) ||
                    (deal.getSellerId() != null && deal.getSellerId().equals(currentUser.getId()));
            if (!isParty) {
                throw new UnauthorizedException("You are not authorized to view settlements for this deal");
            }
        }
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

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 calculation failed", e);
        }
    }
}
