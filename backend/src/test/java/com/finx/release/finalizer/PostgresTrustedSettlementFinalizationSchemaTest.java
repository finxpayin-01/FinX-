package com.finx.release.finalizer;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
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
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.observation.repository.SettlementObservationRepository;
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
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
public class PostgresTrustedSettlementFinalizationSchemaTest {

    static {
        ensureDatabaseExists("finx_release_e7_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;

    @MockBean private BankingProvider bankingProvider;

    private User testUser;
    private Organization testOrg;
    private EnginePolicyVersion defaultPolicy;
    private ReleaseInstruction testInstruction;
    private SettlementExecutionAttempt testAttempt;
    private SettlementObservation testObservation;

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
        jdbc.execute("DELETE FROM settlement_observations");
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");
        jdbc.execute("DELETE FROM engine_decisions");
        jdbc.execute("DELETE FROM engine_policy_versions");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        testUser = userRepo.save(new User("User " + suffix, "u" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));

        testOrg = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", testUser.getId()));

        defaultPolicy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", testUser.getId(), Instant.now()
        ));

        OrganizationBankAccount bankAcc = new OrganizationBankAccount(testOrg.getId(), "Legal " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        bankAcc.setEncryptedAccountNumber("enc-" + suffix);
        bankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAcc = bankAccRepo.save(bankAcc);

        Deal deal = new Deal();
        deal.setTitle("Deal " + suffix);
        deal.setDescription("Test deal");
        deal.setBuyerId(testUser.getId());
        deal.setSellerId(testUser.getId());
        deal.setTotalAmount(new BigDecimal("10000.0000"));
        deal.setCurrency("INR");
        deal.setStatus(DealStatus.FUNDED);
        deal = dealRepo.save(deal);

        Milestone milestone = new Milestone();
        milestone.setDealId(deal.getId());
        milestone.setTitle("M1");
        milestone.setDescription("Desc");
        milestone.setAmount(new BigDecimal("5000.0000"));
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone = milestoneRepo.save(milestone);

        EngineDecision decision = createDecision(testOrg.getId(), milestone.getId(), defaultPolicy, testUser.getId());

        testInstruction = new ReleaseInstruction(
                UUID.randomUUID(),
                testOrg.getId(),
                deal.getId(),
                milestone.getId(),
                decision.getId(),
                "a".repeat(64),
                defaultPolicy.getId(),
                defaultPolicy.getContentHash(),
                bankAcc.getId(),
                "MOCK",
                "ESCROW-POOL-1",
                new BigDecimal("5000.00"),
                "INR",
                "idem-" + suffix,
                "c".repeat(64),
                testUser.getId()
        );
        testInstruction.markAuthorized(testUser.getId(), Instant.now());
        testInstruction.markReserved();
        testInstruction.markQueued();
        testInstruction.markDispatching();
        testInstruction.markPendingSettlement();
        testInstruction = instructionRepo.save(testInstruction);

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(),
                testInstruction.getId(),
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

        testAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                testInstruction.getId(),
                outbox.getId(),
                1,
                "OP-" + suffix,
                "OP-" + suffix,
                "MOCK",
                "ESCROW-POOL-1",
                bankAcc.getId(),
                new BigDecimal("5000.00"),
                "INR",
                "d".repeat(64),
                "worker-1",
                Instant.now()
        );
        testAttempt.markSending(Instant.now());
        testAttempt.markAcknowledged("SETTLE-" + suffix, "Dispatch OK", Instant.now());
        testAttempt = attemptRepo.save(testAttempt);

        testObservation = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
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
                .observedAmount(new BigDecimal("5000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("e".repeat(64))
                .observedAt(Instant.now())
                .build();
        testObservation = observationRepo.save(testObservation);
    }

    @Test
    @DisplayName("Verify V34 migration columns and unique constraint exist on release_instructions")
    void testV34ColumnsExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(null, null, "release_instructions", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(columns).contains("final_settlement_observation_id", "finalized_by");
        }
    }

