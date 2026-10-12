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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e2_test}",
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
public class PostgresReleaseAuthorizationConcurrencyTest {

    static {
        ensureDatabaseExists("finx_release_e2_test");
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
    @Autowired private com.finx.outbox.repository.FinancialOutboxRepository outboxRepo;

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
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }

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
            deal.setTitle("E2 Real Postgres Concurrency Deal " + suffix);
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

            // Reconciled PaymentInstruction funding evidence
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

            // Platform Release Policy
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

            // 1. Deliverables & Durable milestone approval binding
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

            // 2. Component Engine Decisions (8 ALLOW)
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

            // Mock in-memory read-only re-evaluation
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
            doAnswer(inv -> {
                UUID mId = inv.getArgument(1);
                return evalResultsByMilestoneId.get(mId);
            }).when(releaseDecisionEngine)
                    .evaluateMilestone(any(), any(), any(), any(), any(), any());

            // 3. Persist Release Decision
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
    @DisplayName("1-4. Real PostgreSQL detected, create instruction, authorize, and reserve")
    void test01_createAndAuthorizeUnderPostgres() {
        EngineDecision decision = setupCompleteReleaseDecisionForMilestone(milestone1);

        // Create
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(decision.getId(), "idem-test-01");
        ReleaseInstruction instruction = instructionService.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(instruction).isNotNull();
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        assertThat(instruction.getAmount()).isEqualByComparingTo(new BigDecimal("70000.0000"));

        // Authorize + Reserve
        ReleaseReservation reservation = authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal);

        assertThat(reservation).isNotNull();
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(reservation.getAmount()).isEqualByComparingTo(new BigDecimal("70000.0000"));

