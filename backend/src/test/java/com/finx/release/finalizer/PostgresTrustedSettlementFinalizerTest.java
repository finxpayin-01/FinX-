package com.finx.release.finalizer;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.entity.JournalLine;
import com.finx.ledger.entity.LedgerAccount;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
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
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e7_test}",
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
public class PostgresTrustedSettlementFinalizerTest {

    static {
        ensureDatabaseExists("finx_release_e7_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrustedSettlementFinalizer finalizer;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowAccountRepo;
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
    @Autowired private PlatformTransactionManager transactionManager;

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
        } catch (SQLException e) {
            // Already exists
        }
    }

    private EngineDecision createDecision(UUID orgId, UUID milestoneId, EnginePolicyVersion policy, UUID actorId) {
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
            var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(decision, policy.getId());
            var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(decision, policy.getContentHash());
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

    @BeforeEach
    void setUp() {
        jdbc.execute("UPDATE release_instructions SET status = 'PENDING_SETTLEMENT', settled_at = NULL, final_settlement_observation_id = NULL");
        jdbc.execute("DELETE FROM journal_lines");
        jdbc.execute("DELETE FROM journal_entries");
        jdbc.execute("DELETE FROM escrow_ledger");
        jdbc.execute("DELETE FROM settlement_observations");
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");
        jdbc.execute("DELETE FROM engine_decisions");
        jdbc.execute("DELETE FROM engine_policy_versions");
        jdbc.execute("DELETE FROM milestones");
        jdbc.execute("DELETE FROM escrow_accounts");
        jdbc.execute("DELETE FROM deals");

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
        escrowAccount = escrowAccountRepo.save(escrowAccount);

        // Pre-fund ledger accounts: Dr 1010-ESCROW-POOL, Cr 2010-ESCROW-LIABILITY
        fundLedger(deal.getId(), new BigDecimal("100000.00"));
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

    private ReleaseInstruction createAndPersistFullFlow(BigDecimal amount, Milestone targetMilestone) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        EngineDecision decision = createDecision(testOrg.getId(), targetMilestone.getId(), defaultPolicy, adminUser.getId());
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

        return instruction;
    }

    // Part AY: Happy Path Postgres settlement finalization
    @Test
    @DisplayName("Part AY: Happy path executes atomic settlement finalization in real PostgreSQL")
    void testHappyPathPostgres() {
        ReleaseInstruction instruction = createAndPersistFullFlow(new BigDecimal("50000.00"), milestone);

        SettlementFinalizationResult result = finalizer.finalizeRelease(instruction.getId(), adminPrincipal);

        assertThat(result).isNotNull();
        assertThat(result.releaseInstructionId()).isEqualTo(instruction.getId());
        assertThat(result.idempotentReplay()).isFalse();

        // ReleaseInstruction: SETTLED with observation binding
        ReleaseInstruction settled = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        assertThat(settled.getFinalSettlementObservationId()).isNotNull();
        assertThat(settled.getFinalizedBy()).isEqualTo(adminUser.getId());
        assertThat(settled.getSettledAt()).isNotNull();

        // ReleaseReservation: CONSUMED
        ReleaseReservation consumedRes = reservationRepo.findByReleaseInstructionId(instruction.getId()).orElseThrow();
        assertThat(consumedRes.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
        assertThat(consumedRes.getConsumedAt()).isNotNull();

        // EscrowAccount: balance decremented by 50,000, totalReleased incremented by 50,000
        EscrowAccount updatedEscrow = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(updatedEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(updatedEscrow.getTotalReleased()).isEqualByComparingTo(new BigDecimal("50000.00"));

        // EscrowLedger: exactly 1 RELEASE row
        List<EscrowLedger> ledgers = escrowLedgerRepo.findAll();
        assertThat(ledgers).hasSize(1);
        EscrowLedger releaseLedger = ledgers.get(0);
        assertThat(releaseLedger.getTransactionType()).isEqualTo(TransactionType.RELEASE);
        assertThat(releaseLedger.getMilestoneId()).isEqualTo(milestone.getId());
        assertThat(releaseLedger.getAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(releaseLedger.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("50000.00"));

        // JournalEntry: exactly 1 MILESTONE_SETTLEMENT
        JournalEntry journal = journalEntryRepo.findByTransactionTypeAndReferenceId(LedgerTransactionType.MILESTONE_SETTLEMENT, instruction.getId()).orElseThrow();
        assertThat(journal.getTotalDebit()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(journal.getTotalCredit()).isEqualByComparingTo(new BigDecimal("50000.00"));

        // JournalLines: 2 balanced lines (Dr 2010, Cr 1010)
        List<JournalLine> lines = journalLineRepo.findByJournalEntryIdOrderBySequenceAsc(journal.getId());
        assertThat(lines).hasSize(2);
        JournalLine debitLine = lines.stream().filter(l -> l.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        JournalLine creditLine = lines.stream().filter(l -> l.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        LedgerAccount debitAcc = ledgerAccountRepo.findById(debitLine.getLedgerAccountId()).orElseThrow();
        LedgerAccount creditAcc = ledgerAccountRepo.findById(creditLine.getLedgerAccountId()).orElseThrow();
        assertThat(debitAcc.getAccountNumber()).isEqualTo(LedgerService.ACC_ESCROW_LIABILITY);
        assertThat(debitLine.getAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(creditAcc.getAccountNumber()).isEqualTo(LedgerService.ACC_ESCROW_POOL);
        assertThat(creditLine.getAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));

        // Milestone: COMPLETED
        Milestone completedMilestone = milestoneRepo.findById(milestone.getId()).orElseThrow();
        assertThat(completedMilestone.getStatus()).isEqualTo(MilestoneStatus.COMPLETED);

        // Deal: COMPLETED (since only 1 milestone exists and it is completed)
        Deal updatedDeal = dealRepo.findById(deal.getId()).orElseThrow();
        assertThat(updatedDeal.getStatus()).isEqualTo(DealStatus.COMPLETED);

        // SettlementInstruction writes: 0 (table has 0 rows)
        Integer settlementInstructionCount = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_instructions", Integer.class);
        assertThat(settlementInstructionCount).isEqualTo(0);
    }

    // Part AZ: Journal Balance Conservation
    @Test
    @DisplayName("Part AZ: Journal balances conserve perfectly: Escrow Pool and Liability decrease by release amount; Vendor Payable and Bank Nodal untouched")
    void testJournalBalanceConservation() {
        ReleaseInstruction instruction = createAndPersistFullFlow(new BigDecimal("70000.00"), milestone);

        finalizer.finalizeRelease(instruction.getId(), adminPrincipal);

        LedgerAccount pool = ledgerAccountRepo.findByAccountNumber(LedgerService.ACC_ESCROW_POOL).orElseThrow();
        LedgerAccount liability = ledgerAccountRepo.findByAccountNumber(LedgerService.ACC_ESCROW_LIABILITY).orElseThrow();
        LedgerAccount vendorPayable = ledgerAccountRepo.findByAccountNumber(LedgerService.ACC_VENDOR_PAYABLE).orElseThrow();
        LedgerAccount bankNodal = ledgerAccountRepo.findByAccountNumber(LedgerService.ACC_BANK_NODAL).orElseThrow();

        // Asset balance = debits - credits: 100,000 - 70,000 = 30,000
        assertThat(pool.getBalance()).isEqualByComparingTo(new BigDecimal("30000.00"));
        // Liability balance = credits - debits: 100,000 - 70,000 = 30,000
        assertThat(liability.getBalance()).isEqualByComparingTo(new BigDecimal("30000.00"));

        // Vendor Payable untouched
        assertThat(vendorPayable.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        // Bank Nodal untouched
        assertThat(bankNodal.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Part BA: Idempotent Replay in PostgreSQL
    @Test
    @DisplayName("Part BA: Calling finalizer twice produces identical result with 0 additional ledger or escrow writes")
    void testIdempotencyPostgres() {
        ReleaseInstruction instruction = createAndPersistFullFlow(new BigDecimal("50000.00"), milestone);

        // First call
        SettlementFinalizationResult firstResult = finalizer.finalizeRelease(instruction.getId(), adminPrincipal);
        assertThat(firstResult.idempotentReplay()).isFalse();

        int journalCountAfterFirst = journalEntryRepo.findAll().size();
        int escrowLedgerCountAfterFirst = escrowLedgerRepo.findAll().size();
        BigDecimal balanceAfterFirst = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow().getBalance();
        BigDecimal totalReleasedAfterFirst = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow().getTotalReleased();

        // Second call
        SettlementFinalizationResult secondResult = finalizer.finalizeRelease(instruction.getId(), adminPrincipal);
        assertThat(secondResult.idempotentReplay()).isTrue();
        assertThat(secondResult.journalEntryId()).isEqualTo(firstResult.journalEntryId());
        assertThat(secondResult.escrowLedgerId()).isEqualTo(firstResult.escrowLedgerId());

        int journalCountAfterSecond = journalEntryRepo.findAll().size();
        int escrowLedgerCountAfterSecond = escrowLedgerRepo.findAll().size();
        BigDecimal balanceAfterSecond = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow().getBalance();
        BigDecimal totalReleasedAfterSecond = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow().getTotalReleased();

        assertThat(journalCountAfterSecond).isEqualTo(journalCountAfterFirst);
        assertThat(escrowLedgerCountAfterSecond).isEqualTo(escrowLedgerCountAfterFirst);
        assertThat(balanceAfterSecond).isEqualByComparingTo(balanceAfterFirst);
        assertThat(totalReleasedAfterSecond).isEqualByComparingTo(totalReleasedAfterFirst);
    }

    // Part BB: Concurrent Finalization Same Release
    @Test
    @DisplayName("Part BB: Concurrent same release execution locks safely with exactly one financial effect")
    void testConcurrentSameRelease() throws Exception {
        ReleaseInstruction instruction = createAndPersistFullFlow(new BigDecimal("50000.00"), milestone);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<SettlementFinalizationResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return finalizer.finalizeRelease(instruction.getId(), adminPrincipal);
            }));
        }

        List<SettlementFinalizationResult> results = new ArrayList<>();
        for (Future<SettlementFinalizationResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // One was fresh finalization, one was idempotent replay
        long freshCount = results.stream().filter(r -> !r.idempotentReplay()).count();
        long replayCount = results.stream().filter(SettlementFinalizationResult::idempotentReplay).count();
        assertThat(freshCount).isEqualTo(1);
        assertThat(replayCount).isEqualTo(1);

        // Financial balances mutated exactly once
        EscrowAccount escrow = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(escrow.getBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(escrow.getTotalReleased()).isEqualByComparingTo(new BigDecimal("50000.00"));

        List<EscrowLedger> releaseLedgers = escrowLedgerRepo.findAll();
        assertThat(releaseLedgers).hasSize(1);

        List<JournalEntry> settlements = journalEntryRepo.findAll().stream()
                .filter(j -> LedgerTransactionType.MILESTONE_SETTLEMENT.equals(j.getTransactionType()))
                .toList();
        assertThat(settlements).hasSize(1);
    }

    // Part BC: Concurrent Different Milestones Same Escrow
    @Test
    @DisplayName("Part BC: Concurrent different milestones on same escrow serialize; if second lacks funds, first succeeds and second fails safely")
    void testConcurrentDifferentMilestonesSameEscrow() throws Exception {
        // Escrow has 80,000 INR
        escrowAccount.setBalance(new BigDecimal("80000.00"));
        escrowAccountRepo.save(escrowAccount);

        // Milestone 1 for 50,000
        ReleaseInstruction inst1 = createAndPersistFullFlow(new BigDecimal("50000.00"), milestone);

        // Create Milestone 2 for 50,000
        Milestone milestone2 = new Milestone();
        milestone2.setDealId(deal.getId());
        milestone2.setTitle("M2");
        milestone2.setDescription("Desc 2");
        milestone2.setAmount(new BigDecimal("50000.0000"));
        milestone2.setStatus(MilestoneStatus.APPROVED);
        milestone2 = milestoneRepo.save(milestone2);

        ReleaseInstruction inst2 = createAndPersistFullFlow(new BigDecimal("50000.00"), milestone2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<?> f1 = executor.submit(() -> {
            try {
                barrier.await();
                return finalizer.finalizeRelease(inst1.getId(), adminPrincipal);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                barrier.await();
                return finalizer.finalizeRelease(inst2.getId(), adminPrincipal);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (Future<?> f : List.of(f1, f2)) {
            try {
                f.get(10, TimeUnit.SECONDS);
                successCount.incrementAndGet();
            } catch (ExecutionException e) {
                failureCount.incrementAndGet();
                assertThat(e.getCause().getMessage()).contains("Insufficient escrow balance");
            }
        }
        executor.shutdown();

        // Exactly one succeeded, one failed
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        // Escrow balance: 80,000 - 50,000 = 30,000 (never negative!)
        EscrowAccount updatedEscrow = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(updatedEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(updatedEscrow.getTotalReleased()).isEqualByComparingTo(new BigDecimal("50000.00"));

        // Exactly one journal entry and one escrow release
        List<JournalEntry> settlements = journalEntryRepo.findAll().stream()
                .filter(j -> LedgerTransactionType.MILESTONE_SETTLEMENT.equals(j.getTransactionType()))
                .toList();
        assertThat(settlements).hasSize(1);
        assertThat(escrowLedgerRepo.findAll()).hasSize(1);
    }

    // Part BD: Rollback Test
    @Test
    @DisplayName("Part BD: Transaction rollback on failure leaves zero partial financial or entity writes")
    void testAtomicRollbackOnFailure() {
        ReleaseInstruction instruction = createAndPersistFullFlow(new BigDecimal("50000.00"), milestone);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        try {
            txTemplate.execute(status -> {
                finalizer.finalizeRelease(instruction.getId(), adminPrincipal);
                throw new RuntimeException("Simulated mid-finalization failure");
            });
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Simulated mid-finalization failure");
        }

        // Verify everything rolled back completely
        ReleaseInstruction rolledBackInst = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(rolledBackInst.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);
        assertThat(rolledBackInst.getFinalSettlementObservationId()).isNull();

        ReleaseReservation rolledBackRes = reservationRepo.findByReleaseInstructionId(instruction.getId()).orElseThrow();
        assertThat(rolledBackRes.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        EscrowAccount rolledBackEscrow = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(rolledBackEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(rolledBackEscrow.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);

        List<EscrowLedger> ledgers = escrowLedgerRepo.findAll();
        assertThat(ledgers).isEmpty();

        List<JournalEntry> settlements = journalEntryRepo.findAll().stream()
                .filter(j -> LedgerTransactionType.MILESTONE_SETTLEMENT.equals(j.getTransactionType()))
                .toList();
        assertThat(settlements).isEmpty();
    }
}
