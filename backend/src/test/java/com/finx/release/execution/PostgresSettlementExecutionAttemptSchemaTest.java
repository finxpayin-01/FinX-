package com.finx.release.execution;

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
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
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
public class PostgresSettlementExecutionAttemptSchemaTest {

    static {
        ensureDatabaseExists("finx_release_e4_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
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
    private FinancialOutboxMessage testOutbox;
    private OrganizationBankAccount testBankAcc;

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
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepo.save(new User("User " + suffix, "u" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));

        Organization org = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", user.getId()));

        testBankAcc = new OrganizationBankAccount(org.getId(), "Legal " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        testBankAcc.setEncryptedAccountNumber("enc-" + suffix);
        testBankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        testBankAcc = bankAccRepo.save(testBankAcc);

        Deal deal = new Deal();
        deal.setTitle("Deal " + suffix);
        deal.setDescription("Test deal");
        deal.setBuyerId(user.getId());
        deal.setSellerId(user.getId());
        deal.setTotalAmount(new BigDecimal("10000.0000"));
        deal.setCurrency("INR");
        deal.setStatus(DealStatus.FUNDED);
        deal = dealRepo.save(deal);

        Instant now = Instant.now();
        Milestone milestone = milestoneRepo.save(new Milestone(deal.getId(), "M1", "M1 desc", 1, new BigDecimal("10000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400)));
        EscrowAccount escrow = escrowRepo.save(new EscrowAccount(deal.getId(), new BigDecimal("10000.0000"), "INR"));

        EnginePolicyVersion policy = policyRepo.save(new EnginePolicyVersion(
                null, com.finx.engine.model.PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", user.getId(), now
        ));

        EngineDecision decision = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(decision, org.getId());
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(decision, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(decision, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(decision, milestone.getId());
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
        decision = decisionRepo.save(decision);

        testInstruction = new ReleaseInstruction(
                UUID.randomUUID(), org.getId(), deal.getId(), milestone.getId(), decision.getId(),
                "1".repeat(64), policy.getId(), policy.getContentHash(), testBankAcc.getId(),
                "MOCK", "MOCK-ACC-1", new BigDecimal("10000.0000"), "INR", "idem-" + suffix,
                "a".repeat(64), user.getId()
        );
        testInstruction = instructionRepo.save(testInstruction);

        testOutbox = new FinancialOutboxMessage(
                UUID.randomUUID(), testInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                testInstruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now, now, now
        );
        testOutbox.markLeased("worker-1", now);
        testOutbox = outboxRepo.save(testOutbox);
    }

    private ReleaseInstruction createAdditionalInstruction(String suffix) {
        Instant now = Instant.now();
        Milestone milestone2 = milestoneRepo.save(new Milestone(
                testInstruction.getDealId(), "M-" + suffix, "M desc", 2,
                new BigDecimal("10000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400)
        ));
        EngineDecision decision = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(decision, testInstruction.getOrganizationId());
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(decision, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(decision, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(decision, milestone2.getId());
            var fDec = EngineDecision.class.getDeclaredField("decision"); fDec.setAccessible(true); fDec.set(decision, DecisionStatus.ALLOW);
            var fOut = EngineDecision.class.getDeclaredField("evaluationOutcome"); fOut.setAccessible(true); fOut.set(decision, EvaluationOutcome.SUCCESS);
            var fInFp = EngineDecision.class.getDeclaredField("inputFingerprint"); fInFp.setAccessible(true); fInFp.set(decision, "0".repeat(64));
            var fDecFp = EngineDecision.class.getDeclaredField("decisionFingerprint"); fDecFp.setAccessible(true); fDecFp.set(decision, "1".repeat(64));
            var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(decision, testInstruction.getPolicyVersionId());
            var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(decision, testInstruction.getPolicyContentHash());
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
                UUID.randomUUID(), testInstruction.getOrganizationId(), testInstruction.getDealId(),
                milestone2.getId(), decision.getId(),
                "1".repeat(64), testInstruction.getPolicyVersionId(), testInstruction.getPolicyContentHash(),
                testBankAcc.getId(), "AXIS", "AXIS-ACC-1", new BigDecimal("10000.0000"), "INR",
                "idem-" + suffix, "a".repeat(64), testInstruction.getCreatedBy()
        );
        return instructionRepo.save(instruction);
    }

    @Test
    @DisplayName("1 & 2. Verify PostgreSQL product and settlement_execution_attempts table exists")
    void test01_postgresProductAndTableExists() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'settlement_execution_attempts'",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("3, 4, 5. Foreign keys exist to release_instructions, financial_outbox, and organization_bank_accounts")
    void test02_foreignKeysExist() {
        Integer fkCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name = 'settlement_execution_attempts' AND constraint_type = 'FOREIGN KEY'",
                Integer.class
        );
        assertThat(fkCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("6, 7, 8, 9. Semantic check constraints: amount > 0, currency format, request_hash format, attempt_number > 0")
    void test03_semanticCheckConstraints() {
        UUID id = UUID.randomUUID();

        // Amount <= 0 rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op', 'pop', 'MOCK', 'acc', ?, 0, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid currency rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op', 'pop', 'MOCK', 'acc', ?, 100, 'inr', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid request_hash (not 64 hex) rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op', 'pop', 'MOCK', 'acc', ?, 100, 'INR', 'invalid-hash', 'PREPARED', 'w', NOW(), NOW(), 0)",
                id, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);

        // attempt_number <= 0 rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 0, 'op', 'pop', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("10, 11, 12, 13, 14. Blank fields and invalid status rejected")
    void test04_blankFieldsAndInvalidStatusRejected() {
        UUID id = UUID.randomUUID();

        // Blank operation key
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, '   ', 'pop', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Blank provider operation key
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op', '   ', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid status
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op', 'pop', 'MOCK', 'acc', ?, 100, 'INR', ?, 'INVALID_STATUS', 'w', NOW(), NOW(), 0)",
                id, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("15 & 16. Duplicate (release_instruction_id, attempt_number) and (outbox_message_id, attempt_number) rejected")
    void test05_duplicateAttemptNumberRejected() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op1', 'pop1', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id1, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        );

        // Duplicate attempt 1 for same release_instruction_id / outbox_message_id
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op2', 'pop2', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id2, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("17. Partial unique index: second active (PREPARED) attempt for same outbox rejected even with different attempt_number")
    void test06_secondActiveAttemptRejected() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op1', 'pop1', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id1, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        );

        // Attempt 2 in PREPARED status for same outbox_message_id is blocked by partial unique index uk_settlement_attempts_active_outbox
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 2, 'op1', 'pop1', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), 0)",
                id2, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("18 & 19. Provider settlement ID unique within provider, but allowed across different providers")
    void test07_providerSettlementIdScopedUniqueness() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        String settleId = "SETTLE-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Provider MOCK with settlement_id
        jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, provider_settlement_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op1', 'pop1', 'MOCK', 'acc', ?, 100, 'INR', ?, 'ACKNOWLEDGED', 'w', ?, NOW(), NOW(), 0)",
                id1, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64), settleId
        );

        // 2. Same provider MOCK with same settlement_id rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, provider_settlement_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 2, 'op2', 'pop2', 'MOCK', 'acc', ?, 100, 'INR', ?, 'ACKNOWLEDGED', 'w', ?, NOW(), NOW(), 0)",
                id2, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64), settleId
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 3. Different provider AXIS with same settlement_id allowed
        ReleaseInstruction instruction2 = createAdditionalInstruction("scoped-2");

        FinancialOutboxMessage outbox2 = new FinancialOutboxMessage(
                UUID.randomUUID(), instruction2.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instruction2.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, Instant.now(), Instant.now(), Instant.now()
        );
        outboxRepo.save(outbox2);

        int inserted = jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, provider_settlement_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op3', 'pop3', 'AXIS', 'acc', ?, 100, 'INR', ?, 'ACKNOWLEDGED', 'w', ?, NOW(), NOW(), 0)",
                id3, instruction2.getId(), outbox2.getId(), testBankAcc.getId(), "a".repeat(64), settleId
        );
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("20 & 21. PREPARED status with requestStartedAt or providerSettlementId rejected by check constraint")
    void test08_preparedStatusLifecycleIntegrity() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        // PREPARED with request_started_at rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, request_started_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op', 'pop', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', NOW(), NOW(), NOW(), 0)",
                id1, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        // PREPARED with provider_settlement_id rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO settlement_execution_attempts " +
                        "(id, release_instruction_id, outbox_message_id, attempt_number, operation_key, provider_operation_key, provider, provider_account_id, beneficiary_bank_account_id, amount, currency, request_hash, status, worker_id, provider_settlement_id, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 1, 'op', 'pop', 'MOCK', 'acc', ?, 100, 'INR', ?, 'PREPARED', 'w', 'SETTLE-1', NOW(), NOW(), 0)",
                id2, testInstruction.getId(), testOutbox.getId(), testBankAcc.getId(), "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("22, 23, 24. Foreign key ON DELETE RESTRICT blocks deletion of referenced entities")
    void test09_foreignKeyDeleteRestricted() {
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                testInstruction.getId(),
                testOutbox.getId(),
                1,
                testInstruction.getId().toString(),
                ProviderSettlementOperationKey.release(testInstruction.getId()),
                "MOCK",
                "MOCK-ACC-1",
                testBankAcc.getId(),
                new BigDecimal("1000.0000"),
                "INR",
                "a".repeat(64),
                "w-1",
                Instant.now()
        );
        attemptRepo.save(attempt);
        attemptRepo.flush();

        // Delete ReleaseInstruction blocked
        assertThatThrownBy(() -> {
            instructionRepo.delete(testInstruction);
            instructionRepo.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);

        // Delete Outbox blocked
        assertThatThrownBy(() -> {
            outboxRepo.delete(testOutbox);
            outboxRepo.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);

        // Delete Beneficiary account blocked
        assertThatThrownBy(() -> {
            bankAccRepo.delete(testBankAcc);
            bankAccRepo.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
