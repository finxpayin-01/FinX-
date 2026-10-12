package com.finx.outbox;

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
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
public class PostgresFinancialOutboxSchemaTest {

    static {
        ensureDatabaseExists("finx_release_e3_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;

    @MockBean private BankingProvider bankingProvider;

    private ReleaseInstruction testInstruction;

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
            // Already exists or permission denied
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");

        Organization org = new Organization();
        org.setName("Outbox Test Org " + UUID.randomUUID().toString().substring(0, 8));
        org.setLegalName("Outbox Legal Org");
        org.setEntityType(EntityType.PRIVATE_LIMITED);
        org.setRegistrationNumber("REG-OB-" + UUID.randomUUID().toString().substring(0, 8));
        org = orgRepo.save(org);

        User user = new User();
        user.setEmail("outbox_user_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        user.setName("Outbox User");
        user.setPasswordHash("hashed_pw");
        user.setRole(Role.ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        user = userRepo.save(user);

        Deal deal = new Deal();
        deal.setTitle("Outbox Test Deal");
        deal.setCurrency("INR");
        deal.setTotalAmount(BigDecimal.valueOf(100000));
        deal.setStatus(DealStatus.FUNDED);
        deal.setBuyerId(user.getId());
        deal.setSellerId(user.getId());
        deal.setBuyerOrganizationId(org.getId());
        deal.setSellerOrganizationId(org.getId());
        deal = dealRepo.save(deal);

        Milestone milestone = new Milestone();
        milestone.setDealId(deal.getId());
        milestone.setTitle("Outbox Milestone");
        milestone.setAmount(BigDecimal.valueOf(50000));
        milestone.setCurrency("INR");
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setSequence(1);
        milestone = milestoneRepo.save(milestone);

        EscrowAccount escrow = new EscrowAccount();
        escrow.setDealId(deal.getId());
        escrow.setBalance(BigDecimal.valueOf(100000));
        escrow.setCurrency("INR");
        escrow.setTotalFunded(BigDecimal.valueOf(100000));
        escrow.setTotalReleased(BigDecimal.ZERO);
        escrow = escrowRepo.save(escrow);

        OrganizationBankAccount bankAcc = new OrganizationBankAccount();
        bankAcc.setOrganizationId(org.getId());
        bankAcc.setAccountHolderName("Outbox Vendor");
        bankAcc.setAccountNumberMasked("XXXX1234");
        bankAcc.setAccountNumberHash("hash1234");
        bankAcc.setIfscCode("HDFC0001234");
        bankAcc.setBankName("HDFC Bank");
        bankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAcc = bankAccRepo.save(bankAcc);

        EnginePolicyVersion policy = policyRepo.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, "FINX_RELEASE_E3_POLICY", "1.0.0"
        ).orElseGet(() -> policyRepo.save(EnginePolicyVersion.platform(
                EngineType.RELEASE, "FINX_RELEASE_E3_POLICY", "1.0.0", "b".repeat(64)
        )));

        EngineDecision decision = new EngineDecision(
                org.getId(), EngineType.RELEASE, "MILESTONE", milestone.getId(),
                DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, null, null, "[]",
                "Passed", "[]", "1.0.0", policy.getId(), policy.getContentHash(),
                null, UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64), "1", "1.0", "1.0.0",
                "d".repeat(64), null, Instant.now()
        );
        decision = decisionRepo.save(decision);

        testInstruction = new ReleaseInstruction(
                UUID.randomUUID(),
                org.getId(),
                deal.getId(),
                milestone.getId(),
                decision.getId(),
                decision.getInputFingerprint(),
                policy.getId(),
                policy.getContentHash(),
                bankAcc.getId(),
                "AXIS",
                "POOL-ACC-1",
                milestone.getAmount(),
                milestone.getCurrency(),
                "IDEM-OB-" + UUID.randomUUID(),
                "a".repeat(64),
                user.getId()
        );
        testInstruction.markAuthorized(user.getId(), Instant.now());
        testInstruction.markReserved();
        testInstruction = instructionRepo.save(testInstruction);
    }

    @Test
    @DisplayName("1. Verify PostgreSQL product name")
    void test01_postgresProductVerified() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }

