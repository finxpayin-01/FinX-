package com.finx.release.finalizer;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.entity.JournalLine;
import com.finx.ledger.entity.LedgerAccount;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.JournalLineRepository;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.finalizer.model.SettlementFinalizationResult;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeObservationResult;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.ReleaseAuthorizationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e9_test}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:finx_user}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:finx_password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "rate-limit.enabled=false",
        "finx.banking.provider=mock",
        "finx.release.sandbox-finalization.enabled=true"
})
@EnabledIf("isPostgresAvailable")
public class FinalizationConcurrencyAndRollbackMatrixTest {

    static {
        ensureDatabaseExists("finx_release_e9_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FinxClock clock;

    @Autowired private TrustedSettlementFinalizer finalizer;
    @Autowired private SettlementObservationEvidenceReader evidenceReader;
    @Autowired private ReleaseAuthorizationService authorizationService;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private DealRepository dealRepo;
    @SpyBean private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private EscrowLedgerRepository escrowLedgerRepo;
    @Autowired private LedgerService ledgerService;
    @Autowired private LedgerAccountRepository ledgerAccountRepo;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;

    @MockBean private BankingProvider bankingProvider;

    private User adminUser;
    private UserPrincipal adminPrincipal;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;
    private OrganizationBankAccount bankAccount;
    private Organization testOrg;
    private EnginePolicyVersion defaultPolicy;

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureDatabaseExists(String dbName) {
        if (!isPostgresAvailable()) return;
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/postgres", "finx_user", "finx_password")) {
            try (ResultSet rs = conn.getMetaData().getCatalogs()) {
                while (rs.next()) {
                    if (dbName.equalsIgnoreCase(rs.getString(1))) return;
                }
            }
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE " + dbName);
            }
        } catch (SQLException ignored) {
        }
    }

    @BeforeEach
    void setUp() {
        clock.reset();
        jdbc.execute("UPDATE release_instructions SET status = 'PENDING_SETTLEMENT', settled_at = NULL, final_settlement_observation_id = NULL");
        jdbc.execute("DELETE FROM journal_lines");
        jdbc.execute("DELETE FROM journal_entries");
        jdbc.execute("DELETE FROM escrow_ledger");
        jdbc.execute("DELETE FROM settlement_observations");
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM settlement_instructions");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");
        jdbc.execute("DELETE FROM engine_decisions");
        jdbc.execute("DELETE FROM engine_policy_versions");
        jdbc.execute("DELETE FROM payment_transactions");
        jdbc.execute("DELETE FROM payment_instructions");
        jdbc.execute("DELETE FROM disputes");
        jdbc.execute("DELETE FROM milestone_approval_bindings");
        jdbc.execute("DELETE FROM deliverables");
        jdbc.execute("DELETE FROM milestones");
        jdbc.execute("DELETE FROM escrow_accounts");
        jdbc.execute("DELETE FROM deals");
        jdbc.execute("DELETE FROM organization_bank_accounts");
        jdbc.execute("DELETE FROM organizations");

        ledgerService.initSystemAccounts();
        jdbc.execute("UPDATE ledger_accounts SET balance = 0");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUser = userRepo.save(new User("Admin " + suffix, "admin" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));
        adminPrincipal = UserPrincipal.create(adminUser);

        testOrg = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", adminUser.getId()));

