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
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import com.finx.release.model.ReleaseInstructionStatus;
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
        "razorpay.sandbox-mode=true"
})
@EnabledIf("isPostgresAvailable")
public class WorkerClaimStormAndCrashRecoveryTest {

    static {
        ensureDatabaseExists("finx_release_e9_test");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FinxClock clock;

    @Autowired private FinancialOutboxLeaseService leaseService;
    @Autowired private SettlementExecutionAttemptService attemptService;

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
            // Database exists
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
    @DisplayName("PART G: 100 PENDING outbox messages claimed across 8 concurrent workers with zero overlap, zero starvation, SKIP LOCKED verified")
    void testPartG_workerClaimStorm100MessagesAcross8Workers() throws Exception {
        int rowCount = 100;
        int workerCount = 8;
        int batchSize = 10;

        Instant now = clock.now();
        List<UUID> seededOutboxIds = new ArrayList<>();
        List<UUID> seededInstructionIds = new ArrayList<>();

        for (int i = 0; i < rowCount; i++) {
            TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));
            seededOutboxIds.add(item.outbox.getId());
            seededInstructionIds.add(item.instruction.getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CyclicBarrier barrier = new CyclicBarrier(workerCount);
        Map<String, Set<UUID>> workerClaims = new ConcurrentHashMap<>();
        AtomicInteger totalClaimedCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int w = 0; w < workerCount; w++) {
            final String workerId = "worker-storm-" + w;
            workerClaims.put(workerId, ConcurrentHashMap.newKeySet());

            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
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
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // 1. Verify every eligible message was claimed exactly once
        assertThat(totalClaimedCount.get()).isEqualTo(rowCount);

        Set<UUID> allClaimedIds = new HashSet<>();
        for (Map.Entry<String, Set<UUID>> entry : workerClaims.entrySet()) {
            for (UUID id : entry.getValue()) {
                if (seededOutboxIds.contains(id)) {
                    boolean added = allClaimedIds.add(id);
                    assertThat(added)
                            .as("Outbox ID " + id + " was claimed by multiple workers!")
                            .isTrue();
                }
            }
        }
        assertThat(allClaimedIds).containsExactlyInAnyOrderElementsOf(seededOutboxIds);

        // 2. Verify all outbox rows in DB are LEASED with attemptCount = 1, lockedBy set
        for (UUID outboxId : seededOutboxIds) {
            FinancialOutboxMessage msg = outboxRepo.findById(outboxId).orElseThrow();
            assertThat(msg.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
            assertThat(msg.getLockedBy()).isNotNull().startsWith("worker-storm-");
            assertThat(msg.getLockedAt()).isNotNull();
            assertThat(msg.getAttemptCount()).isEqualTo(1);
        }

        // 3. Zero bank calls occurred during leasing
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("PART H: Worker crash before attempt - lease timeout recovers to RETRY, next worker claims and creates exactly one PREPARED attempt")
    void testPartH_workerCrashBeforeAttempt_safeRecoveryToRetryAndPreparedAttempt() {
        Instant now = clock.now();
        TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));

        // Worker 1 claims message
        String crashedWorkerId = "crashed-worker-alpha";
        List<FinancialOutboxMessage> claimed = leaseService.claimBatch(crashedWorkerId, 1);
        assertThat(claimed).hasSize(1);
        UUID outboxId = claimed.get(0).getId();

        FinancialOutboxMessage msg = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(msg.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
        assertThat(msg.getLockedBy()).isEqualTo(crashedWorkerId);

        // No execution attempt exists yet
        assertThat(attemptRepo.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(outboxId)).isEmpty();

        // Worker crashes. Time advances past lease timeout (default lease timeout 60s)
        Duration leaseTimeout = Duration.ofSeconds(60);
        clock.setFixed(now.plusSeconds(65));

        // Recovery service runs
        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                leaseTimeout, Duration.ofSeconds(5), 10
        );
        assertThat(recovered).extracting(FinancialOutboxMessage::getId).contains(outboxId);

        FinancialOutboxMessage recoveredMsg = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(recoveredMsg.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);
        assertThat(recoveredMsg.getLockedBy()).isNull();
        assertThat(recoveredMsg.getLockedAt()).isNull();

        // Advance time past retry delay
        clock.setFixed(now.plusSeconds(75));

        // Worker 2 claims the recovered message
        String worker2 = "recovered-worker-beta";
        List<FinancialOutboxMessage> reClaimed = leaseService.claimBatch(worker2, 1);
        assertThat(reClaimed).hasSize(1);
        assertThat(reClaimed.get(0).getId()).isEqualTo(outboxId);

        // Worker 2 prepares the attempt
        SettlementExecutionAttempt attempt = attemptService.prepareAttempt(outboxId, worker2);
        assertThat(attempt).isNotNull();
        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getProviderOperationKey()).isEqualTo(ProviderSettlementOperationKey.release(item.instruction.getId()));

        // Exactly 1 attempt in database
        List<SettlementExecutionAttempt> allAttempts = attemptRepo.findAll();
        assertThat(allAttempts).hasSize(1);
        assertThat(allAttempts.get(0).getId()).isEqualTo(attempt.getId());

        // Zero provider interactions
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("PART I: Worker crash after PREPARED - lease timeout recovers to RETRY, next worker claims and reuses SAME PREPARED attempt without duplicate attempt or new operation key")
    void testPartI_workerCrashAfterPrepared_safeRecoveryReusesSamePreparedAttempt() {
        Instant now = clock.now();
        TestItem item = createInstructionAndOutbox(FinancialOutboxStatus.PENDING, now.minusSeconds(10));

        // Worker 1 claims message and prepares attempt
        String crashedWorkerId = "crashed-worker-gamma";
        List<FinancialOutboxMessage> claimed = leaseService.claimBatch(crashedWorkerId, 1);
        assertThat(claimed).hasSize(1);
        UUID outboxId = claimed.get(0).getId();

        SettlementExecutionAttempt initialAttempt = attemptService.prepareAttempt(outboxId, crashedWorkerId);
        assertThat(initialAttempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);
        assertThat(initialAttempt.getRequestStartedAt()).isNull();
        UUID initialAttemptId = initialAttempt.getId();
        String initialProviderOpKey = initialAttempt.getProviderOperationKey();

        // Worker 1 dies before starting dispatch. Time advances past lease timeout (60s)
        Duration leaseTimeout = Duration.ofSeconds(60);
        clock.setFixed(now.plusSeconds(70));

        // Recovery service runs
        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                leaseTimeout, Duration.ofSeconds(5), 10
        );
        assertThat(recovered).extracting(FinancialOutboxMessage::getId).contains(outboxId);

        FinancialOutboxMessage recoveredMsg = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(recoveredMsg.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);

        // Advance past retry delay
        clock.setFixed(now.plusSeconds(80));

        // Worker 2 claims the recovered message
        String worker2 = "recovered-worker-delta";
        List<FinancialOutboxMessage> reClaimed = leaseService.claimBatch(worker2, 1);
        assertThat(reClaimed).hasSize(1);
        assertThat(reClaimed.get(0).getId()).isEqualTo(outboxId);

        // Worker 2 calls prepareAttempt
        SettlementExecutionAttempt reusedAttempt = attemptService.prepareAttempt(outboxId, worker2);
        assertThat(reusedAttempt).isNotNull();

        // Crucial assertions:
        // 1. Attempt ID unchanged
        assertThat(reusedAttempt.getId()).isEqualTo(initialAttemptId);
        // 2. Provider operation key unchanged
        assertThat(reusedAttempt.getProviderOperationKey()).isEqualTo(initialProviderOpKey);
        // 3. Attempt status is PREPARED
        assertThat(reusedAttempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);
        // 4. Exactly 1 attempt in database (no duplicate attempt row created)
        List<SettlementExecutionAttempt> allAttempts = attemptRepo.findAll();
        assertThat(allAttempts).hasSize(1);
        assertThat(allAttempts.get(0).getId()).isEqualTo(initialAttemptId);

        // Zero provider interactions
        verifyNoInteractions(bankingProvider);
    }
}
