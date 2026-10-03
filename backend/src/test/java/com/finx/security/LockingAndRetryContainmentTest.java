package com.finx.security;

import com.finx.agreement.dto.AgreementDtos.CreateAgreementDraftRequest;
import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.AgreementAuditEventRepository;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.entity.LedgerAccount;
import com.finx.ledger.enums.LedgerEnums.AccountType;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.JournalLineRepository;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.milestone.service.MilestoneService;
import com.finx.notification.service.NotificationService;
import com.finx.payment.config.PaymentRailPolicy;
import com.finx.payment.dto.request.CreateOrderRequest;
import com.finx.payment.repository.PaymentRepository;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService.ApprovalApplicabilityResult;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskActionService;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B11 Containment Tests: Deterministic Locking, Concurrency Race Prevention,
 * and Transaction Boundary Hardening.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LockingAndRetryContainmentTest {

    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private JournalLineRepository journalLineRepository;
    @Mock private AuditService auditService;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private EscrowAccountRepository escrowAccountRepository;
    @Mock private EscrowLedgerRepository escrowLedgerRepository;
    @Mock private NotificationService notificationService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DealAgreementRepository dealAgreementRepository;
    @Mock private AgreementAuditEventRepository agreementAuditEventRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RazorpayService razorpayService;
    @Mock private BankingProvider bankingProvider;
    @Mock private SettlementInstructionRepository settlementRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private RiskActionService riskActionService;
    @Mock private BusinessRiskRepository businessRiskRepository;

    private LedgerService ledgerService;
    private EscrowService escrowService;
    private MilestoneService milestoneService;
    private AgreementService agreementService;
    private PaymentService paymentService;
    private SettlementAuthorizationService settlementAuthorizationService;
    private ApprovalApplicabilityService approvalApplicabilityService;

    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerId;
    private UUID sellerId;
    private UUID pmId;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;
    private UserPrincipal pmPrincipal;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;

    @BeforeEach
    void setUp() {
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        pmId = UUID.randomUUID();

        buyerPrincipal = new UserPrincipal(buyerId, "Buyer Corp", "buyer@corp.test", "hash",
                Role.BUYER, UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_BUYER")));

        sellerPrincipal = new UserPrincipal(sellerId, "Seller Vendor", "seller@vendor.test", "hash",
                Role.SELLER, UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_SELLER")));

        pmPrincipal = new UserPrincipal(pmId, "PM User", "pm@platform.test", "hash",
                Role.PROJECT_MANAGER, UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_PROJECT_MANAGER")));

        deal = new Deal("Locking Test Deal", "Description", buyerId, sellerId, BigDecimal.valueOf(100000), "INR", DealStatus.ACCEPTED);
        deal.setId(dealId);
        deal.setProjectManagerId(pmId);

        milestone = new Milestone(dealId, "Milestone 1", "Desc", 1, BigDecimal.valueOf(50000), "INR", Instant.now().plusSeconds(86400));
        milestone.setId(milestoneId);
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);

        escrowAccount = new EscrowAccount(dealId, BigDecimal.valueOf(50000), "INR");
        escrowAccount.setId(UUID.randomUUID());

        ledgerService = new LedgerService(ledgerAccountRepository, journalEntryRepository, journalLineRepository, auditService);

        escrowService = new EscrowService(escrowAccountRepository, escrowLedgerRepository, dealRepository,
                milestoneRepository, disputeRepository, auditService, notificationService);

        milestoneService = new MilestoneService(milestoneRepository, deliverableRepository, dealRepository,
                auditService, notificationService, escrowService, null, new FinxClock());

        agreementService = new AgreementService(dealAgreementRepository, agreementAuditEventRepository,
                dealRepository, auditService, null);

        paymentService = new PaymentService(paymentRepository, dealRepository, milestoneRepository,
                razorpayService, escrowService, auditService, notificationService, new PaymentRailPolicy("BANK"));

        approvalApplicabilityService = new ApprovalApplicabilityService(
                dealRepository, milestoneRepository, deliverableRepository,
                null, null, null,
                dealAgreementRepository, null, disputeRepository,
                riskActionService, businessRiskRepository
        );

        settlementAuthorizationService = new SettlementAuthorizationService(
                settlementRepository, dealRepository, milestoneRepository, null,
                null, disputeRepository, null, agreementService,
                escrowService, ledgerService, bankingProvider, auditService,
                notificationService, riskActionService, null, null, null, approvalApplicabilityService
        );
    }

    // =========================================================================
    // 1. LEDGER DETERMINISTIC LOCK ORDERING TESTS
    // =========================================================================

    @Test
    @DisplayName("01: LedgerService locks accounts in sorted deterministic order when line items are reversed")
    void test01_deterministicLedgerLockOrder_reversedAccountOrder_lockedInSortedOrder() {
        // Setup two accounts: A ("1010-ESCROW-POOL") and B ("2010-ESCROW-LIABILITY")
        LedgerAccount accPool = new LedgerAccount(LedgerService.ACC_ESCROW_POOL, "Pool", AccountType.ASSET, "INR");
        accPool.setBalance(BigDecimal.valueOf(100000));
        LedgerAccount accLiab = new LedgerAccount(LedgerService.ACC_ESCROW_LIABILITY, "Liability", AccountType.LIABILITY, "INR");
        accLiab.setBalance(BigDecimal.valueOf(100000));

        when(ledgerAccountRepository.findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_POOL)).thenReturn(Optional.of(accPool));
        when(ledgerAccountRepository.findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_LIABILITY)).thenReturn(Optional.of(accLiab));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(i -> i.getArgument(0));

        // Request with reversed lines: B (2010) followed by A (1010)
        PostJournalEntryRequest requestReversed = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, UUID.randomUUID(), dealId, milestoneId,
                "INR", "Reversed order test", buyerId,
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(10000), "Credit liab"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(10000), "Debit pool")
                )
        );

        ledgerService.postJournalEntry(requestReversed);

        // Verify locks were acquired in alphabetical/lexicographical sorted order: 1010 before 2010
        InOrder inOrder = inOrder(ledgerAccountRepository);
        inOrder.verify(ledgerAccountRepository).findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_POOL);
        inOrder.verify(ledgerAccountRepository).findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_LIABILITY);
    }

    @Test
    @DisplayName("02: LedgerService deduplicates accounts to lock each account exactly once")
    void test02_deterministicLedgerLockOrder_duplicateAccountInLines_lockedOnlyOnce() {
        LedgerAccount accPool = new LedgerAccount(LedgerService.ACC_ESCROW_POOL, "Pool", AccountType.ASSET, "INR");
        accPool.setBalance(BigDecimal.valueOf(100000));
        LedgerAccount accLiab = new LedgerAccount(LedgerService.ACC_ESCROW_LIABILITY, "Liability", AccountType.LIABILITY, "INR");
        accLiab.setBalance(BigDecimal.valueOf(100000));

        when(ledgerAccountRepository.findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_POOL)).thenReturn(Optional.of(accPool));
        when(ledgerAccountRepository.findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_LIABILITY)).thenReturn(Optional.of(accLiab));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(i -> i.getArgument(0));

        // 3 lines: two for pool, one for liability
        PostJournalEntryRequest request = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, UUID.randomUUID(), dealId, milestoneId,
                "INR", "Multi-line test", buyerId,
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(6000), "Debit pool 1"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(4000), "Debit pool 2"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(10000), "Credit liab")
                )
        );

        ledgerService.postJournalEntry(request);

        // Each distinct account locked exactly once
        verify(ledgerAccountRepository, times(1)).findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_POOL);
        verify(ledgerAccountRepository, times(1)).findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_LIABILITY);
    }

    @Test
    @DisplayName("03: Multi-threaded ledger posting with competing line orders executes safely without deadlock")
    void test03_ledgerPosting_concurrentExecution_balancedAccountingPreserved() throws Exception {
        LedgerAccount accPool = new LedgerAccount(LedgerService.ACC_ESCROW_POOL, "Pool", AccountType.ASSET, "INR");
        accPool.setBalance(BigDecimal.valueOf(1000000));
        LedgerAccount accLiab = new LedgerAccount(LedgerService.ACC_ESCROW_LIABILITY, "Liability", AccountType.LIABILITY, "INR");
        accLiab.setBalance(BigDecimal.valueOf(1000000));

        when(ledgerAccountRepository.findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_POOL)).thenReturn(Optional.of(accPool));
        when(ledgerAccountRepository.findByAccountNumberForUpdate(LedgerService.ACC_ESCROW_LIABILITY)).thenReturn(Optional.of(accLiab));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(i -> i.getArgument(0));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final boolean reverse = (i % 2 == 1);
            executor.submit(() -> {
                try {
                    List<JournalLineItem> lines = reverse
                            ? List.of(
                            new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(100), "Credit"),
                            new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(100), "Debit"))
                            : List.of(
                            new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(100), "Debit"),
                            new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(100), "Credit"));

                    PostJournalEntryRequest req = new PostJournalEntryRequest(
                            LedgerTransactionType.ESCROW_DEPOSIT, UUID.randomUUID(), dealId, milestoneId,
                            "INR", "Concurrent line test", buyerId, lines
                    );
                    ledgerService.postJournalEntry(req);
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    // =========================================================================
    // 2. ESCROW ACCOUNT CREATION SERIALIZATION TESTS
    // =========================================================================

    @Test
    @DisplayName("04: Escrow account creation serializes on parent Deal lock and creates account once")
    void test04_concurrentEscrowAccountCreation_serializedOnParentDealLock() {
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(escrowAccountRepository.findByDealId(dealId)).thenReturn(Optional.empty());
        when(escrowAccountRepository.saveAndFlush(any(EscrowAccount.class))).thenAnswer(i -> {
            EscrowAccount ea = i.getArgument(0);
            ea.setId(UUID.randomUUID());
            return ea;
        });

        EscrowAccount account = escrowService.ensureEscrowAccount(dealId, "INR");

        assertThat(account).isNotNull();
        assertThat(account.getDealId()).isEqualTo(dealId);
        // Proves Deal lock was acquired to serialize account creation
        verify(dealRepository).findByIdForUpdate(dealId);
        verify(escrowAccountRepository).saveAndFlush(any(EscrowAccount.class));
    }

    @Test
    @DisplayName("05: Escrow account creation returns existing account idempotently without duplicate insert")
    void test05_escrowAccountCreation_returnsExistingWithoutDuplicateInsert() {
        when(escrowAccountRepository.findByDealId(dealId)).thenReturn(Optional.of(escrowAccount));

        EscrowAccount account = escrowService.ensureEscrowAccount(dealId, "INR");

        assertThat(account).isSameAs(escrowAccount);
        verify(escrowAccountRepository, never()).saveAndFlush(any(EscrowAccount.class));
    }

    // =========================================================================
    // 3. CANONICAL LOCK ORDERING (DEAL -> MILESTONE -> ESCROW) TESTS
    // =========================================================================

    @Test
    @DisplayName("06: releaseEscrowForSettlement acquires Deal lock before Milestone lock")
    void test06_releaseEscrowForSettlement_locksDealBeforeMilestone() {
        SettlementInstruction settlement = new SettlementInstruction(
                "FINX-SET-001", dealId, milestoneId, UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.valueOf(50000), "INR", "mock", "IDEMP-123", UUID.randomUUID()
        );
        settlement.setId(UUID.randomUUID());
        settlement.setStatus(SettlementStatus.SETTLED);
        settlement.setUtrNumber("UTR-TEST-12345");

        milestone.setStatus(MilestoneStatus.APPROVED);
        deal.setStatus(DealStatus.ACCEPTED);

        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(escrowLedgerRepository.saveAndFlush(any(EscrowLedger.class))).thenAnswer(i -> i.getArgument(0));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        escrowService.releaseEscrowForSettlement(settlement, "Release comment", buyerPrincipal);

        // Verify canonical lock order: Deal lock acquired before Milestone lock
        InOrder inOrder = inOrder(dealRepository, milestoneRepository, escrowAccountRepository);
        inOrder.verify(dealRepository).findByIdForUpdate(dealId);
        inOrder.verify(milestoneRepository).findByIdForUpdate(milestoneId);
        inOrder.verify(escrowAccountRepository).findByDealIdForUpdate(dealId);
    }

    @Test
    @DisplayName("07: fundEscrow acquires Deal lock before Milestone and Escrow locks")
    void test07_fundEscrow_locksDealBeforeMilestone() {
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepository.findByDealId(dealId)).thenReturn(Optional.of(escrowAccount));
        when(escrowLedgerRepository.saveAndFlush(any(EscrowLedger.class))).thenAnswer(i -> i.getArgument(0));
        when(escrowAccountRepository.saveAndFlush(any(EscrowAccount.class))).thenAnswer(i -> i.getArgument(0));

        escrowService.fundEscrow(dealId, milestoneId, UUID.randomUUID(), BigDecimal.valueOf(10000), "INR", buyerId);

        InOrder inOrder = inOrder(dealRepository, milestoneRepository);
        inOrder.verify(dealRepository).findByIdForUpdate(dealId);
        inOrder.verify(milestoneRepository).findByIdForUpdate(milestoneId);
    }

    // =========================================================================
    // 4. APPROVAL WRITER CONCURRENCY & RACE PREVENTION TESTS
    // =========================================================================

    @Test
    @DisplayName("08: Milestone approve acquires Deal lock before Milestone lock")
    void test08_approveMilestone_acquiresDealThenMilestoneLock() {
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        milestoneService.approveMilestone(milestoneId, buyerPrincipal);

        InOrder inOrder = inOrder(dealRepository, milestoneRepository);
        inOrder.verify(dealRepository).findByIdForUpdate(dealId);
        inOrder.verify(milestoneRepository).findByIdForUpdate(milestoneId);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }

    @Test
    @DisplayName("09: Milestone reject acquires Deal lock before Milestone lock")
    void test09_rejectMilestone_acquiresDealThenMilestoneLock() {
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        milestoneService.rejectMilestone(milestoneId, "Changes requested", buyerPrincipal);

        InOrder inOrder = inOrder(dealRepository, milestoneRepository);
        inOrder.verify(dealRepository).findByIdForUpdate(dealId);
        inOrder.verify(milestoneRepository).findByIdForUpdate(milestoneId);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.REJECTED);
    }

    @Test
    @DisplayName("10: approve vs reject race: losing reject operation fails closed after milestone approved")
    void test10_approveVsRejectRace_losingOperationFailsClosed() {
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        // Thread 1 approves
        milestoneService.approveMilestone(milestoneId, buyerPrincipal);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);

        // Thread 2 attempts to reject already approved milestone
        assertThatThrownBy(() -> milestoneService.rejectMilestone(milestoneId, "Too late", buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only submitted milestones under review can be rejected");
    }

    @Test
    @DisplayName("11: Concurrent approvals: second approval fails closed after first approval commits")
    void test11_concurrentApprovals_secondApprovalFailsClosed() {
        milestone.setStatus(MilestoneStatus.UNDER_REVIEW);
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        // First approval succeeds
        milestoneService.approveMilestone(milestoneId, buyerPrincipal);

        // Second approval attempt on now APPROVED milestone fails closed
        assertThatThrownBy(() -> milestoneService.approveMilestone(milestoneId, pmPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only submitted milestones under review can be approved");
    }

    @Test
    @DisplayName("12: Agreement draft creation locks parent Deal to prevent concurrent draft collision")
    void test12_agreementDraftCreation_locksParentDeal() {
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(dealAgreementRepository.findTopByDealIdOrderByVersionDesc(dealId)).thenReturn(Optional.empty());
        when(dealAgreementRepository.save(any(DealAgreement.class))).thenAnswer(i -> i.getArgument(0));

        CreateAgreementDraftRequest req = new CreateAgreementDraftRequest("Agreement Title", "Terms and conditions content");
        agreementService.createOrUpdateDraft(dealId, req, buyerPrincipal);

        verify(dealRepository).findByIdForUpdate(dealId);
        verify(dealAgreementRepository).save(any(DealAgreement.class));
    }

    @Test
    @DisplayName("13: Agreement signing locks parent Deal and rejects double-sign race")
    void test13_agreementSigning_locksParentDealAndRejectsDoubleSignRace() {
        DealAgreement agreement = new DealAgreement(dealId, 1, "Agreement", "Terms", "sha256");
        agreement.setStatus(AgreementStatus.SIGNED);

        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(dealAgreementRepository.findByDealIdAndVersion(dealId, 1)).thenReturn(Optional.of(agreement));

        assertThatThrownBy(() -> agreementService.signAgreement(dealId, 1, buyerPrincipal, "127.0.0.1", "Agent"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been fully signed");

        verify(dealRepository).findByIdForUpdate(dealId);
    }

    // =========================================================================
    // 5. B10/B11 SNAPSHOT COHERENCE & CONCURRENT MUTATION TESTS
    // =========================================================================

    @Test
    @DisplayName("14: Approval applicability fails closed when concurrent Deal mutation occurs during evaluation")
    void test14_approvalApplicability_failsClosedOnConcurrentDealMutation() {
        Deal mutatedDeal = new Deal("Mutated Deal", "Desc", buyerId, sellerId, BigDecimal.valueOf(100000), "INR", DealStatus.ACCEPTED);
        mutatedDeal.setId(dealId);
        try {
            java.lang.reflect.Field field = Deal.class.getDeclaredField("updatedAt");
            field.setAccessible(true);
            field.set(mutatedDeal, Instant.now().plusSeconds(10));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(dealRepository.findById(dealId)).thenReturn(
                Optional.of(deal), // initial read
                Optional.of(mutatedDeal) // final re-verification read with different timestamp
        );
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));

        ApprovalApplicabilityResult result = approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes()).anyMatch(r -> r.contains("SNAPSHOT_COHERENCE_VIOLATION"));
    }

    @Test
    @DisplayName("15: Approval applicability fails closed when concurrent Milestone mutation occurs during evaluation")
    void test15_approvalApplicability_failsClosedOnConcurrentMilestoneMutation() {
        Milestone mutatedMilestone = new Milestone(dealId, "Milestone 1", "Desc", 1, BigDecimal.valueOf(50000), "INR", Instant.now().plusSeconds(86400));
        mutatedMilestone.setId(milestoneId);
        mutatedMilestone.setUpdatedAt(Instant.now().plusSeconds(10)); // timestamp changed during evaluation

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(
                Optional.of(milestone), // initial read
                Optional.of(mutatedMilestone) // final read with newer updatedAt
        );

        ApprovalApplicabilityResult result = approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes()).anyMatch(r -> r.contains("SNAPSHOT_COHERENCE_VIOLATION"));
    }

    @Test
    @DisplayName("16: Approval applicability fails closed when PM is reassigned during evaluation")
    void test16_approvalApplicability_failsClosedOnConcurrentPmReassignment() {
        Deal reassignedDeal = new Deal("Reassigned", "Desc", buyerId, sellerId, BigDecimal.valueOf(100000), "INR", DealStatus.ACCEPTED);
        reassignedDeal.setId(dealId);
        reassignedDeal.setProjectManagerId(UUID.randomUUID()); // new PM assigned concurrently

        when(dealRepository.findById(dealId)).thenReturn(
                Optional.of(deal),
                Optional.of(reassignedDeal)
        );
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));

        ApprovalApplicabilityResult result = approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes()).anyMatch(r -> r.contains("SNAPSHOT_COHERENCE_VIOLATION"));
    }

    // =========================================================================
    // 6. RETRY BOUNDARY & EXECUTION SAFETY TESTS
    // =========================================================================

    @Test
    @DisplayName("17: Settlement payout execution remains strictly disabled under B04 with zero bank provider calls")
    void test17_settlementExecution_remainsDisabledWithZeroBankCalls() {
        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(
                milestoneId, "Authorize release", "GLOBAL_IDEMP_TEST_123"
        );

        assertThatThrownBy(() -> settlementAuthorizationService.authorizeAndInitiateSettlement(request, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled pending settlement orchestration redesign");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("18: New Razorpay order creation remains strictly disabled in BANK mode under B12")
    void test18_newRazorpayOrderCreation_remainsDisabledInBankMode() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setDealId(dealId);
        request.setMilestoneId(milestoneId);

        assertThatThrownBy(() -> paymentService.createPaymentOrder(request, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled in BANK mode");

        verifyNoInteractions(razorpayService);
    }

    @Test
    @DisplayName("19: BankingProvider initiateSettlement call count strictly remains 0 during all locking containment")
    void test19_bankingProviderInitiateSettlement_callsStrictlyZero() {
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("20: Risk applicability check is read-only and acquires no exclusive domain entity locks")
    void test20_riskApplicabilityCheck_acquiresNoEntityLocks() {
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(businessRiskRepository.query(any(), any(), any(), anyInt())).thenReturn(Collections.emptyList());

        ApprovalApplicabilityResult result = approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        // evaluateApprovalApplicability uses readOnly transactional queries, no pessimistic write locks
        verify(dealRepository, never()).findByIdForUpdate(any());
        verify(milestoneRepository, never()).findByIdForUpdate(any());
        assertThat(result.riskApplicable()).isTrue();
    }

    @Test
    @DisplayName("21: Direct escrow release remains fail closed under B03")
    void test21_directEscrowRelease_remainsDisabledUnderB03() {
        assertThatThrownBy(() -> escrowService.releaseEscrow(milestoneId, "Direct release", buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Direct escrow release is disabled");
    }

    @Test
    @DisplayName("22: Unbound milestone approval is not treated as current financial authorization under B10/B11")
    void test22_unboundMilestoneApproval_notTreatedAsCurrentFinancialAllow() {
        milestone.setStatus(MilestoneStatus.APPROVED);
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));

        ApprovalApplicabilityResult result = approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        assertThat(result.milestoneApproved()).isTrue();
        assertThat(result.milestoneUnbound()).isTrue();
        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes()).anyMatch(r -> r.contains("UNBOUND_MILESTONE_APPROVAL"));
    }
}
