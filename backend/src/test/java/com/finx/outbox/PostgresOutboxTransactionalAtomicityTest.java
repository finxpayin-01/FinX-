package com.finx.outbox;

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
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.outbox.service.FinancialOutboxService;
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
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e3_test}",
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
public class PostgresOutboxTransactionalAtomicityTest {

    static {
        ensureDatabaseExists("finx_release_e3_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @Autowired private ReleaseInstructionService instructionService;
    @Autowired private ReleaseAuthorizationService authorizationService;
    @SpyBean private FinancialOutboxRepository outboxRepo;
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
    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private SettlementInstructionRepository settlementInstructionRepo;

    @MockBean private BankingProvider bankingProvider;
    @SpyBean private ReleaseDecisionEngine releaseDecisionEngine;
    @Autowired private FinancialOutboxService outboxService;

    private User adminUser;
    private User buyerUser;
    private User sellerUser;
    private UserPrincipal adminPrincipal;

    private Organization buyerOrg;
    private Organization sellerOrg;
    private OrganizationBankAccount sellerBankAcc;

    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;
    private ReleaseInstruction instruction;
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
                st.execute("CREATE DATABASE " + dbName);
            }
        } catch (SQLException e) {
            // Already exists
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
            deal.setTitle("E3 Real Postgres Atomicity Deal " + suffix);
            deal.setDescription("Test deal");
            deal.setBuyerId(buyerUser.getId());
            deal.setSellerId(sellerUser.getId());
            deal.setBuyerOrganizationId(buyerOrg.getId());
            deal.setSellerOrganizationId(sellerOrg.getId());
            deal.setTotalAmount(new BigDecimal("100000.0000"));
            deal.setCurrency("INR");
            deal.setStatus(DealStatus.FUNDED);
            deal = dealRepo.save(deal);

            Instant nowTrunc = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant dueDate = nowTrunc.plusSeconds(86400);

            milestone = new Milestone(deal.getId(), "M1 " + suffix, "First Milestone", 1, new BigDecimal("70000.0000"), "INR", dueDate, dueDate);
            milestone.setStatus(MilestoneStatus.APPROVED);
            milestone = milestoneRepo.saveAndFlush(milestone);

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

            // 1. Deliverables & Durable milestone approval binding
            Deliverable del = new Deliverable(milestone.getId(), buyerUser.getId(), "del.pdf", "https://del.pdf", "Artifact");
            del.setStatus(com.finx.milestone.entity.DeliverableStatus.APPROVED);
            del = deliverableRepo.saveAndFlush(del);
            Milestone reloadedMilestone = milestoneRepo.findById(milestone.getId()).orElseThrow();
            Instant now = nowTrunc;
            Deliverable reloadedDel = deliverableRepo.findById(del.getId()).orElseThrow();

            String delFp = fingerprintService.computeDeliverablesFingerprint(List.of(reloadedDel));
            String mFp = fingerprintService.computeMilestoneFingerprint(reloadedMilestone);
            String appFp = fingerprintService.computeApprovalFingerprint(
                    buyerOrg.getId(), deal.getId(), reloadedMilestone.getId(),
                    buyerUser.getId(), MilestoneApprovalAuthority.BUYER, null,
                    mFp, delFp, now
            );

            MilestoneApprovalBinding binding = new MilestoneApprovalBinding(
                    UUID.randomUUID(),
                    buyerOrg.getId(),
                    deal.getId(),
                    reloadedMilestone.getId(),
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
            UUID evalId = UUID.randomUUID();
            UUID corrId = UUID.randomUUID();
            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(Map.of("sourceType", "ORGANIZATION_BANK_ACCOUNT", "sourceId", sellerBankAcc.getId().toString(), "provenance", "MOCK"));

            for (EngineType engineType : ReleasePolicyDefinition.REQUIRED_ENGINES) {
                EngineDecision comp = new EngineDecision();
                setField(comp, "organizationId", buyerOrg.getId());
                setField(comp, "engineType", engineType);
                setField(comp, "subjectType", "MILESTONE");
                setField(comp, "subjectId", milestone.getId());
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
                    .subjectId(milestone.getId())
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

            doAnswer(inv -> evalResult).when(releaseDecisionEngine)
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
            setField(releaseDecision, "subjectId", milestone.getId());
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

            releaseDecision = decisionRepo.save(releaseDecision);

            // 4. Create ReleaseInstruction in CREATED status
            CreateReleaseInstructionCommand insCmd = new CreateReleaseInstructionCommand(releaseDecision.getId(), "idem-atomic-" + suffix);
            instruction = instructionService.createFromReleaseDecision(insCmd, adminPrincipal);

            return null;
        });
    }

