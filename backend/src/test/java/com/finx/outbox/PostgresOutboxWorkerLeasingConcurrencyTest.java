package com.finx.outbox;

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
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.milestone.entity.Milestone;
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
import com.finx.outbox.worker.FinancialOutboxWorker;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e4_test}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:finx_user}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:finx_password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "rate-limit.enabled=false",
        "finx.banking.provider=mock",
        "razorpay.sandbox-mode=true"
})
@EnabledIf("isPostgresAvailable")
public class PostgresOutboxWorkerLeasingConcurrencyTest {

    static {
        ensureDatabaseExists("finx_release_e4_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private FinxClock clock;

    @Autowired private FinancialOutboxLeaseService leaseService;
    @Autowired private SettlementExecutionAttemptService attemptService;
    @Autowired private FinancialOutboxWorker outboxWorker;

    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;

    @MockBean private BankingProvider bankingProvider;

    private User testUser;
    private Organization testOrg;
    private OrganizationBankAccount testBankAcc;
    private Deal testDeal;
    private EscrowAccount testEscrow;
    private EnginePolicyVersion testPolicy;

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

    @BeforeEach
    void setUp() {
        clock.reset();
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        testUser = userRepo.save(new User("User " + suffix, "u" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));

        testOrg = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", testUser.getId()));

        testBankAcc = new OrganizationBankAccount(testOrg.getId(), "Legal " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        testBankAcc.setEncryptedAccountNumber("enc-" + suffix);
        testBankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        testBankAcc = bankAccRepo.save(testBankAcc);

        testDeal = new Deal();
        testDeal.setTitle("Deal " + suffix);
        testDeal.setDescription("Test deal");
        testDeal.setBuyerId(testUser.getId());
        testDeal.setSellerId(testUser.getId());
        testDeal.setTotalAmount(new BigDecimal("1000000.0000"));
        testDeal.setCurrency("INR");
        testDeal.setStatus(DealStatus.FUNDED);
        testDeal = dealRepo.save(testDeal);

        testEscrow = escrowRepo.save(new EscrowAccount(testDeal.getId(), new BigDecimal("1000000.0000"), "INR"));

        Instant now = clock.now();
        testPolicy = policyRepo.save(new EnginePolicyVersion(
                null, com.finx.engine.model.PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", testUser.getId(), now
        ));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    private TestItem createInstructionAndOutbox(FinancialOutboxStatus status, Instant availableAt) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = clock.now();

        Milestone milestone = milestoneRepo.save(new Milestone(
                testDeal.getId(), "M-" + suffix, "Desc", 1,
                new BigDecimal("1000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400)
        ));

        EngineDecision decision = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(decision, testOrg.getId());
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(decision, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(decision, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(decision, milestone.getId());
            var fDec = EngineDecision.class.getDeclaredField("decision"); fDec.setAccessible(true); fDec.set(decision, DecisionStatus.ALLOW);
            var fOut = EngineDecision.class.getDeclaredField("evaluationOutcome"); fOut.setAccessible(true); fOut.set(decision, EvaluationOutcome.SUCCESS);
            var fInFp = EngineDecision.class.getDeclaredField("inputFingerprint"); fInFp.setAccessible(true); fInFp.set(decision, "0".repeat(64));
            var fDecFp = EngineDecision.class.getDeclaredField("decisionFingerprint"); fDecFp.setAccessible(true); fDecFp.set(decision, "1".repeat(64));
            var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(decision, testPolicy.getId());
            var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(decision, testPolicy.getContentHash());
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
        decision = decisionRepo.save(decision);

        ReleaseInstruction instruction = new ReleaseInstruction(
                UUID.randomUUID(), testOrg.getId(), testDeal.getId(), milestone.getId(), decision.getId(),
                "1".repeat(64), testPolicy.getId(), testPolicy.getContentHash(), testBankAcc.getId(),
                "MOCK", "MOCK-ACC-1", new BigDecimal("1000.0000"), "INR", "idem-" + suffix,
                "a".repeat(64), testUser.getId()
        );
        instruction.markAuthorized(testUser.getId(), now);
        instruction.markReserved();
        instruction.markQueued();
        instruction = instructionRepo.save(instruction);

        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(), instruction.getId(), testEscrow.getId(), milestone.getId(),
                new BigDecimal("1000.0000"), "INR"
        );
        reservationRepo.save(reservation);

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(), instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instruction.getId().toString(), 1, status, 0, availableAt, now, now
        );
        outbox = outboxRepo.save(outbox);

        return new TestItem(instruction, reservation, outbox);
    }

    private static class TestItem {
        final ReleaseInstruction instruction;
        final ReleaseReservation reservation;
        final FinancialOutboxMessage outbox;

        TestItem(ReleaseInstruction instruction, ReleaseReservation reservation, FinancialOutboxMessage outbox) {
            this.instruction = instruction;
            this.reservation = reservation;
            this.outbox = outbox;
        }
    }

    @Test
    @DisplayName("PART AO: 20 concurrent outbox rows claimed exactly once across 4 worker threads")
    void test01_concurrentWorkersClaimAllPendingRowsExactlyOnceWithoutDuplicatesOrDeadlocks() throws Exception {
        int rowCount = 20;
        int threadCount = 4;
        int batchSize = 5;

        Instant now = clock.now();
        List<UUID> seededIds = new ArrayList<>();
        List<UUID> instructionIds = new ArrayList<>();

        for (int i = 0; i < rowCount; i++) {
            TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
            seededIds.add(item.outbox.getId());
            instructionIds.add(item.instruction.getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        Map<String, Set<UUID>> workerClaims = new ConcurrentHashMap<>();
        AtomicInteger totalClaimedCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final String workerId = "worker-thread-" + t;
            workerClaims.put(workerId, ConcurrentHashMap.newKeySet());

            tasks.add(() -> {
                barrier.await();
                // Loop until no more available rows
                while (true) {
                    List<FinancialOutboxMessage> batch = leaseService.claimBatch(workerId, batchSize);
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (FinancialOutboxMessage msg : batch) {
                        workerClaims.get(workerId).add(msg.getId());
                        totalClaimedCount.incrementAndGet();
                    }
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // 1. Verify all 20 rows claimed
        assertThat(totalClaimedCount.get()).isGreaterThanOrEqualTo(rowCount);

        Set<UUID> allClaimedIds = new HashSet<>();
        for (Map.Entry<String, Set<UUID>> entry : workerClaims.entrySet()) {
            for (UUID id : entry.getValue()) {
                if (seededIds.contains(id)) {
                    // 2. Intersection between workers is empty (each row claimed by exactly one worker)
                    boolean added = allClaimedIds.add(id);
                    assertThat(added)
                            .as("Outbox ID " + id + " was claimed by multiple workers!")
                            .isTrue();
                }
            }
        }
        assertThat(allClaimedIds).containsAll(seededIds);

        // 3. Verify database state for all seeded rows
        for (UUID id : seededIds) {
            FinancialOutboxMessage msg = outboxRepo.findById(id).orElseThrow();
            assertThat(msg.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
            assertThat(msg.getLockedBy()).isNotNull();
            assertThat(msg.getLockedAt()).isNotNull();
            assertThat(msg.getAttemptCount()).isEqualTo(1);
        }

        // 4. Verify ReleaseInstruction status remains QUEUED throughout
        for (UUID insId : instructionIds) {
            ReleaseInstruction ins = instructionRepo.findById(insId).orElseThrow();
            assertThat(ins.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        }

        // 5. Zero bank calls
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("PART AP: SKIP LOCKED allows Worker B to claim available rows while Worker A holds active lease transaction")
    void test02_skipLockedAllowsConcurrentWorkersToClaimDifferentRowsWithoutBlocking() throws Exception {
        int totalRows = 10;
        Instant now = clock.now();
        List<UUID> seededIds = new ArrayList<>();

        for (int i = 0; i < totalRows; i++) {
            TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
            seededIds.add(item.outbox.getId());
        }

        CountDownLatch workerALockedLatch = new CountDownLatch(1);
        CountDownLatch workerBFinishedLatch = new CountDownLatch(1);
        List<UUID> workerAClaimed = new ArrayList<>();
        List<UUID> workerBClaimed = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Worker A thread holds an active transaction
        Future<Void> workerAFuture = executor.submit(() -> {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt.execute(status -> {
                List<FinancialOutboxMessage> claimed = leaseService.claimBatch("worker-A", 5);
                for (FinancialOutboxMessage msg : claimed) {
                    workerAClaimed.add(msg.getId());
                }
                workerALockedLatch.countDown();
                try {
                    boolean ok = workerBFinishedLatch.await(5, TimeUnit.SECONDS);
                    if (!ok) {
                        throw new RuntimeException("Timeout waiting for worker B");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            return null;
        });

        // Main thread waits until Worker A has locked its 5 rows
        boolean locked = workerALockedLatch.await(5, TimeUnit.SECONDS);
        assertThat(locked).isTrue();

        // Worker B attempts to claim 5 rows concurrently while Worker A's transaction is open
        Future<Void> workerBFuture = executor.submit(() -> {
            List<FinancialOutboxMessage> claimed = leaseService.claimBatch("worker-B", 5);
            for (FinancialOutboxMessage msg : claimed) {
                workerBClaimed.add(msg.getId());
            }
            workerBFinishedLatch.countDown();
            return null;
        });

        workerBFuture.get(5, TimeUnit.SECONDS);
        workerAFuture.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert SKIP LOCKED worked: Worker B got 5 rows without blocking on Worker A
        assertThat(workerAClaimed).hasSize(5);
        assertThat(workerBClaimed).hasSize(5);

        // Ensure completely disjoint sets
        Set<UUID> intersection = new HashSet<>(workerAClaimed);
        intersection.retainAll(workerBClaimed);
        assertThat(intersection).isEmpty();

        // Verify all 10 are now LEASED
        for (UUID id : seededIds) {
            FinancialOutboxMessage msg = outboxRepo.findById(id).orElseThrow();
            assertThat(msg.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
        }
    }

    @Test
    @DisplayName("PART AQ: Two concurrent prepareAttempt calls on same leased row produce exactly 1 PREPARED attempt")
    void test03_preparedAttemptConcurrencyProducesSingleAttemptWithoutDeadlock() throws Exception {
        Instant now = clock.now();
        TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
        UUID outboxId = item.outbox.getId();

        // Worker leases row first
        List<FinancialOutboxMessage> leased = leaseService.claimBatch("worker-concurrency", 1);
        assertThat(leased).hasSize(1);
        assertThat(leased.get(0).getId()).isEqualTo(outboxId);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<SettlementExecutionAttempt> results = Collections.synchronizedList(new ArrayList<>());

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                barrier.await();
                SettlementExecutionAttempt attempt = attemptService.prepareAttempt(outboxId, "worker-concurrency");
                results.add(attempt);
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(results).hasSize(2);
        SettlementExecutionAttempt a1 = results.get(0);
        SettlementExecutionAttempt a2 = results.get(1);

        // Both got the exact same attempt instance (idempotent reuse)
        assertThat(a1.getId()).isEqualTo(a2.getId());
        assertThat(a1.getAttemptNumber()).isEqualTo(1);
        assertThat(a1.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);
        assertThat(a1.getProviderOperationKey()).isEqualTo(ProviderSettlementOperationKey.release(item.instruction.getId()));

        // Exactly 1 attempt exists in database
        List<SettlementExecutionAttempt> inDb = attemptRepo.findByOutboxMessageIdOrderByAttemptNumberDesc(outboxId);
        assertThat(inDb).hasSize(1);
    }

    @Test
    @DisplayName("PART AR: Expired pre-dispatch lease recovery safely transitions outbox to RETRY")
    void test04_expiredPreDispatchLeaseRecoveryReturnsToRetry() {
        Instant now = clock.now();

        // Case 1: LEASED row with NO execution attempt
        TestItem item1 = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
        FinancialOutboxMessage msg1 = item1.outbox;
        msg1.markLeased("worker-old-1", now.minusSeconds(600));
        msg1 = outboxRepo.save(msg1);

        // Case 2: LEASED row with PREPARED execution attempt
        TestItem item2 = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
        FinancialOutboxMessage msg2 = item2.outbox;
        msg2.markLeased("worker-old-2", now.minusSeconds(600));
        msg2 = outboxRepo.save(msg2);

        SettlementExecutionAttempt attempt2 = attemptService.prepareAttempt(msg2.getId(), "worker-old-2");
        assertThat(attempt2.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);

        // Run expired recovery (timeout 5 minutes)
        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ofMinutes(5), Duration.ofMinutes(1), 10
        );

        List<UUID> recoveredIds = recovered.stream().map(FinancialOutboxMessage::getId).toList();
        assertThat(recoveredIds).contains(msg1.getId(), msg2.getId());

        // Verify msg1 state
        FinancialOutboxMessage reloaded1 = outboxRepo.findById(msg1.getId()).orElseThrow();
        assertThat(reloaded1.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);
        assertThat(reloaded1.getLockedBy()).isNull();
        assertThat(reloaded1.getLockedAt()).isNull();
        assertThat(reloaded1.getLastErrorCode()).isEqualTo("LEASE_EXPIRED_BEFORE_DISPATCH");

        // Verify msg2 state and retained attempt
        FinancialOutboxMessage reloaded2 = outboxRepo.findById(msg2.getId()).orElseThrow();
        assertThat(reloaded2.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);
        assertThat(reloaded2.getLockedBy()).isNull();
        assertThat(reloaded2.getLockedAt()).isNull();

        List<SettlementExecutionAttempt> attempts2 = attemptRepo.findByOutboxMessageIdOrderByAttemptNumberDesc(msg2.getId());
        assertThat(attempts2).hasSize(1);
        assertThat(attempts2.get(0).getId()).isEqualTo(attempt2.getId());
        assertThat(attempts2.get(0).getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);
    }

    @Test
    @DisplayName("PART AS: Network-started attempts (SENDING, ACKNOWLEDGED, UNKNOWN) are strictly blocked from retry recovery")
    void test05_networkStartedAttemptsBlockedFromSafeRecovery() {
        Instant now = clock.now();
        TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
        final UUID targetOutboxId = item.outbox.getId();
        FinancialOutboxMessage msg = item.outbox;
        msg.markLeased("worker-net", now.minusSeconds(600));
        msg = outboxRepo.save(msg);

        // Prepare attempt
        SettlementExecutionAttempt attempt = attemptService.prepareAttempt(targetOutboxId, "worker-net");

        // Update attempt to SENDING status in DB (simulating E5 network phase start)
        jdbc.update("UPDATE settlement_execution_attempts SET status = 'SENDING', request_started_at = NOW() WHERE id = ?",
                attempt.getId());

        // Try recoverExpiredPreDispatchLeases
        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ofMinutes(5), Duration.ofMinutes(1), 10
        );

        // Must NOT be recovered into RETRY
        List<UUID> recoveredIds = recovered.stream().map(FinancialOutboxMessage::getId).toList();
        assertThat(recoveredIds).doesNotContain(targetOutboxId);

        FinancialOutboxMessage reloaded = outboxRepo.findById(targetOutboxId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED); // remains LEASED / non-redispatchable

        // Explicit releaseLeaseBeforeDispatchForRetry must fail closed
        assertThatThrownBy(() -> leaseService.releaseLeaseBeforeDispatchForRetry(
                targetOutboxId, "worker-net", "FAIL_REASON", Duration.ofMinutes(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release lease for retry when attempt has started network execution");

        // Repeat check for ACKNOWLEDGED
        jdbc.update("UPDATE settlement_execution_attempts SET status = 'ACKNOWLEDGED' WHERE id = ?", attempt.getId());
        assertThatThrownBy(() -> leaseService.releaseLeaseBeforeDispatchForRetry(
                targetOutboxId, "worker-net", "FAIL_REASON", Duration.ofMinutes(1)
        )).isInstanceOf(IllegalStateException.class);

        // Repeat check for UNKNOWN
        jdbc.update("UPDATE settlement_execution_attempts SET status = 'UNKNOWN' WHERE id = ?", attempt.getId());
        assertThatThrownBy(() -> leaseService.releaseLeaseBeforeDispatchForRetry(
                targetOutboxId, "worker-net", "FAIL_REASON", Duration.ofMinutes(1)
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PART AU: Worker A crash after PREPARED allows Worker B to recover lease and reuse existing attempt")
    void test06_multiNodeCrashAndReclaimReusesPreparedAttempt() {
        Instant now = clock.now();
        TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
        UUID outboxId = item.outbox.getId();

        // 1. Worker A claims row
        List<FinancialOutboxMessage> claimedA = leaseService.claimBatch("worker-A", 1);
        assertThat(claimedA).hasSize(1);
        assertThat(claimedA.get(0).getId()).isEqualTo(outboxId);

        // 2. Worker A prepares attempt
        SettlementExecutionAttempt attemptA = attemptService.prepareAttempt(outboxId, "worker-A");
        assertThat(attemptA.getAttemptNumber()).isEqualTo(1);
        assertThat(attemptA.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);

        // 3. Worker A crashes before bank dispatch. Clock advances past lease timeout.
        clock.advance(Duration.ofMinutes(10));

        // 4. Recovery job recovers expired pre-dispatch leases
        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ofMinutes(5), Duration.ofMinutes(0), 10
        );
        assertThat(recovered.stream().map(FinancialOutboxMessage::getId)).contains(outboxId);

        FinancialOutboxMessage afterRecovery = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(afterRecovery.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);
        assertThat(afterRecovery.getLockedBy()).isNull();

        // 5. Worker B claims row
        List<FinancialOutboxMessage> claimedB = leaseService.claimBatch("worker-B", 1);
        assertThat(claimedB).hasSize(1);
        assertThat(claimedB.get(0).getId()).isEqualTo(outboxId);
        assertThat(claimedB.get(0).getAttemptCount()).isEqualTo(2); // lease attempt count increments

        // 6. Worker B prepares attempt -> reuses existing PREPARED attempt
        SettlementExecutionAttempt attemptB = attemptService.prepareAttempt(outboxId, "worker-B");

        assertThat(attemptB.getId()).isEqualTo(attemptA.getId());
        assertThat(attemptB.getAttemptNumber()).isEqualTo(1); // attempt number remains 1
        assertThat(attemptB.getProviderOperationKey()).isEqualTo(attemptA.getProviderOperationKey());
        assertThat(attemptB.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);

        // Exactly 1 attempt in database
        List<SettlementExecutionAttempt> allAttempts = attemptRepo.findByOutboxMessageIdOrderByAttemptNumberDesc(outboxId);
        assertThat(allAttempts).hasSize(1);

        // ReleaseInstruction remains QUEUED
        ReleaseInstruction ins = instructionRepo.findById(item.instruction.getId()).orElseThrow();
        assertThat(ins.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        // ReleaseReservation remains ACTIVE
        ReleaseReservation res = reservationRepo.findByReleaseInstructionId(ins.getId()).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }
}
