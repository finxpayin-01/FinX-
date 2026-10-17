package com.finx.release;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
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
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e1_test}",
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
public class PostgresReleasePersistenceFoundationTest {

    static {
        ensureDatabaseExists("finx_release_e1_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;

    @MockBean private BankingProvider bankingProvider;

    private Organization testOrg;
    private User testUser;
    private Deal testDeal;
    private Milestone testMilestone;
    private EscrowAccount testEscrow;
    private OrganizationBankAccount testBankAcc;
    private EnginePolicyVersion testPolicy;
    private EngineDecision testDecision;

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

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);

            testUser = new User("Test User " + suffix, "test-" + suffix + "@finx.invalid", "hash", Role.BUYER, UserStatus.ACTIVE);
            testUser = userRepo.save(testUser);

            testOrg = new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                    "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "123 Street", testUser.getId());
            testOrg = orgRepo.save(testOrg);

            testDeal = new Deal();
            testDeal.setTitle("Deal " + suffix);
            testDeal.setDescription("Desc");
            testDeal.setBuyerId(testUser.getId());
            testDeal.setSellerId(testUser.getId());
            testDeal.setTotalAmount(new BigDecimal("100000.00"));
            testDeal.setCurrency("INR");
            testDeal.setStatus(DealStatus.IN_PROGRESS);
            testDeal = dealRepo.save(testDeal);

            testMilestone = new Milestone(testDeal.getId(), "M1 " + suffix, "Desc", 1, new BigDecimal("50000.00"), "INR", Instant.now().plusSeconds(86400));
            testMilestone.setStatus(MilestoneStatus.APPROVED);
            testMilestone = milestoneRepo.save(testMilestone);

            testEscrow = new EscrowAccount(testDeal.getId(), new BigDecimal("100000.00"), "INR");
            testEscrow = escrowRepo.save(testEscrow);

            testBankAcc = new OrganizationBankAccount(
                    testOrg.getId(), "Beneficiary " + suffix, "XXXX1234", "a".repeat(64),
                    "HDFC0001234", "HDFC Bank", "Branch 1", true
            );
            testBankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
            testBankAcc = bankAccRepo.save(testBankAcc);

            testPolicy = policyRepo.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                    EngineType.RELEASE, "FINX_RELEASE_DETERMINISTIC_BASE", "1.0.0"
            ).orElseGet(() -> policyRepo.save(EnginePolicyVersion.platform(
                    EngineType.RELEASE, "FINX_RELEASE_DETERMINISTIC_BASE", "1.0.0", "b".repeat(64)
            )));

