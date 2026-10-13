package com.finx.release.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.banking.provider.mock.MockBankingProvider;
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
import com.finx.escrow.entity.EscrowAccountStatus;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.ledger.repository.JournalEntryRepository;
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
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.verification.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "finx.banking.mock.auto-settle=false",
        "razorpay.sandbox-mode=true"
})
@AutoConfigureMockMvc
@EnabledIf("isPostgresAvailable")
public class PostgresSettlementWebhookObservationTest {

    private static final String PROVIDER = "mock";
    private static final String VALID_SIGNATURE = "mock-valid-signature";

    static {
        ensureDatabaseExists("finx_release_e6_test");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebhookEventRepository webhookEventRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PaymentInstructionRepository paymentInstructionRepo;
    @Autowired private PaymentTransactionRepository paymentTransactionRepo;
    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private PiiEncryptionService piiEncryptionService;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;

    private ReleaseInstruction testInstruction;
    private ReleaseReservation testReservation;
    private SettlementExecutionAttempt testAttempt;
    private EscrowAccount testEscrow;

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
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE " + dbName);
            }
        } catch (SQLException ignored) {
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM settlement_observations");
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");
        jdbc.execute("DELETE FROM engine_decisions");
        jdbc.execute("DELETE FROM engine_policy_versions");
        jdbc.execute("DELETE FROM payment_transactions");
        jdbc.execute("DELETE FROM payment_instructions");
        jdbc.execute("DELETE FROM escrow_accounts");
        jdbc.execute("DELETE FROM milestones");
        jdbc.execute("DELETE FROM deals");
        jdbc.execute("DELETE FROM organization_bank_accounts");
        jdbc.execute("DELETE FROM organizations");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User adminUser = userRepo.save(new User("Admin " + suffix, "admin" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));
        User buyerUser = userRepo.save(new User("Buyer " + suffix, "buyer" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));

        Organization buyerOrg = orgRepo.save(new Organization("Buyer Org " + suffix, "Buyer Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-B-" + suffix, "PANB" + suffix.toUpperCase(), "29AAAAA0000A1Z5", "Bangalore", buyerUser.getId()));

        Organization sellerOrg = orgRepo.save(new Organization("Seller Org " + suffix, "Seller Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-S-" + suffix, "PANS" + suffix.toUpperCase(), "29AAAAA0000A1Z6", "Mumbai", adminUser.getId()));

        String encAcc = piiEncryptionService.encrypt("987654321098");
        OrganizationBankAccount sellerBankAcc = new OrganizationBankAccount(sellerOrg.getId(), "Beneficiary Corp", "****1098", "hash-" + suffix, "MOCK0001", "Branch", "CURRENT", true);
        sellerBankAcc.setEncryptedAccountNumber(encAcc);
        sellerBankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        sellerBankAcc.setProvider("MOCK");
        sellerBankAcc = bankAccRepo.save(sellerBankAcc);

        Deal testDeal = new Deal();
        testDeal.setTitle("Deal " + suffix);
        testDeal.setDescription("E6 Deal");
        testDeal.setBuyerId(buyerUser.getId());
        testDeal.setSellerId(adminUser.getId());
        testDeal.setBuyerOrganizationId(buyerOrg.getId());
        testDeal.setSellerOrganizationId(sellerOrg.getId());
        testDeal.setTotalAmount(new BigDecimal("50000.0000"));
        testDeal.setCurrency("INR");
        testDeal.setStatus(DealStatus.FUNDED);
        testDeal = dealRepo.save(testDeal);

        Instant now = Instant.now();
        Milestone testMilestone = new Milestone(testDeal.getId(), "M1", "M1 desc", 1, new BigDecimal("50000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400));
        testMilestone.setStatus(MilestoneStatus.APPROVED);
        testMilestone = milestoneRepo.save(testMilestone);

        testEscrow = new EscrowAccount(testDeal.getId(), new BigDecimal("50000.0000"), "INR");
        testEscrow.setStatus(EscrowAccountStatus.ACTIVE);
        testEscrow = escrowRepo.save(testEscrow);

        EnginePolicyVersion policy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", adminUser.getId(), now
        ));

        EngineDecision decision = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(decision, buyerOrg.getId());
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(decision, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(decision, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(decision, testMilestone.getId());
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

        UUID instructionId = UUID.randomUUID();
        testInstruction = new ReleaseInstruction(
                instructionId, buyerOrg.getId(), testDeal.getId(), testMilestone.getId(), decision.getId(),
                "1".repeat(64), policy.getId(), policy.getContentHash(), sellerBankAcc.getId(),
                "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT, new BigDecimal("50000.0000"), "INR",
                "idem-" + suffix, "3".repeat(64), adminUser.getId()
        );
        testInstruction.markAuthorized(adminUser.getId(), now);
        testInstruction.markReserved();
        testInstruction.markQueued();
        testInstruction.markDispatching();
        testInstruction.markPendingSettlement();
        testInstruction = instructionRepo.save(testInstruction);

        testReservation = new ReleaseReservation(
                UUID.randomUUID(), testInstruction.getId(), testEscrow.getId(), testMilestone.getId(),
                new BigDecimal("50000.0000"), "INR"
        );
        testReservation = reservationRepo.save(testReservation);

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(), testInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                testInstruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now, now, now
        );
        outbox = outboxRepo.save(outbox);

        testAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), testInstruction.getId(), outbox.getId(), 1,
                "OP-" + suffix, "FINX-REL-" + testInstruction.getId(), "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT,
                sellerBankAcc.getId(), new BigDecimal("50000.0000"), "INR", "4".repeat(64), "worker-1", now
        );
        testAttempt.markSending(now);
        testAttempt.markAcknowledged("MOCK-SETTLE-POSTGRES-001", "ACKNOWLEDGED", now);
        testAttempt = attemptRepo.save(testAttempt);
    }

    private String validTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    @Test
    @DisplayName("Valid signed webhook twice against real Postgres: idempotent, 1 observation, 0 finalization")
    void validSignedWebhookTwice_idempotentNoFinalization() throws Exception {
        String eventId = "evt-pg-settle-001";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-POSTGRES-001",
                "status", "SETTLED",
                "utrNumber", "UTR-PG-123456",
                "amount", "50000.0000",
                "currency", "INR",
                "direction", "DEBIT",
                "providerAccountId", MockBankingProvider.MOCK_ESCROW_ACCOUNT
        ));
        String ts = validTimestamp();

        // Delivery 1: Processed
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        // Delivery 2: Already processed
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_processed"));

        // Exactly 1 WebhookEvent in PostgreSQL
        List<WebhookEvent> webhookEvents = webhookEventRepo.findAll();
        assertThat(webhookEvents).hasSize(1);
        assertThat(webhookEvents.get(0).getProcessingStatus()).isEqualTo("PROCESSED");

        // Exactly 1 SettlementObservation in PostgreSQL
        List<SettlementObservation> observations = observationRepo.findByExecutionAttemptIdOrderByObservedAtDesc(testAttempt.getId());
        assertThat(observations).hasSize(1);
        SettlementObservation obs = observations.get(0);
        assertThat(obs.getSource()).isEqualTo(SettlementObservationSource.WEBHOOK);
        assertThat(obs.getFinality()).isEqualTo(SettlementObservationFinality.FINAL_SUCCESS);
        assertThat(obs.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED);
        assertThat(obs.getUtrNumber()).isEqualTo("UTR-PG-123456");

        // ReleaseInstruction remains PENDING_SETTLEMENT (0 release finalization)
        ReleaseInstruction instruction = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        // ReleaseReservation remains ACTIVE (0 consumption or release)
        ReleaseReservation reservation = reservationRepo.findById(testReservation.getId()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        // Escrow totalReleased unchanged (0 financial release mutation)
        EscrowAccount escrow = escrowRepo.findById(testEscrow.getId()).orElseThrow();
        assertThat(escrow.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(escrow.getBalance()).isEqualByComparingTo(new BigDecimal("50000.0000"));

        // JournalEntry writes == 0
        assertThat(journalRepo.findAll()).isEmpty();
    }
}
