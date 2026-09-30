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
import org.springframework.security.access.AccessDeniedException;
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
    private final com.finx.risk.service.RiskActionService riskActionService;
    private final com.finx.invoice.repository.InvoiceRepository invoiceRepository;

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
        this.invoiceRepository = null;
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
                                          @org.springframework.beans.factory.annotation.Autowired(required = false) com.finx.invoice.repository.InvoiceRepository invoiceRepository) {
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
    }

    @Transactional
    public SettlementResponse authorizeAndInitiateSettlement(PayoutAuthorizationRequest request, UserPrincipal currentUser) {
        throw new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign");
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