        // Verify committed database state
        ReleaseInstruction committedInstruction = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(committedInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        assertThat(committedInstruction.getAuthorizedBy()).isEqualTo(adminUser.getId());

        // Verify committed financial outbox state
        List<com.finx.outbox.entity.FinancialOutboxMessage> outboxList = outboxRepo.findAll().stream()
                .filter(m -> m.getReleaseInstructionId().equals(instruction.getId()))
                .toList();
        assertThat(outboxList).hasSize(1);
        com.finx.outbox.entity.FinancialOutboxMessage outboxMsg = outboxList.get(0);
        assertThat(outboxMsg.getReleaseInstructionId()).isEqualTo(instruction.getId());
        assertThat(outboxMsg.getStatus()).isEqualTo(com.finx.outbox.model.FinancialOutboxStatus.PENDING);
        assertThat(outboxMsg.getEventType()).isEqualTo(com.finx.outbox.model.FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED);

        EscrowAccount committedEscrow = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(committedEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("100000.0000"));
        assertThat(committedEscrow.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("5. Concurrent same instruction results in exactly one reservation and one outbox row")
    void test05_concurrentSameInstructionSingleReservation() throws Exception {
        EngineDecision decision = setupCompleteReleaseDecisionForMilestone(milestone1);
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(decision.getId(), "idem-same-instruction");
        ReleaseInstruction instruction = instructionService.createFromReleaseDecision(cmd, adminPrincipal);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        List<Future<ReleaseReservation>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal);
            }));
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        Set<UUID> reservationIds = new HashSet<>();
        for (Future<ReleaseReservation> f : futures) {
            reservationIds.add(f.get().getId());
        }

        // All threads must return the exact same reservation
        assertThat(reservationIds).hasSize(1);

        // Exactly one reservation row in PostgreSQL
        List<ReleaseReservation> reservationsInDb = reservationRepo.findAll();
        long activeCount = reservationsInDb.stream()
                .filter(r -> r.getReleaseInstructionId().equals(instruction.getId()))
                .count();
        assertThat(activeCount).isEqualTo(1);

        // Instruction final state is QUEUED
        ReleaseInstruction finalInstruction = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(finalInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        // Exactly one PENDING outbox message for this instruction
        List<com.finx.outbox.entity.FinancialOutboxMessage> outboxForInstruction = outboxRepo.findAll().stream()
                .filter(o -> o.getReleaseInstructionId().equals(instruction.getId()))
                .toList();
        assertThat(outboxForInstruction).hasSize(1);
        assertThat(outboxForInstruction.get(0).getStatus()).isEqualTo(com.finx.outbox.model.FinancialOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("6-15. Concurrent oversubscription: exactly one succeeds, available funds never negative, zero ledger/escrow writes")
    void test06_concurrentOversubscriptionSerializedByEscrowLock() throws Exception {
        // Milestone 1 (70,000) and Milestone 2 (70,000) both on Escrow (100,000). Total requested: 140,000 > 100,000.
        EngineDecision decision1 = setupCompleteReleaseDecisionForMilestone(milestone1);
        EngineDecision decision2 = setupCompleteReleaseDecisionForMilestone(milestone2);

        ReleaseInstruction instruction1 = instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decision1.getId(), "idem-m1"), adminPrincipal);
        ReleaseInstruction instruction2 = instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decision2.getId(), "idem-m2"), adminPrincipal);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        List<Throwable> caughtExceptions = new CopyOnWriteArrayList<>();

        Future<?> f1 = executor.submit(() -> {
            try {
                startLatch.await();
                authorizationService.authorizeAndReserve(instruction1.getId(), adminPrincipal);
                successes.incrementAndGet();
            } catch (Throwable e) {
                caughtExceptions.add(e);
                failures.incrementAndGet();
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                startLatch.await();
                authorizationService.authorizeAndReserve(instruction2.getId(), adminPrincipal);
                successes.incrementAndGet();
            } catch (Throwable e) {
                caughtExceptions.add(e);
                failures.incrementAndGet();
            }
        });

        startLatch.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        if (successes.get() != 1) {
            for (Throwable t : caughtExceptions) {
                t.printStackTrace();
            }
        }

        // Exactly one succeeds, one fails
        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        // Escrow balance remains exactly 100,000
        EscrowAccount finalEscrow = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(finalEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("100000.0000"));
        assertThat(finalEscrow.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);

        // Sum of active reservations is exactly 70,000 (never 140,000)
        BigDecimal activeReservedSum = reservationRepo.sumActiveReservationsForEscrow(escrowAccount.getId());
        assertThat(activeReservedSum).isEqualByComparingTo(new BigDecimal("70000.0000"));

        // Available funds never negative
        BigDecimal availableToReserve = finalEscrow.getBalance().subtract(activeReservedSum);
        assertThat(availableToReserve).isEqualByComparingTo(new BigDecimal("30000.0000"));
        assertThat(availableToReserve).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Verify outbox rows: exactly 1 PENDING outbox for the winning instruction, 0 for the losing instruction
        ReleaseInstruction inst1 = instructionRepo.findById(instruction1.getId()).orElseThrow();
        ReleaseInstruction inst2 = instructionRepo.findById(instruction2.getId()).orElseThrow();
        if (inst1.getStatus() == ReleaseInstructionStatus.QUEUED) {
            assertThat(inst2.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
            assertThat(outboxRepo.findAll().stream().filter(o -> o.getReleaseInstructionId().equals(instruction1.getId())).count()).isEqualTo(1);
            assertThat(outboxRepo.findAll().stream().filter(o -> o.getReleaseInstructionId().equals(instruction2.getId())).count()).isEqualTo(0);
        } else {
            assertThat(inst1.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
            assertThat(inst2.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
            assertThat(outboxRepo.findAll().stream().filter(o -> o.getReleaseInstructionId().equals(instruction2.getId())).count()).isEqualTo(1);
            assertThat(outboxRepo.findAll().stream().filter(o -> o.getReleaseInstructionId().equals(instruction1.getId())).count()).isEqualTo(0);
        }

        // Zero JournalEntry writes
        assertThat(journalEntryRepo.findAll()).isEmpty();

        // Zero SettlementInstruction writes
        assertThat(settlementInstructionRepo.findAll()).isEmpty();
    }
}