        defaultPolicy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", adminUser.getId(), Instant.now()
        ));

        bankAccount = new OrganizationBankAccount(testOrg.getId(), "Legal " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        bankAccount.setEncryptedAccountNumber("enc-" + suffix);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount = bankAccRepo.save(bankAccount);

        deal = new Deal();
        deal.setTitle("Deal " + suffix);
        deal.setDescription("Test deal");
        deal.setBuyerId(adminUser.getId());
        deal.setSellerId(adminUser.getId());
        deal.setTotalAmount(new BigDecimal("100000.0000"));
        deal.setCurrency("INR");
        deal.setStatus(DealStatus.FUNDED);
        deal = dealRepo.save(deal);

        milestone = new Milestone();
        milestone.setDealId(deal.getId());
        milestone.setTitle("M1");
        milestone.setDescription("Desc");
        milestone.setAmount(new BigDecimal("50000.0000"));
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone = milestoneRepo.save(milestone);

        escrowAccount = new EscrowAccount();
        escrowAccount.setDealId(deal.getId());
        escrowAccount.setCurrency("INR");
        escrowAccount.setBalance(new BigDecimal("100000.00"));
        escrowAccount.setTotalFunded(new BigDecimal("100000.00"));
        escrowAccount.setTotalReleased(BigDecimal.ZERO);
        escrowAccount = escrowRepo.save(escrowAccount);

        // Pre-fund ledger accounts: Dr 1010-ESCROW-POOL, Cr 2010-ESCROW-LIABILITY
        fundLedger(deal.getId(), new BigDecimal("100000.00"));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    private void fundLedger(UUID dealId, BigDecimal amount) {
        ledgerService.postJournalEntry(new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                dealId,
                dealId,
                null,
                "INR",
                "Escrow initial funding",
                adminUser.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, amount, "Fund escrow pool"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, amount, "Fund escrow liability")
                )
        ));
    }

    private EngineDecision createDecision(UUID orgId, UUID milestoneId) {
        Instant now = Instant.now();
        EngineDecision decision = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(decision, orgId);
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(decision, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(decision, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(decision, milestoneId);
            var fDec = EngineDecision.class.getDeclaredField("decision"); fDec.setAccessible(true); fDec.set(decision, DecisionStatus.ALLOW);
            var fOut = EngineDecision.class.getDeclaredField("evaluationOutcome"); fOut.setAccessible(true); fOut.set(decision, EvaluationOutcome.SUCCESS);
            var fInFp = EngineDecision.class.getDeclaredField("inputFingerprint"); fInFp.setAccessible(true); fInFp.set(decision, "0".repeat(64));
            var fDecFp = EngineDecision.class.getDeclaredField("decisionFingerprint"); fDecFp.setAccessible(true); fDecFp.set(decision, "1".repeat(64));
            var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(decision, defaultPolicy.getId());
            var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(decision, defaultPolicy.getContentHash());
            var fEvId = EngineDecision.class.getDeclaredField("evaluationId"); fEvId.setAccessible(true); fEvId.set(decision, UUID.randomUUID());
            var fCoId = EngineDecision.class.getDeclaredField("correlationId"); fCoId.setAccessible(true); fCoId.set(decision, UUID.randomUUID());
            var fRuleV = EngineDecision.class.getDeclaredField("ruleVersion"); fRuleV.setAccessible(true); fRuleV.set(decision, "1.0.0");
            var fRea = EngineDecision.class.getDeclaredField("reasonCodesJson"); fRea.setAccessible(true); fRea.set(decision, "[]");
            var fEvi = EngineDecision.class.getDeclaredField("evidenceReferencesJson"); fEvi.setAccessible(true); fEvi.set(decision, "[]");
            var fEvAt = EngineDecision.class.getDeclaredField("evaluatedAt"); fEvAt.setAccessible(true); fEvAt.set(decision, now);
            var fVal = EngineDecision.class.getDeclaredField("validUntil"); fVal.setAccessible(true); fVal.set(decision, now.plusSeconds(3600));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return decisionRepo.save(decision);
    }

    private TestItem createSettledReadyFlow(BigDecimal amount, Milestone targetMilestone) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        EngineDecision decision = createDecision(testOrg.getId(), targetMilestone.getId());
        ReleaseInstruction instruction = new ReleaseInstruction(
                UUID.randomUUID(),
                testOrg.getId(),
                deal.getId(),
                targetMilestone.getId(),
                decision.getId(),
                decision.getDecisionFingerprint(),
                defaultPolicy.getId(),
                defaultPolicy.getContentHash(),
                bankAccount.getId(),
                "MOCK",
                "ESCROW-POOL-1",
                amount,
                "INR",
                "idem-" + suffix,
                "c".repeat(64),
                adminUser.getId()
        );
        instruction.markAuthorized(adminUser.getId(), Instant.now());
        instruction.markReserved();
        instruction.markQueued();
        instruction.markDispatching();
        instruction.markPendingSettlement();
        instruction = instructionRepo.save(instruction);

        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(),
                instruction.getId(),
                escrowAccount.getId(),
                targetMilestone.getId(),
                amount,
                "INR"
        );
        reservationRepo.save(reservation);

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(),
                instruction.getId(),
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                "OP-" + suffix,
                1,
                FinancialOutboxStatus.PENDING,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
        outbox = outboxRepo.save(outbox);

        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instruction.getId(),
                outbox.getId(),
                1,
                "OP-" + suffix,
                "OP-" + suffix,
                "MOCK",
                "ESCROW-POOL-1",
                bankAccount.getId(),
                amount,
                "INR",
                "d".repeat(64),
                "worker-1",
                Instant.now()
        );
        attempt.markSending(Instant.now());
        attempt.markAcknowledged("SETTLE-" + suffix, "Dispatched", Instant.now());
        attemptRepo.save(attempt);

        SettlementObservation observation = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(instruction.getId())
                .executionAttemptId(attempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-" + suffix)
                .providerOperationKey("OP-" + suffix)
                .providerSettlementId("SETTLE-" + suffix)
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-" + suffix)
                .observedAmount(amount)
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("e".repeat(64))
                .observedAt(Instant.now())
                .build();
        observationRepo.save(observation);

        return new TestItem(instruction, reservation, attempt, observation);
    }

    private static class TestItem {
        final ReleaseInstruction instruction;
        final ReleaseReservation reservation;
        final SettlementExecutionAttempt attempt;
        final SettlementObservation observation;

        TestItem(ReleaseInstruction instruction, ReleaseReservation reservation,
                 SettlementExecutionAttempt attempt, SettlementObservation observation) {
            this.instruction = instruction;
            this.reservation = reservation;
            this.attempt = attempt;
            this.observation = observation;
        }
    }

    @Test
    @DisplayName("PART V: 20 concurrent finalizeRelease calls for same release produce exactly one financial effect, one journal, one escrow release, and all callers succeed via idempotent replay")
    void testPartV_doubleFinalizationStorm20Calls() throws Exception {
        BigDecimal amount = new BigDecimal("50000.00");
        TestItem item = createSettledReadyFlow(amount, milestone);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<SettlementFinalizationResult>> futures = new ArrayList<>();
        AtomicInteger realWorkCount = new AtomicInteger(0);
        AtomicInteger replayCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                SettlementFinalizationResult res = finalizer.finalizeRelease(item.instruction.getId(), adminPrincipal);
                if (res.idempotentReplay()) {
                    replayCount.incrementAndGet();
                } else {
                    realWorkCount.incrementAndGet();
                }
                return res;
            }));
        }

        for (Future<SettlementFinalizationResult> f : futures) {
            SettlementFinalizationResult res = f.get(30, TimeUnit.SECONDS);
            assertThat(res.releaseInstructionId()).isEqualTo(item.instruction.getId());
        }
        executor.shutdown();

        // Invariant 1: Exactly one execution did real work; all others are idempotent replay
        assertThat(realWorkCount.get()).isEqualTo(1);
        assertThat(replayCount.get()).isEqualTo(threadCount - 1);

        // Invariant 2: ReleaseInstruction is SETTLED once with observation bound
        ReleaseInstruction settled = instructionRepo.findById(item.instruction.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        assertThat(settled.getFinalSettlementObservationId()).isEqualTo(item.observation.getId());
        assertThat(settled.getSettledAt()).isNotNull();

        // Invariant 3: Exactly one ReleaseReservation consumed
        ReleaseReservation res = reservationRepo.findByReleaseInstructionId(item.instruction.getId()).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
        assertThat(res.getConsumedAt()).isNotNull();

        // Invariant 4: Exactly one MILESTONE_SETTLEMENT journal entry
        List<JournalEntry> journals = journalEntryRepo.findByReferenceId(item.instruction.getId());
        assertThat(journals).hasSize(1);
        JournalEntry journal = journals.get(0);
        assertThat(journal.getTransactionType()).isEqualTo(LedgerTransactionType.MILESTONE_SETTLEMENT);

        // Invariant 5: Escrow balance decremented by 50,000, totalReleased incremented by 50,000
        EscrowAccount updatedEscrow = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(updatedEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(updatedEscrow.getTotalReleased()).isEqualByComparingTo(new BigDecimal("50000.00"));

        // Invariant 6: Exactly one EscrowLedger RELEASE entry
        List<EscrowLedger> escrowLedgerEntries = escrowLedgerRepo.findByEscrowAccountIdOrderByCreatedAtDesc(escrowAccount.getId());
        assertThat(escrowLedgerEntries).hasSize(1);
        assertThat(escrowLedgerEntries.get(0).getTransactionType()).isEqualTo(TransactionType.RELEASE);
        assertThat(escrowLedgerEntries.get(0).getAmount()).isEqualByComparingTo(amount);

        // Invariant 7: Zero provider calls
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("PART W: Finalization vs Reversal race - Reversal before finalizer blocks finalization; Reversal after finalizer leaves journal intact and flags REVERSAL_RECOVERY_REQUIRED")
    void testPartW_finalizationVsReversalRace() {
        BigDecimal amount = new BigDecimal("50000.00");

        // Case 1: Reversal observation exists BEFORE finalizer runs
        TestItem item1 = createSettledReadyFlow(amount, milestone);
        // Insert REVERSED observation for attempt
        SettlementObservation reversalObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(item1.instruction.getId())
                .executionAttemptId(item1.attempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("poll-reversal-" + UUID.randomUUID())
                .providerOperationKey(item1.attempt.getProviderOperationKey())
                .providerSettlementId(item1.attempt.getProviderSettlementId())
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("REVERSED")
                .finality(SettlementObservationFinality.REVERSED)
                .reasonCode("REVERSED_BY_BANK")
                .utrNumber("UTR-REV-1")
                .observedAmount(amount)
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("f".repeat(64))
                .observedAt(Instant.now().plusSeconds(10))
                .build();
        observationRepo.save(reversalObs);

        // Finalizer must fail closed and reject settling
        assertThatThrownBy(() -> finalizer.finalizeRelease(item1.instruction.getId(), adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFLICT");

        // Release remains PENDING_SETTLEMENT, reservation remains ACTIVE, 0 journals
        ReleaseInstruction ins1 = instructionRepo.findById(item1.instruction.getId()).orElseThrow();
        assertThat(ins1.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);
        assertThat(journalEntryRepo.findByReferenceId(item1.instruction.getId())).isEmpty();

        // Case 2: Finalizer commits FIRST, then a REVERSED observation arrives
        // Create milestone 2 for clean test flow
        Milestone m2 = new Milestone();
        m2.setDealId(deal.getId());
        m2.setTitle("M2");
        m2.setDescription("Desc");
        m2.setAmount(new BigDecimal("50000.0000"));
        m2.setStatus(MilestoneStatus.APPROVED);
        m2 = milestoneRepo.save(m2);

        TestItem item2 = createSettledReadyFlow(amount, m2);
        // Finalizer runs and settles successfully
        SettlementFinalizationResult res2 = finalizer.finalizeRelease(item2.instruction.getId(), adminPrincipal);
        assertThat(res2.idempotentReplay()).isFalse();

        // Now a subsequent REVERSED observation is recorded
        SettlementObservation reversalObs2 = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(item2.instruction.getId())
                .executionAttemptId(item2.attempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("poll-reversal-2-" + UUID.randomUUID())
                .providerOperationKey(item2.attempt.getProviderOperationKey())
                .providerSettlementId(item2.attempt.getProviderSettlementId())
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("REVERSED")
                .finality(SettlementObservationFinality.REVERSED)
                .reasonCode("REVERSED_LATER")
                .utrNumber("UTR-REV-2")
                .observedAmount(amount)
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("2".repeat(64))
                .observedAt(Instant.now().plusSeconds(20))
                .build();
        observationRepo.save(reversalObs2);

        // Evidence reader reports CONFLICT because FINAL_SUCCESS and REVERSED co-exist
        AuthoritativeObservationResult authResult = evidenceReader.getLatestAuthoritativeFinalObservation(item2.instruction.getId());
        assertThat(authResult.status()).isEqualTo(AuthoritativeStatus.CONFLICT);

        // Verify NO automatic compensating journal or balance mutation took place
        List<JournalEntry> journals = journalEntryRepo.findByReferenceId(item2.instruction.getId());
        assertThat(journals).hasSize(1); // Exactly the one original settlement journal, zero auto-reversals
        assertThat(journals.get(0).getTransactionType()).isEqualTo(LedgerTransactionType.MILESTONE_SETTLEMENT);

        ReleaseReservation resCheck = reservationRepo.findByReleaseInstructionId(item2.instruction.getId()).orElseThrow();
        assertThat(resCheck.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED); // Not unconsumed
    }

    @Test
    @DisplayName("PART X: Finalization transaction failure matrix - failure during finalization rolls back entire transaction cleanly")
    void testPartX_finalizationTransactionRollbackMatrix() {
        BigDecimal amount = new BigDecimal("50000.00");
        TestItem item = createSettledReadyFlow(amount, milestone);

        // Inject failure on milestone save
        doThrow(new RuntimeException("SIMULATED_MILESTONE_FAILURE"))
                .when(milestoneRepo).save(any(Milestone.class));

        assertThatThrownBy(() -> finalizer.finalizeRelease(item.instruction.getId(), adminPrincipal))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SIMULATED_MILESTONE_FAILURE");

        // Verify total rollback in PostgreSQL
        // 1. ReleaseInstruction remains PENDING_SETTLEMENT
        ReleaseInstruction ins = instructionRepo.findById(item.instruction.getId()).orElseThrow();
        assertThat(ins.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);
        assertThat(ins.getSettledAt()).isNull();
        assertThat(ins.getFinalSettlementObservationId()).isNull();

        // 2. Reservation remains ACTIVE
        ReleaseReservation res = reservationRepo.findByReleaseInstructionId(item.instruction.getId()).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(res.getConsumedAt()).isNull();

        // 3. Zero journal entries
        List<JournalEntry> journals = journalEntryRepo.findByReferenceId(item.instruction.getId());
        assertThat(journals).isEmpty();

        // 4. Escrow balance unchanged
        EscrowAccount escrow = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(escrow.getBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(escrow.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);

        // 5. Zero escrow ledger releases
        List<EscrowLedger> ledgerEntries = escrowLedgerRepo.findByEscrowAccountIdOrderByCreatedAtDesc(escrowAccount.getId());
        assertThat(ledgerEntries).isEmpty();

        // 6. Milestone not completed
        Milestone m = milestoneRepo.findById(milestone.getId()).orElseThrow();
        assertThat(m.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }

    @Test
    @DisplayName("PART AD: Escrow oversubscription stress - finalizing releases exceeding escrow balance fails closed with balance non-negative")
    void testPartAD_escrowOversubscriptionStress() {
        // Escrow balance = 100,000.
        // Release 1: 60,000 (succeeds -> balance becomes 40,000)
        // Release 2: 60,000 (fails closed because 60,000 > 40,000)
        BigDecimal reqAmount = new BigDecimal("60000.00");

        Milestone m1 = new Milestone();
        m1.setDealId(deal.getId());
        m1.setTitle("Oversub-M1");
        m1.setDescription("Desc");
        m1.setAmount(reqAmount);
        m1.setStatus(MilestoneStatus.APPROVED);
        m1 = milestoneRepo.save(m1);

        Milestone m2 = new Milestone();
        m2.setDealId(deal.getId());
        m2.setTitle("Oversub-M2");
        m2.setDescription("Desc");
        m2.setAmount(reqAmount);
        m2.setStatus(MilestoneStatus.APPROVED);
        m2 = milestoneRepo.save(m2);

        TestItem flow1 = createSettledReadyFlow(reqAmount, m1);
        TestItem flow2 = createSettledReadyFlow(reqAmount, m2);

        // 1. Finalize flow1: succeeds
        SettlementFinalizationResult res1 = finalizer.finalizeRelease(flow1.instruction.getId(), adminPrincipal);
        assertThat(res1.idempotentReplay()).isFalse();

        EscrowAccount escrowAfter1 = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(escrowAfter1.getBalance()).isEqualByComparingTo(new BigDecimal("40000.00"));
        assertThat(escrowAfter1.getTotalReleased()).isEqualByComparingTo(reqAmount);

        // 2. Finalize flow2: must fail closed because required (60,000) > available (40,000)
        assertThatThrownBy(() -> finalizer.finalizeRelease(flow2.instruction.getId(), adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient escrow balance");

        // 3. Invariants verified:
        // Escrow balance is non-negative and untouched by the failed second finalization
        EscrowAccount escrowAfter2 = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(escrowAfter2.getBalance()).isEqualByComparingTo(new BigDecimal("40000.00"));
        assertThat(escrowAfter2.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(escrowAfter2.getTotalReleased()).isLessThanOrEqualTo(escrowAfter2.getTotalFunded());

        // Flow2 release remains PENDING_SETTLEMENT, reservation remains ACTIVE, 0 journal entries for flow2
        ReleaseInstruction ins2 = instructionRepo.findById(flow2.instruction.getId()).orElseThrow();
        assertThat(ins2.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);
        ReleaseReservation res2 = reservationRepo.findByReleaseInstructionId(flow2.instruction.getId()).orElseThrow();
        assertThat(res2.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(journalEntryRepo.findByReferenceId(flow2.instruction.getId())).isEmpty();
    }

    @Test
    @DisplayName("PARTS AE & AF: Ledger conservation and escrow/ledger consistency across finalization")
    void testPartsAE_and_AF_ledgerConservationAndEscrowConsistency() {
        BigDecimal amount = new BigDecimal("50000.00");
        TestItem item = createSettledReadyFlow(amount, milestone);

        // Pre-finalization checks
        EscrowAccount escrowBefore = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        BigDecimal balanceBefore = escrowBefore.getBalance();
        BigDecimal releasedBefore = escrowBefore.getTotalReleased();

        // Execute finalization
        SettlementFinalizationResult result = finalizer.finalizeRelease(item.instruction.getId(), adminPrincipal);
        assertThat(result.idempotentReplay()).isFalse();

        // PART AE: Ledger conservation
        List<JournalEntry> journals = journalEntryRepo.findByReferenceId(item.instruction.getId());
        assertThat(journals).hasSize(1);
        JournalEntry journal = journals.get(0);

        List<JournalLine> lines = journalLineRepo.findByJournalEntryIdOrderBySequenceAsc(journal.getId());
        BigDecimal totalDebit = lines.stream()
                .filter(l -> l.getEntryType() == EntryType.DEBIT)
                .map(JournalLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = lines.stream()
                .filter(l -> l.getEntryType() == EntryType.CREDIT)
                .map(JournalLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // sum(DEBIT) == sum(CREDIT)
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isEqualByComparingTo(amount);

        // Check specific accounts: Dr 2010 Escrow Liability, Cr 1010 Escrow Pool
        JournalLine debitLine = lines.stream().filter(l -> l.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        JournalLine creditLine = lines.stream().filter(l -> l.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        LedgerAccount debitAcc = ledgerAccountRepo.findById(debitLine.getLedgerAccountId()).orElseThrow();
        LedgerAccount creditAcc = ledgerAccountRepo.findById(creditLine.getLedgerAccountId()).orElseThrow();
        assertThat(debitAcc.getAccountNumber()).isEqualTo(LedgerService.ACC_ESCROW_LIABILITY);
        assertThat(debitLine.getAmount()).isEqualByComparingTo(amount);
        assertThat(creditAcc.getAccountNumber()).isEqualTo(LedgerService.ACC_ESCROW_POOL);
        assertThat(creditLine.getAmount()).isEqualByComparingTo(amount);

        // PART AF: Escrow / ledger consistency
        EscrowAccount escrowAfter = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        BigDecimal releasedDelta = escrowAfter.getTotalReleased().subtract(releasedBefore);
        BigDecimal balanceDelta = balanceBefore.subtract(escrowAfter.getBalance());

        List<EscrowLedger> escrowLedgers = escrowLedgerRepo.findByEscrowAccountIdOrderByCreatedAtDesc(escrowAccount.getId());
        assertThat(escrowLedgers).hasSize(1);
        EscrowLedger releaseLedger = escrowLedgers.get(0);

        ReleaseReservation consumedRes = reservationRepo.findByReleaseInstructionId(item.instruction.getId()).orElseThrow();

        // Assert: escrow totalReleased delta == MILESTONE_SETTLEMENT journal amount == EscrowLedger RELEASE amount == ReleaseInstruction amount == consumed reservation amount
        assertThat(releasedDelta).isEqualByComparingTo(amount);
        assertThat(balanceDelta).isEqualByComparingTo(amount);
        assertThat(journal.getTotalDebit()).isEqualByComparingTo(amount);
        assertThat(releaseLedger.getAmount()).isEqualByComparingTo(amount);
        assertThat(item.instruction.getAmount()).isEqualByComparingTo(amount);
        assertThat(consumedRes.getAmount()).isEqualByComparingTo(amount);
    }
}