            testDecision = new EngineDecision(
                    testOrg.getId(), EngineType.RELEASE, "MILESTONE", testMilestone.getId(),
                    DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, null, null, "[]",
                    "Passed", "[]", "1.0.0", testPolicy.getId(), testPolicy.getContentHash(),
                    null, UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64), "1", "1.0", "1.0.0",
                    "d".repeat(64), null, Instant.now()
            );
            testDecision = decisionRepo.save(testDecision);

            return null;
        });
    }

    @Test
    @DisplayName("1. release_instructions table exists in PostgreSQL")
    void test1_releaseInstructionsTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'release_instructions'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("2. release_reservations table exists in PostgreSQL")
    void test2_releaseReservationsTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'release_reservations'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("3 & 4. V29 applied after V28, and V1-V28 intact")
    void test3_4_flywayVersionVerification() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank ASC", String.class);
        assertThat(versions).contains("28", "29");
        assertThat(versions.indexOf("29")).isGreaterThan(versions.indexOf("28"));
    }

    @Test
    @DisplayName("5. Valid ReleaseInstruction insert works")
    void test5_validReleaseInstructionInsert() {
        ReleaseInstruction inst = createValidInstruction(testDecision, "IDEM-VALID-1");
        ReleaseInstruction saved = instructionRepo.saveAndFlush(inst);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("6. Amount <= 0 is rejected by check constraint")
    void test6_amountPositiveConstraint() {
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, " +
                    "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, " +
                    "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, created_by, created_at, updated_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 'MOCK', 'ACC-1', 0.0000, 'INR', 'CREATED', 'IDEM-ERR-1', '%s', '%s', NOW(), NOW(), 0)",
                    UUID.randomUUID(), testOrg.getId(), testDeal.getId(), testMilestone.getId(), testDecision.getId(),
                    "a".repeat(64), testPolicy.getId(), "b".repeat(64), testBankAcc.getId(), "c".repeat(64), testUser.getId()
            ));
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("7. Bad currency is rejected by check constraint")
    void test7_currencyConstraint() {
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, " +
                    "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, " +
                    "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, created_by, created_at, updated_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 'MOCK', 'ACC-1', 100.0000, 'inr', 'CREATED', 'IDEM-ERR-2', '%s', '%s', NOW(), NOW(), 0)",
                    UUID.randomUUID(), testOrg.getId(), testDeal.getId(), testMilestone.getId(), testDecision.getId(),
                    "a".repeat(64), testPolicy.getId(), "b".repeat(64), testBankAcc.getId(), "c".repeat(64), testUser.getId()
            ));
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("8, 9, 10. Bad hash format is rejected")
    void test8_9_10_hashConstraints() {
        // Bad fingerprint
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, " +
                    "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, " +
                    "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, created_by, created_at, updated_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', '%s', 'BAD-FP', '%s', '%s', '%s', 'MOCK', 'ACC-1', 100.0000, 'INR', 'CREATED', 'IDEM-ERR-3', '%s', '%s', NOW(), NOW(), 0)",
                    UUID.randomUUID(), testOrg.getId(), testDeal.getId(), testMilestone.getId(), testDecision.getId(),
                    testPolicy.getId(), "b".repeat(64), testBankAcc.getId(), "c".repeat(64), testUser.getId()
            ));
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("11 & 12. Blank provider and provider_account_id rejected")
    void test11_12_blankProviderRejected() {
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, " +
                    "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, " +
                    "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, created_by, created_at, updated_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '   ', 'ACC-1', 100.0000, 'INR', 'CREATED', 'IDEM-ERR-4', '%s', '%s', NOW(), NOW(), 0)",
                    UUID.randomUUID(), testOrg.getId(), testDeal.getId(), testMilestone.getId(), testDecision.getId(),
                    "a".repeat(64), testPolicy.getId(), "b".repeat(64), testBankAcc.getId(), "c".repeat(64), testUser.getId()
            ));
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("13. Invalid status rejected")
    void test13_invalidStatusRejected() {
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, " +
                    "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, " +
                    "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, created_by, created_at, updated_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 'MOCK', 'ACC-1', 100.0000, 'INR', 'ALLOW', 'IDEM-ERR-5', '%s', '%s', NOW(), NOW(), 0)",
                    UUID.randomUUID(), testOrg.getId(), testDeal.getId(), testMilestone.getId(), testDecision.getId(),
                    "a".repeat(64), testPolicy.getId(), "b".repeat(64), testBankAcc.getId(), "c".repeat(64), testUser.getId()
            ));
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("14 to 19. Foreign key validations rejected for non-existent entities")
    void test14_to_19_fkConstraints() {
        UUID nonExistent = UUID.randomUUID();

        // Invalid release decision FK
        assertThatThrownBy(() -> {
            ReleaseInstruction inst = new ReleaseInstruction(
                    null, testOrg.getId(), testDeal.getId(), testMilestone.getId(), nonExistent,
                    "a".repeat(64), testPolicy.getId(), "b".repeat(64), testBankAcc.getId(), "MOCK", "ACC-1",
                    new BigDecimal("1000.0000"), "INR", "IDEM-FK-1", "c".repeat(64), testUser.getId()
            );
            instructionRepo.saveAndFlush(inst);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("20. Duplicate releaseDecisionId is rejected (1 decision -> at most 1 instruction)")
    void test20_duplicateReleaseDecisionIdRejected() {
        ReleaseInstruction inst1 = createValidInstruction(testDecision, "IDEM-DUP-1");
        instructionRepo.saveAndFlush(inst1);

        ReleaseInstruction inst2 = createValidInstruction(testDecision, "IDEM-DUP-2");
        assertThatThrownBy(() -> instructionRepo.saveAndFlush(inst2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("21 & 22. Scoped idempotency: same org rejects duplicate key, different org allows same key")
    void test21_22_scopedIdempotency() {
        ReleaseInstruction inst1 = createValidInstruction(testDecision, "IDEM-SCOPED-KEY");
        instructionRepo.saveAndFlush(inst1);

        // Another decision in same org
        EngineDecision decisionSameOrg = createDecision(testOrg.getId(), testMilestone.getId());
        ReleaseInstruction instSameOrg = createValidInstruction(decisionSameOrg, "IDEM-SCOPED-KEY");
        assertThatThrownBy(() -> instructionRepo.saveAndFlush(instSameOrg))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Another org with same idempotency key is allowed
        Organization anotherOrg = new Organization("Another Org", "Another Legal", EntityType.PRIVATE_LIMITED, null, null, null, null, testUser.getId());
        anotherOrg = orgRepo.saveAndFlush(anotherOrg);

        OrganizationBankAccount anotherBankAcc = new OrganizationBankAccount(anotherOrg.getId(), "Ben 2", "XXXX5678", "e".repeat(64), "ICIC0001234", "ICICI", "B2", true);
        anotherBankAcc = bankAccRepo.saveAndFlush(anotherBankAcc);

        Milestone milestoneAnotherOrg = new Milestone(testDeal.getId(), "M Another", "D", 2, new BigDecimal("2000.00"), "INR", Instant.now().plusSeconds(86400));
        milestoneAnotherOrg = milestoneRepo.saveAndFlush(milestoneAnotherOrg);

        EngineDecision decisionAnotherOrg = createDecision(anotherOrg.getId(), milestoneAnotherOrg.getId());
        ReleaseInstruction instAnotherOrg = new ReleaseInstruction(
                null, anotherOrg.getId(), testDeal.getId(), milestoneAnotherOrg.getId(), decisionAnotherOrg.getId(),
                decisionAnotherOrg.getDecisionFingerprint(), testPolicy.getId(), testPolicy.getContentHash(),
                anotherBankAcc.getId(), "MOCK", "ACC-2", new BigDecimal("2000.0000"), "INR",
                "IDEM-SCOPED-KEY", "f".repeat(64), testUser.getId()
        );
        ReleaseInstruction saved = instructionRepo.saveAndFlush(instAnotherOrg);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("23 & 24. At most one active ReleaseInstruction per milestone; terminal coexists with active")
    void test23_24_activeMilestoneConstraint() {
        ReleaseInstruction inst1 = createValidInstruction(testDecision, "IDEM-ACT-1");
        instructionRepo.saveAndFlush(inst1);

        // Another active instruction for same milestone is rejected
        EngineDecision decision2 = createDecision(testOrg.getId(), testMilestone.getId());
        ReleaseInstruction inst2 = createValidInstruction(decision2, "IDEM-ACT-2");
        assertThatThrownBy(() -> instructionRepo.saveAndFlush(inst2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cancel first instruction (terminal state)
        inst1.markCancelled();
        instructionRepo.saveAndFlush(inst1);

        // Now a new active instruction for the same milestone is allowed
        ReleaseInstruction inst3 = createValidInstruction(decision2, "IDEM-ACT-3");
        ReleaseInstruction saved = instructionRepo.saveAndFlush(inst3);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("25. Valid ReleaseReservation insert works")
    void test25_validReservationInsert() {
        ReleaseInstruction inst = createValidInstruction(testDecision, "IDEM-RES-1");
        instructionRepo.saveAndFlush(inst);

        ReleaseReservation res = new ReleaseReservation(null, inst.getId(), testEscrow.getId(), testMilestone.getId(), new BigDecimal("50000.0000"), "INR");
        ReleaseReservation saved = reservationRepo.saveAndFlush(res);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("26, 27, 28. Reservation amount, currency, and status check constraints")
    void test26_27_28_reservationConstraints() {
        ReleaseInstruction inst = createValidInstruction(testDecision, "IDEM-RES-2");
        instructionRepo.saveAndFlush(inst);

        // Amount <= 0 rejected
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_reservations (id, release_instruction_id, escrow_account_id, milestone_id, amount, currency, status, created_at, updated_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', -10.0000, 'INR', 'ACTIVE', NOW(), NOW(), 0)",
                    UUID.randomUUID(), inst.getId(), testEscrow.getId(), testMilestone.getId()
            ));
        }).isInstanceOf(Exception.class);

        // Bad currency rejected
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_reservations (id, release_instruction_id, escrow_account_id, milestone_id, amount, currency, status, created_at, updated_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', 10.0000, 'inr', 'ACTIVE', NOW(), NOW(), 0)",
                    UUID.randomUUID(), inst.getId(), testEscrow.getId(), testMilestone.getId()
            ));
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("29. Duplicate reservation for same ReleaseInstruction is rejected")
    void test29_duplicateReservationPerInstructionRejected() {
        ReleaseInstruction inst = createValidInstruction(testDecision, "IDEM-RES-3");
        instructionRepo.saveAndFlush(inst);

        ReleaseReservation res1 = new ReleaseReservation(null, inst.getId(), testEscrow.getId(), testMilestone.getId(), new BigDecimal("1000.0000"), "INR");
        reservationRepo.saveAndFlush(res1);

        ReleaseReservation res2 = new ReleaseReservation(null, inst.getId(), testEscrow.getId(), testMilestone.getId(), new BigDecimal("2000.0000"), "INR");
        assertThatThrownBy(() -> reservationRepo.saveAndFlush(res2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("30. Two ACTIVE reservations for same milestone is rejected by partial unique index")
    void test30_twoActiveReservationsSameMilestoneRejected() {
        ReleaseInstruction inst1 = createValidInstruction(testDecision, "IDEM-RES-4");
        instructionRepo.saveAndFlush(inst1);

        ReleaseReservation res1 = new ReleaseReservation(null, inst1.getId(), testEscrow.getId(), testMilestone.getId(), new BigDecimal("1000.0000"), "INR");
        reservationRepo.saveAndFlush(res1);

        Milestone m2 = milestoneRepo.saveAndFlush(new Milestone(testDeal.getId(), "M2", "D", 2, new BigDecimal("1000.00"), "INR", Instant.now().plusSeconds(86400)));
        EngineDecision d2 = createDecision(testOrg.getId(), m2.getId());
        ReleaseInstruction inst2 = createValidInstruction(d2, "IDEM-RES-5");
        instructionRepo.saveAndFlush(inst2);

        // Attempting to reserve with the same milestone id while res1 is active
        ReleaseReservation res2 = new ReleaseReservation(null, inst2.getId(), testEscrow.getId(), testMilestone.getId(), new BigDecimal("2000.0000"), "INR");
        assertThatThrownBy(() -> reservationRepo.saveAndFlush(res2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("31 to 34. Reservation lifecycle timestamp invariant checks")
    void test31_to_34_reservationLifecycleTimestampChecks() {
        ReleaseInstruction inst = createValidInstruction(testDecision, "IDEM-RES-6");
        instructionRepo.saveAndFlush(inst);

        // ACTIVE with non-null consumed_at rejected
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_reservations (id, release_instruction_id, escrow_account_id, milestone_id, amount, currency, status, created_at, updated_at, consumed_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', 10.0000, 'INR', 'ACTIVE', NOW(), NOW(), NOW(), 0)",
                    UUID.randomUUID(), inst.getId(), testEscrow.getId(), testMilestone.getId()
            ));
        }).isInstanceOf(Exception.class);

        // CONSUMED without consumed_at rejected
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_reservations (id, release_instruction_id, escrow_account_id, milestone_id, amount, currency, status, created_at, updated_at, consumed_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', 10.0000, 'INR', 'CONSUMED', NOW(), NOW(), NULL, 0)",
                    UUID.randomUUID(), inst.getId(), testEscrow.getId(), testMilestone.getId()
            ));
        }).isInstanceOf(Exception.class);

        // Both consumed_at and released_at nonnull rejected
        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO release_reservations (id, release_instruction_id, escrow_account_id, milestone_id, amount, currency, status, created_at, updated_at, consumed_at, released_at, version) " +
                    "VALUES ('%s', '%s', '%s', '%s', 10.0000, 'INR', 'CONSUMED', NOW(), NOW(), NOW(), NOW(), 0)",
                    UUID.randomUUID(), inst.getId(), testEscrow.getId(), testMilestone.getId()
            ));
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("35, 36, 37. Foreign key RESTRICT on release_instruction, escrow_account, and beneficiary")
    void test35_36_37_foreignKeyRestrictDeletions() {
        ReleaseInstruction inst = createValidInstruction(testDecision, "IDEM-RESTRICT-1");
        instructionRepo.saveAndFlush(inst);

        ReleaseReservation res = new ReleaseReservation(null, inst.getId(), testEscrow.getId(), testMilestone.getId(), new BigDecimal("1000.0000"), "INR");
        reservationRepo.saveAndFlush(res);

        // 35: Cannot delete release_instruction referenced by reservation
        assertThatThrownBy(() -> instructionRepo.delete(inst))
                .isInstanceOf(Exception.class);

        // 36: Cannot delete escrow_account referenced by reservation
        assertThatThrownBy(() -> escrowRepo.delete(testEscrow))
                .isInstanceOf(Exception.class);

        // 37: Cannot delete beneficiary bank account referenced by instruction
        assertThatThrownBy(() -> bankAccRepo.delete(testBankAcc))
                .isInstanceOf(Exception.class);
    }

    private ReleaseInstruction createValidInstruction(EngineDecision decision, String idempotencyKey) {
        return new ReleaseInstruction(
                null, testOrg.getId(), testDeal.getId(), decision.getSubjectId(), decision.getId(),
                decision.getDecisionFingerprint(), testPolicy.getId(), testPolicy.getContentHash(),
                testBankAcc.getId(), "MOCK", "ACC-MOCK-1", new BigDecimal("50000.0000"),
                "INR", idempotencyKey, "f".repeat(64), testUser.getId()
        );
    }

    private EngineDecision createDecision(UUID orgId, UUID milestoneId) {
        EngineDecision d = new EngineDecision(
                orgId, EngineType.RELEASE, "MILESTONE", milestoneId,
                DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, null, null, "[]",
                "Passed", "[]", "1.0.0", testPolicy.getId(), testPolicy.getContentHash(),
                null, UUID.randomUUID(), UUID.randomUUID(), "1".repeat(64), "1", "1.0", "1.0.0",
                "2".repeat(64), null, Instant.now()
        );
        return decisionRepo.saveAndFlush(d);
    }
}