    @Test
    @DisplayName("SETTLED status requires final_settlement_observation_id, finalized_by, and settled_at")
    void testSettledStatusRequiresObservationBinding() {
        UUID badId = UUID.randomUUID();
        EngineDecision decision = createDecision(testOrg.getId(), testInstruction.getMilestoneId(), defaultPolicy, testUser.getId());
        assertThatThrownBy(() -> jdbc.execute(String.format(
                "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, " +
                        "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, " +
                        "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, " +
                        "created_by, settled_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 5000.00, 'INR', 'SETTLED', '%s', '%s', '%s', NOW())",
                badId, testOrg.getId(), testInstruction.getDealId(), testInstruction.getMilestoneId(), decision.getId(),
                decision.getDecisionFingerprint(), defaultPolicy.getId(), defaultPolicy.getContentHash(), testInstruction.getBeneficiaryBankAccountId(),
                "MOCK", "ESCROW-POOL-1", UUID.randomUUID(), "c".repeat(64), testUser.getId()
        ))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_release_instructions_settled_observation");
    }

    @Test
    @DisplayName("Non-SETTLED status forbids final_settlement_observation_id")
    void testNonSettledForbidsObservationBinding() {
        UUID badId = UUID.randomUUID();
        EngineDecision decision = createDecision(testOrg.getId(), testInstruction.getMilestoneId(), defaultPolicy, testUser.getId());
        assertThatThrownBy(() -> jdbc.execute(String.format(
                "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, " +
                        "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, " +
                        "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, " +
                        "created_by, final_settlement_observation_id) " +
                        "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 5000.00, 'INR', 'CREATED', '%s', '%s', '%s', '%s')",
                badId, testOrg.getId(), testInstruction.getDealId(), testInstruction.getMilestoneId(), decision.getId(),
                decision.getDecisionFingerprint(), defaultPolicy.getId(), defaultPolicy.getContentHash(), testInstruction.getBeneficiaryBankAccountId(),
                "MOCK", "ESCROW-POOL-1", UUID.randomUUID(), "c".repeat(64), testUser.getId(), testObservation.getId()
        ))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_release_instructions_settled_observation");
    }

    @Test
    @DisplayName("Unique constraint on final_settlement_observation_id prevents two releases binding to same observation")
    void testUniqueFinalObservationBinding() {
        testInstruction.markSettledFromObservation(testObservation.getId(), testUser.getId(), Instant.now());
        instructionRepo.saveAndFlush(testInstruction);

        EngineDecision secondDecision = createDecision(testOrg.getId(), testInstruction.getMilestoneId(), defaultPolicy, testUser.getId());
        ReleaseInstruction secondInstruction = new ReleaseInstruction(
                UUID.randomUUID(),
                testOrg.getId(),
                testInstruction.getDealId(),
                testInstruction.getMilestoneId(),
                secondDecision.getId(),
                "a".repeat(64),
                defaultPolicy.getId(),
                defaultPolicy.getContentHash(),
                testInstruction.getBeneficiaryBankAccountId(),
                "MOCK",
                "ESCROW-POOL-1",
                new BigDecimal("5000.00"),
                "INR",
                "idem-second-" + UUID.randomUUID(),
                "c".repeat(64),
                testUser.getId()
        );
        secondInstruction.markAuthorized(testUser.getId(), Instant.now());
        secondInstruction.markReserved();
        secondInstruction.markQueued();
        secondInstruction.markDispatching();
        secondInstruction.markPendingSettlement();
        secondInstruction.markSettledFromObservation(testObservation.getId(), testUser.getId(), Instant.now());

        assertThatThrownBy(() -> instructionRepo.saveAndFlush(secondInstruction))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uix_release_instructions_final_observation");
    }

    @Test
    @DisplayName("Foreign key constraints RESTRICT deletion of observation or user bound to settled release")
    void testForeignKeyRestrictOnDelete() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User finalizerUser = userRepo.save(new User("Finance " + suffix, "fin" + suffix + "@finx.invalid", "pass", Role.FINANCE, UserStatus.ACTIVE));
        testInstruction.markSettledFromObservation(testObservation.getId(), finalizerUser.getId(), Instant.now());
        instructionRepo.saveAndFlush(testInstruction);

        // Attempt to delete observation
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM settlement_observations WHERE id = '" + testObservation.getId() + "'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("release_instructions_final_settlement_observation_id_fkey");

        // Attempt to delete user (finalizerUser is ONLY referenced by release_instructions.finalized_by)
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM users WHERE id = '" + finalizerUser.getId() + "'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("release_instructions_finalized_by_fkey");
    }
}