    @Test
    @DisplayName("2. Verify financial_outbox table exists")
    void test02_financialOutboxTableExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, null, "financial_outbox", new String[]{"TABLE"})) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    @DisplayName("3. Foreign key to release_instructions exists")
    void test03_foreignKeyExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getImportedKeys(null, null, "financial_outbox")) {
            boolean fkFound = false;
            while (rs.next()) {
                if ("release_instructions".equalsIgnoreCase(rs.getString("PKTABLE_NAME"))
                        && "release_instruction_id".equalsIgnoreCase(rs.getString("FKCOLUMN_NAME"))) {
                    fkFound = true;
                    break;
                }
            }
            assertThat(fkFound).isTrue();
        }
    }

    @Test
    @DisplayName("4. Deleting ReleaseInstruction referenced by outbox is blocked by ON DELETE RESTRICT")
    void test04_deleteInstructionBlockedByForeignKey() {
        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(testInstruction, Instant.now());
        outboxRepo.save(message);

        assertThatThrownBy(() -> instructionRepo.delete(testInstruction))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("5. Valid PENDING event insert succeeds")
    void test05_validPendingEventInsert() {
        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(testInstruction, Instant.now());
        FinancialOutboxMessage saved = outboxRepo.save(message);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(FinancialOutboxStatus.PENDING);
        assertThat(saved.getEventType()).isEqualTo(FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED);
        assertThat(saved.getOperationKey()).isEqualTo(testInstruction.getId().toString());
    }

    @Test
    @DisplayName("6. Unsupported event type rejected by CHECK constraint")
    void test06_unsupportedEventTypeRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'UNSUPPORTED_EVENT', ?, 1, 'PENDING', 0, NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("7. Invalid status rejected by CHECK constraint")
    void test07_invalidStatusRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'INVALID_STATUS', 0, NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("8. Blank operation key rejected by CHECK constraint")
    void test08_blankOperationKeyRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', '   ', 1, 'PENDING', 0, NOW(), NOW(), NOW())",
                id, testInstruction.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("9. Payload version <= 0 rejected by CHECK constraint")
    void test09_nonPositivePayloadVersionRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 0, 'PENDING', 0, NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("10. Negative attempt count rejected by CHECK constraint")
    void test10_negativeAttemptCountRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'PENDING', -1, NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("11. Duplicate (release_instruction_id, event_type) rejected by UNIQUE constraint")
    void test11_duplicateReleaseInstructionAndEventTypeRejected() {
        FinancialOutboxMessage m1 = FinancialOutboxMessage.createReleaseDispatch(testInstruction, Instant.now());
        outboxRepo.save(m1);

        UUID secondId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'PENDING', 0, NOW(), NOW(), NOW())",
                secondId, testInstruction.getId(), "OP-KEY-DIFFERENT"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("12. Duplicate (event_type, operation_key) rejected by UNIQUE constraint")
    void test12_duplicateEventTypeAndOperationKeyRejected() {
        FinancialOutboxMessage m1 = FinancialOutboxMessage.createReleaseDispatch(testInstruction, Instant.now());
        outboxRepo.save(m1);

        UUID secondId = UUID.randomUUID();
        UUID otherInstructionId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'PENDING', 0, NOW(), NOW(), NOW())",
                secondId, otherInstructionId, testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("13. PENDING with lock fields rejected by lifecycle CHECK constraint")
    void test13_pendingWithLockFieldsRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, locked_at, locked_by, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'PENDING', 0, NOW(), NOW(), 'worker-node-1', NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("14. LEASED without lock fields rejected by lifecycle CHECK constraint")
    void test14_leasedWithoutLockFieldsRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'LEASED', 0, NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("15. PROCESSED without processed_at rejected by lifecycle CHECK constraint")
    void test15_processedWithoutProcessedAtRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'PROCESSED', 0, NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("16. DEAD_LETTER without dead_lettered_at rejected by lifecycle CHECK constraint")
    void test16_deadLetterWithoutDeadLetteredAtRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'DEAD_LETTER', 0, NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("17. Valid future LEASED schema state accepted")
    void test17_validFutureLeasedStateAccepted() {
        UUID id = UUID.randomUUID();
        int rows = jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, locked_at, locked_by, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'LEASED', 1, NOW(), NOW(), 'worker-node-1', NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("18. Valid future RETRY schema state accepted")
    void test18_validFutureRetryStateAccepted() {
        UUID id = UUID.randomUUID();
        int rows = jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, last_error_code, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'RETRY', 2, NOW(), 'NETWORK_TIMEOUT', NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("19. Valid future PROCESSED schema state accepted")
    void test19_validFutureProcessedStateAccepted() {
        UUID id = UUID.randomUUID();
        int rows = jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, processed_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'PROCESSED', 1, NOW(), NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("20. Valid future DEAD_LETTER schema state accepted")
    void test20_validFutureDeadLetterStateAccepted() {
        UUID id = UUID.randomUUID();
        int rows = jdbc.update(
                "INSERT INTO financial_outbox (id, release_instruction_id, event_type, operation_key, payload_version, status, attempt_count, available_at, last_error_code, dead_lettered_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'RELEASE_DISPATCH_REQUESTED', ?, 1, 'DEAD_LETTER', 5, NOW(), 'PERMANENT_REJECTION', NOW(), NOW(), NOW())",
                id, testInstruction.getId(), testInstruction.getId().toString()
        );
        assertThat(rows).isEqualTo(1);
    }
}
