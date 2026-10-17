package com.finx.release;

import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.approval.repository.MilestoneApprovalBindingRepository;
import com.finx.approval.service.MilestoneApprovalFingerprintService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import com.finx.engine.policy.release.ReleasePolicyDefinition;
import com.finx.engine.release.ReleaseDecisionEngine;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowAccountStatus;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.release.dto.CreateReleaseInstructionCommand;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.ReleaseAuthorizationService;
import com.finx.release.service.ReleaseInstructionService;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e9_test}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:finx_user}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:finx_password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.connection-timeout=20000",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "rate-limit.enabled=false",
        "finx.banking.provider=mock",
        "razorpay.sandbox-mode=true"
})
@EnabledIf("isPostgresAvailable")
public class ConcurrentAuthorizationConflictTest {

    static {
        ensureDatabaseExists("finx_release_e9_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    @Autowired private ReleaseInstructionService instructionService;
    @Autowired private ReleaseAuthorizationService authorizationService;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PaymentInstructionRepository paymentInstructionRepo;
    @Autowired private PaymentTransactionRepository paymentTransactionRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private MilestoneApprovalBindingRepository approvalBindingRepo;
    @Autowired private MilestoneApprovalFingerprintService fingerprintService;
    @Autowired private DeliverableRepository deliverableRepo;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private SettlementInstructionRepository settlementInstructionRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;

    @MockBean private BankingProvider bankingProvider;
    @SpyBean private ReleaseDecisionEngine releaseDecisionEngine;

    private User adminUser;
    private User buyerUser;
    private User sellerUser;
    private UserPrincipal adminPrincipal;

    private Organization buyerOrg;
    private Organization sellerOrg;
    private OrganizationBankAccount sellerBankAcc;

    private Deal deal;
    private Milestone milestone1;
    private Milestone milestone2;
    private EscrowAccount escrowAccount;

    private EnginePolicyVersion releasePolicy;

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
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE DATABASE " + dbName);
            }
        } catch (Exception ignored) {
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            hikari.setMaximumPoolSize(35);
        }
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }

        jdbcTemplate.execute("UPDATE release_instructions SET status = 'PENDING_SETTLEMENT', settled_at = NULL, final_settlement_observation_id = NULL");
        jdbcTemplate.execute("DELETE FROM journal_lines");
        jdbcTemplate.execute("DELETE FROM journal_entries");
        jdbcTemplate.execute("DELETE FROM escrow_ledger");
        jdbcTemplate.execute("DELETE FROM settlement_observations");
        jdbcTemplate.execute("DELETE FROM webhook_events");
        jdbcTemplate.execute("DELETE FROM settlement_execution_attempts");
        jdbcTemplate.execute("DELETE FROM settlement_instructions");
        jdbcTemplate.execute("DELETE FROM financial_outbox");
        jdbcTemplate.execute("DELETE FROM release_reservations");
        jdbcTemplate.execute("DELETE FROM release_instructions");
        jdbcTemplate.execute("DELETE FROM engine_decisions");
        jdbcTemplate.execute("DELETE FROM engine_policy_versions");
        jdbcTemplate.execute("DELETE FROM payment_transactions");
        jdbcTemplate.execute("DELETE FROM payment_instructions");
        jdbcTemplate.execute("DELETE FROM disputes");
        jdbcTemplate.execute("DELETE FROM milestone_approval_bindings");
        jdbcTemplate.execute("DELETE FROM deliverables");
        jdbcTemplate.execute("DELETE FROM milestones");
        jdbcTemplate.execute("DELETE FROM escrow_accounts");
        jdbcTemplate.execute("DELETE FROM deals");
        jdbcTemplate.execute("DELETE FROM organization_bank_accounts");
        jdbcTemplate.execute("DELETE FROM organization_members");
        jdbcTemplate.execute("DELETE FROM organizations");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);

            adminUser = userRepo.save(new User("Admin " + suffix, "admin-" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));
            buyerUser = userRepo.save(new User("Buyer " + suffix, "buyer-" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));
            sellerUser = userRepo.save(new User("Seller " + suffix, "seller-" + suffix + "@finx.invalid", "pass", Role.SELLER, UserStatus.ACTIVE));
            adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getName(), adminUser.getEmail(), "pass", Role.ADMIN, UserStatus.ACTIVE, List.of());

            buyerOrg = orgRepo.save(new Organization("Buyer Org " + suffix, "Buyer Legal " + suffix, EntityType.PRIVATE_LIMITED,
                    "REG-B-" + suffix, "PAN-B-" + suffix, "29AAAAA0000A1Z5", "Street B", buyerUser.getId()));
            sellerOrg = orgRepo.save(new Organization("Seller Org " + suffix, "Seller Legal " + suffix, EntityType.PRIVATE_LIMITED,
                    "REG-S-" + suffix, "PAN-S-" + suffix, "29BBBBB0000B1Z5", "Street S", sellerUser.getId()));

            sellerBankAcc = new OrganizationBankAccount(sellerOrg.getId(), "Seller Legal " + suffix, "****5678", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
            sellerBankAcc.setEncryptedAccountNumber("enc-" + suffix);
            sellerBankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
            sellerBankAcc.setNameMatchStatus("EXACT_MATCH");
            sellerBankAcc.setVerifiedAt(Instant.now().minusSeconds(600));
            sellerBankAcc = bankAccRepo.save(sellerBankAcc);

            deal = new Deal();
            deal.setTitle("E9 Real Postgres Concurrency Deal " + suffix);
            deal.setDescription("Test deal");
            deal.setBuyerId(buyerUser.getId());
            deal.setSellerId(sellerUser.getId());
            deal.setBuyerOrganizationId(buyerOrg.getId());
            deal.setSellerOrganizationId(sellerOrg.getId());
            deal.setTotalAmount(new BigDecimal("140000.0000"));
            deal.setCurrency("INR");
            deal.setStatus(DealStatus.FUNDED);
            deal = dealRepo.save(deal);

            Instant nowTrunc = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant dueDate = nowTrunc.plusSeconds(86400);

            milestone1 = new Milestone(deal.getId(), "M1 " + suffix, "First Milestone", 1, new BigDecimal("70000.0000"), "INR", dueDate, dueDate);
            milestone1.setStatus(MilestoneStatus.APPROVED);
            milestone1 = milestoneRepo.saveAndFlush(milestone1);

            milestone2 = new Milestone(deal.getId(), "M2 " + suffix, "Second Milestone", 2, new BigDecimal("70000.0000"), "INR", dueDate, dueDate);
            milestone2.setStatus(MilestoneStatus.APPROVED);
            milestone2 = milestoneRepo.saveAndFlush(milestone2);

            escrowAccount = new EscrowAccount(deal.getId(), new BigDecimal("100000.0000"), "INR");
            escrowAccount.setStatus(EscrowAccountStatus.ACTIVE);
            escrowAccount = escrowRepo.save(escrowAccount);

            PaymentInstruction pi = new PaymentInstruction(
                    "PI-" + suffix, deal.getId(), null, buyerOrg.getId(), sellerOrg.getId(),
                    new BigDecimal("100000.0000"), "INR", "AXIS", "idem-" + suffix, buyerUser.getId()
            );
            pi.setProviderAccountId("ESCROW-POOL-1");
            pi = paymentInstructionRepo.save(pi);

            PaymentTransaction pt = new PaymentTransaction(
                    "TX-" + suffix, pi.getId(), "AXIS", "REF-" + suffix,
                    new BigDecimal("100000.0000"), "INR", "SUCCESS", "UTR-" + suffix, "{}"
            );
            paymentTransactionRepo.save(pt);

            Optional<EnginePolicyVersion> existingPolicy = policyRepo.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                    EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
            );
            if (existingPolicy.isPresent()) {
                releasePolicy = existingPolicy.get();
            } else {
                releasePolicy = new EnginePolicyVersion(
                        null,
                        PolicyScope.PLATFORM,
                        EngineType.RELEASE,
                        ReleasePolicyDefinition.POLICY_KEY,
                        ReleasePolicyDefinition.POLICY_VERSION,
                        ReleasePolicyDefinition.getExpectedContentHash(),
                        "1.0",
                        ReleasePolicyDefinition.DESCRIPTION,
                        ReleasePolicyDefinition.getCanonicalPolicyContent(),
                        adminUser.getId(),
                        Instant.now()
                );
                releasePolicy = policyRepo.save(releasePolicy);
            }

            return null;
        });

        doAnswer(inv -> {
            UUID mId = inv.getArgument(1);
            return evalResultsByMilestoneId.get(mId);
        }).when(releaseDecisionEngine)
                .evaluateMilestone(any(), any(), any(), any(), any(), any());
    }

    private final Map<UUID, com.finx.engine.release.ReleaseEvaluationResult> evalResultsByMilestoneId = new ConcurrentHashMap<>();

    private EngineDecision setupCompleteReleaseDecisionForMilestone(Milestone inputMilestone) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            Milestone targetMilestone = milestoneRepo.findById(inputMilestone.getId()).orElseThrow();
            UUID evalId = UUID.randomUUID();
            UUID corrId = UUID.randomUUID();
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            Deliverable del = new Deliverable(targetMilestone.getId(), buyerUser.getId(), "del.pdf", "https://del.pdf", "Artifact");
            del.setStatus(com.finx.milestone.entity.DeliverableStatus.APPROVED);
            del = deliverableRepo.saveAndFlush(del);
            Deliverable reloadedDel = deliverableRepo.findById(del.getId()).orElseThrow();

            String delFp = fingerprintService.computeDeliverablesFingerprint(List.of(reloadedDel));
            String mFp = fingerprintService.computeMilestoneFingerprint(targetMilestone);
            String appFp = fingerprintService.computeApprovalFingerprint(
                    buyerOrg.getId(), deal.getId(), targetMilestone.getId(),
                    buyerUser.getId(), MilestoneApprovalAuthority.BUYER, null,
                    mFp, delFp, now
            );

            MilestoneApprovalBinding binding = new MilestoneApprovalBinding(
                    UUID.randomUUID(),
                    buyerOrg.getId(),
                    deal.getId(),
                    targetMilestone.getId(),
                    buyerUser.getId(),
                    MilestoneApprovalAuthority.BUYER,
                    mFp,
                    delFp,
                    appFp,
                    null,
                    now,
                    now
            );
            approvalBindingRepo.save(binding);

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(Map.of("sourceType", "ORGANIZATION_BANK_ACCOUNT", "sourceId", sellerBankAcc.getId().toString(), "provenance", "MOCK"));

            for (EngineType engineType : ReleasePolicyDefinition.REQUIRED_ENGINES) {
                EngineDecision comp = new EngineDecision();
                setField(comp, "organizationId", buyerOrg.getId());
                setField(comp, "engineType", engineType);
                setField(comp, "subjectType", "MILESTONE");
                setField(comp, "subjectId", targetMilestone.getId());
                setField(comp, "decision", DecisionStatus.ALLOW);
                setField(comp, "evaluationOutcome", EvaluationOutcome.SUCCESS);
                setField(comp, "inputFingerprint", "0".repeat(64));
                setField(comp, "decisionFingerprint", "1".repeat(64));
                setField(comp, "policyVersionId", releasePolicy.getId());
                setField(comp, "policyContentHash", releasePolicy.getContentHash());
                setField(comp, "evaluationId", evalId);
                setField(comp, "correlationId", corrId);
                setField(comp, "ruleVersion", engineType.name() + ":1.0.0");
                setField(comp, "reasonCodesJson", "[\"ALLOW_GATE\"]");
                setField(comp, "evidenceReferencesJson", "[]");
                setField(comp, "evaluatedAt", now);
                setField(comp, "validUntil", now.plusSeconds(3600));

                comp = decisionRepo.save(comp);
                refs.add(Map.of("sourceType", "ENGINE_DECISION", "sourceId", comp.getId().toString(), "provenance", engineType.name()));
            }

            String simulatedInputFingerprint = (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).toLowerCase().substring(0, 64);

            RecordEngineDecisionCommand cmd = RecordEngineDecisionCommand.builder()
                    .engineType(EngineType.RELEASE)
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .organizationId(buyerOrg.getId())
                    .subjectType("MILESTONE")
                    .subjectId(targetMilestone.getId())
                    .policyVersionId(releasePolicy.getId())
                    .policyContentHash(releasePolicy.getContentHash())
                    .ruleVersion(ReleasePolicyDefinition.POLICY_KEY + ":" + ReleasePolicyDefinition.POLICY_VERSION)
                    .inputFingerprint(simulatedInputFingerprint)
                    .reasonCodes(List.of(ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW))
                    .evaluationId(evalId)
                    .correlationId(corrId)
                    .evaluatedAt(now)
                    .build();

            com.finx.engine.release.ReleaseEvaluationResult evalResult = new com.finx.engine.release.ReleaseEvaluationResult(
                    evalId, corrId, null, List.of(), cmd, List.of()
            );

            evalResultsByMilestoneId.put(targetMilestone.getId(), evalResult);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String refsJson;
            try {
                refsJson = mapper.writeValueAsString(refs);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            EngineDecision releaseDecision = new EngineDecision();
            setField(releaseDecision, "organizationId", buyerOrg.getId());
            setField(releaseDecision, "engineType", EngineType.RELEASE);
            setField(releaseDecision, "subjectType", "MILESTONE");
            setField(releaseDecision, "subjectId", targetMilestone.getId());
            setField(releaseDecision, "decision", DecisionStatus.ALLOW);
            setField(releaseDecision, "evaluationOutcome", EvaluationOutcome.SUCCESS);
            setField(releaseDecision, "inputFingerprint", simulatedInputFingerprint);
            setField(releaseDecision, "decisionFingerprint", "d".repeat(64));
            setField(releaseDecision, "policyVersionId", releasePolicy.getId());
            setField(releaseDecision, "policyContentHash", releasePolicy.getContentHash());
            setField(releaseDecision, "evaluationId", evalId);
            setField(releaseDecision, "correlationId", corrId);
            setField(releaseDecision, "ruleVersion", ReleasePolicyDefinition.POLICY_KEY + ":1.0.0");
            setField(releaseDecision, "reasonCodesJson", "[\"" + ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW + "\"]");
            setField(releaseDecision, "evidenceReferencesJson", refsJson);
            setField(releaseDecision, "evaluatedAt", now);
            setField(releaseDecision, "validUntil", now.plusSeconds(3600));

            return decisionRepo.save(releaseDecision);
        });
    }

    @Test
    @DisplayName("PART D — Two concurrent releases each requesting 70k from 100k escrow: exactly one reaches QUEUED")
    void testPartD_concurrentOversubscriptionAuthorization() throws Exception {
        EngineDecision decision1 = setupCompleteReleaseDecisionForMilestone(milestone1);
        EngineDecision decision2 = setupCompleteReleaseDecisionForMilestone(milestone2);

        ReleaseInstruction instruction1 = instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decision1.getId(), "idem-m1-" + UUID.randomUUID()), adminPrincipal);
        ReleaseInstruction instruction2 = instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decision2.getId(), "idem-m2-" + UUID.randomUUID()), adminPrincipal);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        Future<?> f1 = executor.submit(() -> {
            try {
                startLatch.await();
                authorizationService.authorizeAndReserve(instruction1.getId(), adminPrincipal);
                successes.incrementAndGet();
            } catch (Throwable e) {
                failures.incrementAndGet();
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                startLatch.await();
                authorizationService.authorizeAndReserve(instruction2.getId(), adminPrincipal);
                successes.incrementAndGet();
            } catch (Throwable e) {
                failures.incrementAndGet();
            }
        });

        startLatch.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        EscrowAccount finalEscrow = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(finalEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("100000.0000"));
        assertThat(finalEscrow.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);

        BigDecimal activeReservedSum = reservationRepo.sumActiveReservationsForEscrow(escrowAccount.getId());
        assertThat(activeReservedSum).isEqualByComparingTo(new BigDecimal("70000.0000"));
        assertThat(activeReservedSum).isLessThanOrEqualTo(finalEscrow.getBalance());

        assertThat(journalEntryRepo.findAll()).isEmpty();
        assertThat(settlementInstructionRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("PART E — Same Release Instruction authorization storm (20 threads): exactly one QUEUED instruction, one reservation, one outbox")
    void testPartE_sameReleaseAuthorizationStorm() throws Exception {
        EngineDecision decision = setupCompleteReleaseDecisionForMilestone(milestone1);
        ReleaseInstruction instruction = instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decision.getId(), "idem-storm-" + UUID.randomUUID()), adminPrincipal);

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<UUID> reservationIds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ReleaseReservation res = authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal);
                    reservationIds.add(res.getId());
                } catch (Throwable ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(reservationIds).isNotEmpty();
        Set<UUID> uniqueReservationIds = new HashSet<>(reservationIds);
        assertThat(uniqueReservationIds).hasSize(1);

        ReleaseInstruction finalInstruction = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(finalInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        List<ReleaseReservation> reservationsInDb = reservationRepo.findAll().stream()
                .filter(r -> r.getReleaseInstructionId().equals(instruction.getId()))
                .toList();
        assertThat(reservationsInDb).hasSize(1);
        assertThat(reservationsInDb.get(0).getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        long outboxCount = outboxRepo.findAll().stream()
                .filter(o -> o.getReleaseInstructionId().equals(instruction.getId()))
                .count();
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    @DisplayName("PART F — Idempotency key adversarial race: duplicate create requests yield exactly 1 ReleaseInstruction")
    void testPartF_idempotencyKeyConcurrentCreate() throws Exception {
        EngineDecision decision = setupCompleteReleaseDecisionForMilestone(milestone1);
        String sharedKey = "shared-idem-" + UUID.randomUUID();

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<UUID> createdIds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ReleaseInstruction inst = instructionService.createFromReleaseDecision(
                            new CreateReleaseInstructionCommand(decision.getId(), sharedKey), adminPrincipal);
                    createdIds.add(inst.getId());
                } catch (Throwable ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Set<UUID> uniqueIds = new HashSet<>(createdIds);
        assertThat(uniqueIds).hasSize(1);

        long persistedCount = instructionRepo.findAll().stream()
                .filter(r -> r.getIdempotencyKey().equals(sharedKey))
                .count();
        assertThat(persistedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("PART F2 — Conflicting payload under same idempotency key is rejected")
    void testPartF2_conflictingPayloadSameIdempotencyKeyRejected() {
        EngineDecision decision1 = setupCompleteReleaseDecisionForMilestone(milestone1);
        EngineDecision decision2 = setupCompleteReleaseDecisionForMilestone(milestone2);
        String sharedKey = "conflict-idem-" + UUID.randomUUID();

        ReleaseInstruction inst1 = instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decision1.getId(), sharedKey), adminPrincipal);
        assertThat(inst1).isNotNull();

        assertThatThrownBy(() -> instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decision2.getId(), sharedKey), adminPrincipal))
                .isInstanceOf(Exception.class);
    }
}
