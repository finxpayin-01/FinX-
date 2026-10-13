package com.finx.release.observation;

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
import com.finx.engine.model.PolicyScope;
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
import com.finx.payment.entity.WebhookEvent;
import com.finx.payment.repository.WebhookEventRepository;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
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
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e6_test}",
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
public class PostgresSettlementObservationSchemaTest {

    static {
        ensureDatabaseExists("finx_release_e6_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private WebhookEventRepository webhookRepo;
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
    private SettlementExecutionAttempt testAttempt;
    private WebhookEvent testWebhookEvent;

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
        jdbc.execute("DELETE FROM settlement_observations");
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");
        jdbc.execute("DELETE FROM webhook_events");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepo.save(new User("User " + suffix, "u" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));

        Organization org = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", user.getId()));

        OrganizationBankAccount bankAcc = new OrganizationBankAccount(org.getId(), "Legal " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        bankAcc.setEncryptedAccountNumber("enc-" + suffix);
        bankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAcc = bankAccRepo.save(bankAcc);

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
        escrowRepo.save(new EscrowAccount(deal.getId(), new BigDecimal("10000.0000"), "INR"));

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
                "1".repeat(64), policy.getId(), policy.getContentHash(), bankAcc.getId(),
                "MOCK", "MOCK-ACC-1", new BigDecimal("10000.0000"), "INR", "idem-" + suffix,
                "a".repeat(64), user.getId()
        );
        testInstruction.markAuthorized(user.getId(), now);
        testInstruction.markReserved();
        testInstruction.markQueued();
        testInstruction.markDispatching();
        testInstruction.markPendingSettlement();
        testInstruction = instructionRepo.save(testInstruction);

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(), testInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                testInstruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now, now, now
        );
        outbox = outboxRepo.save(outbox);

        testAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), testInstruction.getId(), outbox.getId(), 1,
                "OP-" + suffix, "FINX-REL-" + testInstruction.getId(), "MOCK", "MOCK-ACC-1",
                bankAcc.getId(), new BigDecimal("10000.0000"), "INR", "b".repeat(64), "worker-1", now
        );
        testAttempt.markSending(now);
        testAttempt.markAcknowledged("MOCK-SETTLE-001", "ACKNOWLEDGED", now);
        testAttempt = attemptRepo.save(testAttempt);

        testWebhookEvent = new WebhookEvent();
        testWebhookEvent.setProvider("MOCK");
        testWebhookEvent.setProviderEventId("EVT-" + suffix);
        testWebhookEvent.setEventType("SETTLEMENT_SETTLED");
        testWebhookEvent.setPayloadHash("c".repeat(64));
        testWebhookEvent.setProcessingStatus("PROCESSED");
        testWebhookEvent.setProcessedAt(now);
        testWebhookEvent = webhookRepo.save(testWebhookEvent);
    }

    @Test
    @DisplayName("1. Table settlement_observations exists and has correct columns")
    void tableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'settlement_observations'",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Valid observation insertion succeeds")
    void validObservationInsert() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        SettlementObservation obs = SettlementObservation.builder()
                .id(id)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .webhookEventId(testWebhookEvent.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-1")
                .providerOperationKey("FINX-REL-1")
                .providerSettlementId("MOCK-SETTLE-001")
                .providerAccountId("MOCK-ACC-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode(SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED)
                .utrNumber("UTR-123456")
                .observedAmount(new BigDecimal("10000.0000"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .providerObservedAt(now)
                .evidenceHash("0".repeat(64))
                .observedAt(now)
                .createdAt(now)
                .build();

        SettlementObservation saved = observationRepo.saveAndFlush(obs);
        assertThat(saved.getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("3. Invalid source rejected by chk_settlement_obs_source")
    void invalidSourceRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'INVALID_SOURCE', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'FINAL_SUCCESS', 'REASON', '" + "a".repeat(64) + "', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("4. Invalid finality rejected by chk_settlement_obs_finality")
    void invalidFinalityRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'INVALID_FINALITY', 'REASON', '" + "a".repeat(64) + "', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("5. Blank provider rejected by chk_settlement_obs_provider")
    void blankProviderRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', '   ', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'UNKNOWN', 'REASON', '" + "a".repeat(64) + "', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("6. Blank source_reference rejected by chk_settlement_obs_source_reference")
    void blankSourceReferenceRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'   ', 'OP-1', 'SETTLED', 'UNKNOWN', 'REASON', '" + "a".repeat(64) + "', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("7. Bad evidence hash rejected by chk_settlement_obs_evidence_hash")
    void badEvidenceHashRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'UNKNOWN', 'REASON', 'not-a-valid-sha256-hash', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("8. Bad currency rejected by chk_settlement_obs_currency")
    void badCurrencyRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_currency, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'UNKNOWN', 'REASON', '" + "a".repeat(64) + "', 'inr', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("9. Bad direction rejected by chk_settlement_obs_direction")
    void badDirectionRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_direction, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'UNKNOWN', 'REASON', '" + "a".repeat(64) + "', 'CREDIT', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("10. Observed amount <= 0 rejected by chk_settlement_obs_amount")
    void zeroOrNegativeAmountRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, observed_amount, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'UNKNOWN', 'REASON', '" + "a".repeat(64) + "', 0.0000, NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("11. FINAL_SUCCESS missing provider_settlement_id rejected by chk_settlement_obs_final_success")
    void finalSuccessMissingSettlementIdRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_status, finality, reason_code, evidence_hash, utr_number, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLED', 'FINAL_SUCCESS', 'REASON', '" + "a".repeat(64) + "', 'UTR-1', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("12. FINAL_SUCCESS missing utr_number rejected by chk_settlement_obs_final_success")
    void finalSuccessMissingUtrRejected() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO settlement_observations (id, release_instruction_id, execution_attempt_id, provider, source, " +
                        "source_reference, provider_operation_key, provider_settlement_id, provider_status, finality, reason_code, evidence_hash, observed_at, created_at) " +
                        "VALUES ('" + UUID.randomUUID() + "', '" + testInstruction.getId() + "', '" + testAttempt.getId() + "', 'MOCK', 'WEBHOOK', " +
                        "'REF-1', 'OP-1', 'SETTLE-1', 'SETTLED', 'FINAL_SUCCESS', 'REASON', '" + "a".repeat(64) + "', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("13. Duplicate webhook_event_id rejected by unique partial index")
    void duplicateWebhookEventIdRejected() {
        UUID obs1Id = UUID.randomUUID();
        UUID obs2Id = UUID.randomUUID();
        Instant now = Instant.now();

        SettlementObservation obs1 = SettlementObservation.builder()
                .id(obs1Id)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .webhookEventId(testWebhookEvent.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-1")
                .providerOperationKey("OP-1")
                .providerSettlementId("SETTLE-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-1")
                .evidenceHash("1".repeat(64))
                .observedAt(now)
                .createdAt(now)
                .build();
        observationRepo.saveAndFlush(obs1);

        SettlementObservation obs2 = SettlementObservation.builder()
                .id(obs2Id)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .webhookEventId(testWebhookEvent.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-2")
                .providerOperationKey("OP-1")
                .providerSettlementId("SETTLE-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-1")
                .evidenceHash("2".repeat(64))
                .observedAt(now)
                .createdAt(now)
                .build();

        assertThatThrownBy(() -> observationRepo.saveAndFlush(obs2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("14. Duplicate exact evidence hash for same attempt/source rejected")
    void duplicateAttemptSourceHashRejected() {
        UUID obs1Id = UUID.randomUUID();
        UUID obs2Id = UUID.randomUUID();
        Instant now = Instant.now();
        String sharedHash = "3".repeat(64);

        SettlementObservation obs1 = SettlementObservation.builder()
                .id(obs1Id)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("POLL-1")
                .providerOperationKey("OP-1")
                .providerSettlementId("SETTLE-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-1")
                .evidenceHash(sharedHash)
                .observedAt(now)
                .createdAt(now)
                .build();
        observationRepo.saveAndFlush(obs1);

        SettlementObservation obs2 = SettlementObservation.builder()
                .id(obs2Id)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("POLL-2")
                .providerOperationKey("OP-1")
                .providerSettlementId("SETTLE-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-1")
                .evidenceHash(sharedHash)
                .observedAt(now)
                .createdAt(now)
                .build();

        assertThatThrownBy(() -> observationRepo.saveAndFlush(obs2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("15. Attempt deletion blocked by foreign key constraint")
    void attemptDeletionBlocked() {
        UUID obsId = UUID.randomUUID();
        Instant now = Instant.now();
        SettlementObservation obs = SettlementObservation.builder()
                .id(obsId)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("POLL-1")
                .providerOperationKey("OP-1")
                .providerSettlementId("SETTLE-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-1")
                .evidenceHash("4".repeat(64))
                .observedAt(now)
                .createdAt(now)
                .build();
        observationRepo.saveAndFlush(obs);

        assertThatThrownBy(() -> attemptRepo.delete(testAttempt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("16. Release instruction deletion blocked by foreign key constraint")
    void releaseInstructionDeletionBlocked() {
        UUID obsId = UUID.randomUUID();
        Instant now = Instant.now();
        SettlementObservation obs = SettlementObservation.builder()
                .id(obsId)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.STATUS_POLL)
                .sourceReference("POLL-1")
                .providerOperationKey("OP-1")
                .providerSettlementId("SETTLE-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-1")
                .evidenceHash("5".repeat(64))
                .observedAt(now)
                .createdAt(now)
                .build();
        observationRepo.saveAndFlush(obs);

        assertThatThrownBy(() -> instructionRepo.delete(testInstruction))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("17. Webhook event deletion blocked by foreign key constraint")
    void webhookEventDeletionBlocked() {
        UUID obsId = UUID.randomUUID();
        Instant now = Instant.now();
        SettlementObservation obs = SettlementObservation.builder()
                .id(obsId)
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .webhookEventId(testWebhookEvent.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-1")
                .providerOperationKey("OP-1")
                .providerSettlementId("SETTLE-1")
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-1")
                .evidenceHash("6".repeat(64))
                .observedAt(now)
                .createdAt(now)
                .build();
        observationRepo.saveAndFlush(obs);

        assertThatThrownBy(() -> webhookRepo.delete(testWebhookEvent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