    @Test
    @DisplayName("Test A — Successful TX2 commits QUEUED instruction + ACTIVE reservation + PENDING outbox")
    void testA_successfulTx2CommitAtomicity() {
        // Starting state
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        assertThat(reservationRepo.findByReleaseInstructionId(instruction.getId())).isEmpty();
        assertThat(outboxRepo.findByReleaseInstructionIdAndEventType(instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED)).isEmpty();

        long initialJournals = journalRepo.count();
        long initialSettlements = settlementInstructionRepo.count();
        BigDecimal initialBalance = escrowAccount.getBalance();

        // Execute authorizeAndReserve
        ReleaseReservation reservation = authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal);

        assertThat(reservation).isNotNull();
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        // Verify committed state
        ReleaseInstruction committedInstruction = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(committedInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        assertThat(committedInstruction.getAuthorizedBy()).isEqualTo(adminUser.getId());

        FinancialOutboxMessage outbox = outboxRepo.findByReleaseInstructionIdAndEventType(
                instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED
        ).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.PENDING);
        assertThat(outbox.getOperationKey()).isEqualTo(instruction.getId().toString());
        assertThat(outbox.getPayloadVersion()).isEqualTo(1);
        assertThat(outbox.getAttemptCount()).isEqualTo(0);
        assertThat(outbox.getLockedAt()).isNull();

        // 0 balance change, 0 journal writes, 0 settlement instructions, 0 bank calls
        EscrowAccount reloadedEscrow = escrowRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(reloadedEscrow.getBalance()).isEqualByComparingTo(initialBalance);
        assertThat(journalRepo.count()).isEqualTo(initialJournals);
        assertThat(settlementInstructionRepo.count()).isEqualTo(initialSettlements);
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("Test B — Outbox failure inside TX2 rolls back instruction authorization and reservation insert")
    void testB_outboxFailureRollsBackEntireTx2() {
        doThrow(new RuntimeException("Simulated outbox disk/DB failure during enqueue"))
                .when(outboxRepo).save(any(FinancialOutboxMessage.class));

        try {
            assertThatThrownBy(() -> authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Simulated outbox disk/DB failure");

            // Verified rolled back
            ReleaseInstruction reloaded = instructionRepo.findById(instruction.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
            assertThat(reloaded.getAuthorizedBy()).isNull();

            assertThat(reservationRepo.findByReleaseInstructionId(instruction.getId())).isEmpty();
            assertThat(outboxRepo.findByReleaseInstructionIdAndEventType(instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED)).isEmpty();

            verifyNoInteractions(bankingProvider);
        } finally {
            reset(outboxRepo);
        }
    }

    @Test
    @DisplayName("Part AR — Real PostgreSQL idempotent replay")
    void testAr_realPostgresIdempotentReplay() {
        ReleaseReservation res1 = authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal);
        ReleaseReservation res2 = authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal);

        assertThat(res1.getId()).isEqualTo(res2.getId());

        ReleaseInstruction reloaded = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        long resCount = reservationRepo.findAll().stream()
                .filter(r -> r.getReleaseInstructionId().equals(instruction.getId()))
                .count();
        assertThat(resCount).isEqualTo(1);

        long outboxCount = outboxRepo.countByReleaseInstructionIdAndEventType(
                instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Part AT — Concurrent duplicate enqueue blocked: exactly 1 outbox row")
    void testAt_concurrentEnqueueBlocked() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                authorizationService.authorizeAndReserve(instruction.getId(), adminPrincipal);
                successCount.incrementAndGet();
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threads);

        ReleaseInstruction reloaded = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        long outboxCount = outboxRepo.countByReleaseInstructionIdAndEventType(
                instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED
        );
        assertThat(outboxCount).isEqualTo(1);
    }
}
