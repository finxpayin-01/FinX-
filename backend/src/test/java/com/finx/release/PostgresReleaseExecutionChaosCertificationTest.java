package com.finx.release;

import com.finx.banking.dto.BankingDtos.SettlementRequest;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.dto.BankingDtos.SettlementStatusResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
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
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.entity.JournalLine;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.JournalLineRepository;
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
import com.finx.outbox.service.FinancialOutboxLeaseService;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeStatus;
import com.finx.release.observation.service.SettlementObservationService;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
public class PostgresReleaseExecutionChaosCertificationTest {

    static {
        ensureDatabaseExists("finx_release_e9_test");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FinxClock clock;

    @Autowired private TrustedSettlementFinalizer finalizer;
    @Autowired private SettlementExecutionAttemptService attemptService;
    @Autowired private FinancialOutboxLeaseService leaseService;
    @Autowired private SettlementObservationService observationService;
    @Autowired private SettlementObservationEvidenceReader evidenceReader;
    @Autowired private MockBankingProvider mockBankingProvider;
    @Autowired private LedgerService ledgerService;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private EscrowLedgerRepository escrowLedgerRepo;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;

    private User adminUser;
    private UserPrincipal adminPrincipal;
    private Organization testOrg;
    private OrganizationBankAccount bankAccount;
    private Deal deal;
    private EscrowAccount escrowAccount;
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
        jdbc.execute("DELETE FROM organization_members");
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
        deal.setTitle("Chaos Deal " + suffix);
        deal.setDescription("Test deal");
        deal.setBuyerId(adminUser.getId());
        deal.setSellerId(adminUser.getId());
        deal.setBuyerOrganizationId(testOrg.getId());
        deal.setSellerOrganizationId(testOrg.getId());
        deal.setTotalAmount(new BigDecimal("1000000.0000"));
        deal.setCurrency("INR");
        deal.setStatus(DealStatus.FUNDED);
        deal = dealRepo.save(deal);

        escrowAccount = new EscrowAccount();
        escrowAccount.setDealId(deal.getId());
        escrowAccount.setCurrency("INR");
        escrowAccount.setBalance(new BigDecimal("1000000.00"));
        escrowAccount.setTotalFunded(new BigDecimal("1000000.00"));
        escrowAccount.setTotalReleased(BigDecimal.ZERO);
        escrowAccount = escrowRepo.save(escrowAccount);

        fundLedger(deal.getId(), new BigDecimal("1000000.00"));
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

    private TestFlow createTestFlow(BigDecimal amount) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();

        Milestone m = new Milestone();
        m.setDealId(deal.getId());
        m.setTitle("Chaos-M-" + suffix);
        m.setDescription("Desc");
        m.setAmount(amount);
        m.setStatus(MilestoneStatus.APPROVED);
        m = milestoneRepo.save(m);

        EngineDecision decision = createDecision(testOrg.getId(), m.getId());

        ReleaseInstruction instruction = new ReleaseInstruction(
                UUID.randomUUID(), testOrg.getId(), deal.getId(), m.getId(), decision.getId(),
                decision.getDecisionFingerprint(), defaultPolicy.getId(), defaultPolicy.getContentHash(),
                bankAccount.getId(), "MOCK", "ESCROW-POOL-1", amount, "INR",
                "idem-" + suffix, "a".repeat(64), adminUser.getId()
        );
        instruction.markAuthorized(adminUser.getId(), now);
        instruction.markReserved();
        instruction.markQueued();
        instruction = instructionRepo.save(instruction);

        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(), instruction.getId(), escrowAccount.getId(), m.getId(), amount, "INR"
        );
        reservation = reservationRepo.save(reservation);

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(), instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now.minusSeconds(5), now, now
        );
        outbox = outboxRepo.save(outbox);

        return new TestFlow(instruction, reservation, outbox, m);
    }

    private static class TestFlow {
        final ReleaseInstruction instruction;
        final ReleaseReservation reservation;
        final FinancialOutboxMessage outbox;
        final Milestone milestone;

        TestFlow(ReleaseInstruction instruction, ReleaseReservation reservation, FinancialOutboxMessage outbox, Milestone milestone) {
            this.instruction = instruction;
            this.reservation = reservation;
            this.outbox = outbox;
            this.milestone = milestone;
        }
    }

    private void assertGlobalInvariants() {
        // Invariant 1: For each ReleaseInstruction:
        for (ReleaseInstruction ins : instructionRepo.findAll()) {
            // At most 1 active reservation
            List<ReleaseReservation> activeRes = reservationRepo.findAll().stream()
                    .filter(r -> r.getReleaseInstructionId().equals(ins.getId()) && r.getStatus() == ReleaseReservationStatus.ACTIVE)
                    .toList();
            assertThat(activeRes.size()).isLessThanOrEqualTo(1);

            // At most 1 consumed reservation
            List<ReleaseReservation> consumedRes = reservationRepo.findAll().stream()
                    .filter(r -> r.getReleaseInstructionId().equals(ins.getId()) && r.getStatus() == ReleaseReservationStatus.CONSUMED)
                    .toList();
            assertThat(consumedRes.size()).isLessThanOrEqualTo(1);

            // If SETTLED, must have observation bound and settledAt
            if (ins.getStatus() == ReleaseInstructionStatus.SETTLED) {
                assertThat(ins.getFinalSettlementObservationId()).isNotNull();
                assertThat(ins.getSettledAt()).isNotNull();
                assertThat(ins.getFinalizedBy()).isNotNull();
            }

            // At most 1 MILESTONE_SETTLEMENT journal entry
            List<JournalEntry> journals = journalEntryRepo.findByReferenceId(ins.getId());
            assertThat(journals.size()).isLessThanOrEqualTo(1);
        }

        // Invariant 2: For each escrow account: balance >= 0
        for (EscrowAccount esc : escrowRepo.findAll()) {
            assertThat(esc.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(esc.getTotalReleased()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        // Invariant 3: For each journal entry: sum(debit) == sum(credit)
        for (JournalEntry j : journalEntryRepo.findAll()) {
            assertThat(j.getTotalDebit()).isEqualByComparingTo(j.getTotalCredit());
        }
    }

    @Test
    @DisplayName("PART AW & PART Y: 50 deterministic seeded chaos loop iterations certifying lock ordering, crash recovery, and global financial invariants")
    void testPartAW_chaosLoop50Iterations() throws Exception {
        Random rng = new Random(42L); // Deterministic seed
        BigDecimal amount = new BigDecimal("10000.00");

        for (int i = 0; i < 50; i++) {
            clock.reset();
            int scenario = rng.nextInt(6);

            switch (scenario) {
                case 0 -> {
                    // Scenario 0: Concurrent claim storm on outbox
                    TestFlow flow = createTestFlow(amount);
                    int workers = 4;
                    ExecutorService exec = Executors.newFixedThreadPool(workers);
                    CyclicBarrier barrier = new CyclicBarrier(workers);
                    AtomicInteger claims = new AtomicInteger(0);

                    for (int w = 0; w < workers; w++) {
                        final String workerId = "chaos-worker-" + i + "-" + w;
                        exec.submit(() -> {
                            try {
                                barrier.await(5, TimeUnit.SECONDS);
                                List<FinancialOutboxMessage> claimed = leaseService.claimBatch(workerId, 1);
                                if (!claimed.isEmpty()) {
                                    claims.incrementAndGet();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                    }
                    exec.shutdown();
                    exec.awaitTermination(5, TimeUnit.SECONDS);

                    // Exactly one worker claimed the outbox message
                    assertThat(claims.get()).isEqualTo(1);

                    FinancialOutboxMessage outboxMsg0 = outboxRepo.findById(flow.outbox.getId()).orElseThrow();
                    if (outboxMsg0.getStatus() == FinancialOutboxStatus.LEASED) {
                        outboxMsg0.markProcessed(clock.now(), null);
                        outboxRepo.save(outboxMsg0);
                    }
                }

                case 1 -> {
                    // Scenario 1: Worker lease timeout & recovery past timeout
                    TestFlow flow = createTestFlow(amount);
                    String worker1 = "worker-crash-" + i;
                    List<FinancialOutboxMessage> claimed = leaseService.claimBatch(worker1, 1);
                    assertThat(claimed).hasSize(1);

                    // Clock advances past 60s lease timeout
                    Instant advanceTime = clock.now().plusSeconds(70);
                    clock.setFixed(advanceTime);

                    List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                            Duration.ofSeconds(60), Duration.ofSeconds(5), 10
                    );
                    assertThat(recovered.stream().map(FinancialOutboxMessage::getId)).contains(flow.outbox.getId());

                    // Advance past retry delay
                    clock.setFixed(advanceTime.plusSeconds(10));
                    String worker2 = "worker-recover-" + i;
                    List<FinancialOutboxMessage> reClaimed = leaseService.claimBatch(worker2, 1);
                    assertThat(reClaimed).hasSize(1);
                    assertThat(reClaimed.get(0).getId()).isEqualTo(flow.outbox.getId());

                    FinancialOutboxMessage outboxMsg1 = outboxRepo.findById(flow.outbox.getId()).orElseThrow();
                    if (outboxMsg1.getStatus() == FinancialOutboxStatus.LEASED) {
                        outboxMsg1.markProcessed(clock.now(), null);
                        outboxRepo.save(outboxMsg1);
                    }
                }

                case 2 -> {
                    // Scenario 2: Worker prepare, then crash, then recovery reuses same PREPARED attempt
                    TestFlow flow = createTestFlow(amount);
                    String worker1 = "worker-prep-crash-" + i;
                    List<FinancialOutboxMessage> claimed = leaseService.claimBatch(worker1, 1);
                    SettlementExecutionAttempt attempt = attemptService.prepareAttempt(flow.outbox.getId(), worker1);
                    assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);

                    // Crash: clock advances past timeout
                    Instant advanceTime = clock.now().plusSeconds(70);
                    clock.setFixed(advanceTime);

                    leaseService.recoverExpiredPreDispatchLeases(Duration.ofSeconds(60), Duration.ofSeconds(5), 10);
                    clock.setFixed(advanceTime.plusSeconds(10));

                    String worker2 = "worker-prep-reclaim-" + i;
                    List<FinancialOutboxMessage> reClaimed = leaseService.claimBatch(worker2, 1);
                    assertThat(reClaimed).hasSize(1);
                    assertThat(reClaimed.get(0).getId()).isEqualTo(flow.outbox.getId());
                    SettlementExecutionAttempt reused = attemptService.prepareAttempt(flow.outbox.getId(), worker2);

                    // Reused attempt is identical
                    assertThat(reused.getId()).isEqualTo(attempt.getId());
                    assertThat(reused.getProviderOperationKey()).isEqualTo(attempt.getProviderOperationKey());

                    FinancialOutboxMessage outboxMsg2 = outboxRepo.findById(flow.outbox.getId()).orElseThrow();
                    if (outboxMsg2.getStatus() == FinancialOutboxStatus.LEASED) {
                        outboxMsg2.markProcessed(clock.now(), null);
                        outboxRepo.save(outboxMsg2);
                    }
                }

                case 3 -> {
                    // Scenario 3: Mock provider idempotency under 5 concurrent calls
                    String opKey = "CHAOS-OP-" + i + "-" + UUID.randomUUID();
                    SettlementRequest req = new SettlementRequest(
                            "REF-" + opKey,
                            deal.getId(),
                            UUID.randomUUID(),
                            amount,
                            "INR",
                            "Beneficiary " + i,
                            "9876543210" + (i % 10),
                            bankAccount.getIfscCode(),
                            opKey
                    );

                    int threads = 5;
                    ExecutorService exec = Executors.newFixedThreadPool(threads);
                    CyclicBarrier barrier = new CyclicBarrier(threads);
                    List<Future<SettlementResult>> futures = new ArrayList<>();

                    for (int t = 0; t < threads; t++) {
                        futures.add(exec.submit(() -> {
                            barrier.await(5, TimeUnit.SECONDS);
                            return mockBankingProvider.initiateSettlement(req);
                        }));
                    }
                    exec.shutdown();

                    Set<String> settlementIds = new HashSet<>();
                    Set<String> utrs = new HashSet<>();
                    for (Future<SettlementResult> f : futures) {
                        SettlementResult res = f.get(5, TimeUnit.SECONDS);
                        settlementIds.add(res.providerSettlementId());
                        utrs.add(res.utrNumber());
                    }
                    // All 5 callers converged on same settlementId and UTR
                    assertThat(settlementIds).hasSize(1);
                    assertThat(utrs).hasSize(1);
                }

                case 4 -> {
                    // Scenario 4: Concurrent finalizer storm on settled-ready release
                    TestFlow flow = createTestFlow(amount);
                    // Mark flow ready for settlement
                    ReleaseInstruction ins = flow.instruction;
                    ins.markDispatching();
                    ins.markPendingSettlement();
                    instructionRepo.save(ins);

                    flow.outbox.markLeased("worker-s4-" + i, clock.now());
                    flow.outbox.markProcessed(clock.now(), null);
                    outboxRepo.save(flow.outbox);

                    String opKey = ProviderSettlementOperationKey.release(ins.getId());
                    SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                            UUID.randomUUID(), ins.getId(), flow.outbox.getId(), 1, opKey, opKey,
                            "MOCK", "ESCROW-POOL-1", bankAccount.getId(), amount, "INR", "d".repeat(64),
                            "worker", Instant.now()
                    );
                    attempt.markSending(Instant.now());
                    attempt.markAcknowledged("SETTLE-" + i, "Dispatched", Instant.now());
                    attempt = attemptRepo.save(attempt);

                    SettlementObservation obs = SettlementObservation.builder()
                            .id(UUID.randomUUID())
                            .releaseInstructionId(ins.getId())
                            .executionAttemptId(attempt.getId())
                            .provider("MOCK")
                            .source(SettlementObservationSource.WEBHOOK)
                            .sourceReference("chaos-webhook-" + i)
                            .providerOperationKey(opKey)
                            .providerSettlementId("SETTLE-" + i)
                            .providerAccountId("ESCROW-POOL-1")
                            .providerStatus("SUCCESS")
                            .finality(SettlementObservationFinality.FINAL_SUCCESS)
                            .reasonCode("SUCCESS")
                            .utrNumber("UTR-CHAOS-" + i)
                            .observedAmount(amount)
                            .observedCurrency("INR")
                            .observedDirection("DEBIT")
                            .evidenceHash("3".repeat(64))
                            .observedAt(Instant.now())
                            .build();
                    observationRepo.save(obs);

                    // 5 concurrent finalizer calls
                    int fThreads = 5;
                    ExecutorService exec = Executors.newFixedThreadPool(fThreads);
                    CyclicBarrier barrier = new CyclicBarrier(fThreads);
                    List<Future<?>> fList = new ArrayList<>();

                    for (int t = 0; t < fThreads; t++) {
                        fList.add(exec.submit(() -> {
                            barrier.await(5, TimeUnit.SECONDS);
                            return finalizer.finalizeRelease(ins.getId(), adminPrincipal);
                        }));
                    }
                    exec.shutdown();
                    for (Future<?> f : fList) {
                        f.get(10, TimeUnit.SECONDS);
                    }

                    // Release settled exactly once
                    ReleaseInstruction settled = instructionRepo.findById(ins.getId()).orElseThrow();
                    assertThat(settled.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
                }

                case 5 -> {
                    // Scenario 5: Reversal observation surfaces CONFLICT
                    TestFlow flow = createTestFlow(amount);
                    flow.outbox.markLeased("worker-s5-" + i, clock.now());
                    flow.outbox.markProcessed(clock.now(), null);
                    outboxRepo.save(flow.outbox);

                    ReleaseInstruction ins = flow.instruction;
                    ins.markDispatching();
                    ins.markPendingSettlement();
                    instructionRepo.save(ins);

                    String opKey = ProviderSettlementOperationKey.release(ins.getId());
                    SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                            UUID.randomUUID(), ins.getId(), flow.outbox.getId(), 1, opKey, opKey,
                            "MOCK", "ESCROW-POOL-1", bankAccount.getId(), amount, "INR", "d".repeat(64),
                            "worker", Instant.now()
                    );
                    attempt = attemptRepo.save(attempt);

                    // Save FINAL_SUCCESS
                    observationRepo.save(SettlementObservation.builder()
                            .id(UUID.randomUUID()).releaseInstructionId(ins.getId()).executionAttemptId(attempt.getId())
                            .provider("MOCK").source(SettlementObservationSource.WEBHOOK).sourceReference("wh-" + i)
                            .providerOperationKey(opKey).providerSettlementId("S-" + i).providerAccountId("ESCROW-POOL-1")
                            .providerStatus("SUCCESS").finality(SettlementObservationFinality.FINAL_SUCCESS)
                            .reasonCode("SUCCESS").utrNumber("UTR-" + i).observedAmount(amount).observedCurrency("INR")
                            .observedDirection("DEBIT").evidenceHash("4".repeat(64)).observedAt(Instant.now()).build());

                    // Save later REVERSED
                    observationRepo.save(SettlementObservation.builder()
                            .id(UUID.randomUUID()).releaseInstructionId(ins.getId()).executionAttemptId(attempt.getId())
                            .provider("MOCK").source(SettlementObservationSource.STATUS_POLL).sourceReference("poll-" + i)
                            .providerOperationKey(opKey).providerSettlementId("S-" + i).providerAccountId("ESCROW-POOL-1")
                            .providerStatus("REVERSED").finality(SettlementObservationFinality.REVERSED)
                            .reasonCode("REVERSED").utrNumber("UTR-" + i).observedAmount(amount).observedCurrency("INR")
                            .observedDirection("DEBIT").evidenceHash("5".repeat(64)).observedAt(Instant.now().plusSeconds(5)).build());

                    // Conflict detected
                    var authRes = evidenceReader.getLatestAuthoritativeFinalObservation(ins.getId());
                    assertThat(authRes.status()).isEqualTo(AuthoritativeStatus.CONFLICT);
                }
            }

            // Assert all global invariants after every single iteration
            assertGlobalInvariants();
        }
    }
}
