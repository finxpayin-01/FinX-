package com.finx.release;

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
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.service.LedgerService;
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
import com.finx.payment.entity.WebhookEvent;
import com.finx.payment.repository.WebhookEventRepository;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeObservationResult;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeStatus;
import com.finx.release.observation.service.SettlementObservationService;
import com.finx.release.observation.service.RecordObservationCommand;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
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
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "finx.banking.mock.auto-settle=false",
        "finx.release.sandbox-dispatch.enabled=true",
        "finx.release.sandbox-finalization.enabled=true",
        "razorpay.sandbox-mode=true"
})
@AutoConfigureMockMvc
@EnabledIf("isPostgresAvailable")
public class DuplicateWebhookAndPollRaceTest {

    private static final String PROVIDER = "mock";
    private static final String VALID_SIGNATURE = "mock-valid-signature";

    static {
        ensureDatabaseExists("finx_release_e9_test");
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
    @Autowired private EscrowLedgerRepository escrowLedgerRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PaymentInstructionRepository paymentInstructionRepo;
    @Autowired private PaymentTransactionRepository paymentTransactionRepo;
    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private LedgerService ledgerService;
    @Autowired private PiiEncryptionService piiEncryptionService;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private SettlementObservationService observationService;
    @Autowired private SettlementObservationEvidenceReader evidenceReader;
    @Autowired private TrustedSettlementFinalizer finalizer;

    private User adminUser;
    private User buyerUser;
    private UserPrincipal adminPrincipal;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private OrganizationBankAccount sellerBankAcc;
    private Deal testDeal;
    private Milestone testMilestone;
    private EscrowAccount testEscrow;
    private EnginePolicyVersion policy;
    private EngineDecision decision;
    private String suffix;

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
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            hikari.setMaximumPoolSize(35);
        }
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
        jdbc.execute("DELETE FROM engine_decisions");
        jdbc.execute("DELETE FROM engine_policy_versions");
        jdbc.execute("DELETE FROM payment_transactions");
        jdbc.execute("DELETE FROM payment_instructions");
        jdbc.execute("DELETE FROM escrow_accounts");
        jdbc.execute("DELETE FROM disputes");
        jdbc.execute("DELETE FROM milestone_approval_bindings");
        jdbc.execute("DELETE FROM deliverables");
        jdbc.execute("DELETE FROM milestones");
        jdbc.execute("DELETE FROM deals");
        try {
            jdbc.execute("UPDATE risk_scores SET organization_id = NULL");
            jdbc.execute("ALTER TABLE risk_assessments DISABLE TRIGGER ALL");
            jdbc.execute("UPDATE risk_assessments SET organization_id = NULL");
            jdbc.execute("ALTER TABLE risk_assessments ENABLE TRIGGER ALL");
        } catch (Exception ignored) {
        }
        jdbc.execute("DELETE FROM organization_bank_accounts");
        jdbc.execute("DELETE FROM organizations");

        suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUser = userRepo.save(new User("Admin " + suffix, "admin" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));
        buyerUser = userRepo.save(new User("Buyer " + suffix, "buyer" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getName(), adminUser.getEmail(), "pass", Role.ADMIN, UserStatus.ACTIVE, List.of());

        buyerOrg = orgRepo.save(new Organization("Buyer Org " + suffix, "Buyer Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-B-" + suffix, "PANB" + suffix.toUpperCase(), "29AAAAA0000A1Z5", "Bangalore", buyerUser.getId()));

        sellerOrg = orgRepo.save(new Organization("Seller Org " + suffix, "Seller Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-S-" + suffix, "PANS" + suffix.toUpperCase(), "29AAAAA0000A1Z6", "Mumbai", adminUser.getId()));

        String encAcc = piiEncryptionService.encrypt("987654321098");
        sellerBankAcc = new OrganizationBankAccount(sellerOrg.getId(), "Beneficiary Corp", "****1098", "hash-" + suffix, "MOCK0001", "Branch", "CURRENT", true);
        sellerBankAcc.setEncryptedAccountNumber(encAcc);
        sellerBankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        sellerBankAcc.setProvider("MOCK");
        sellerBankAcc.setVerifiedAt(Instant.now());
        sellerBankAcc = bankAccRepo.save(sellerBankAcc);

        testDeal = new Deal();
        testDeal.setTitle("Deal " + suffix);
        testDeal.setDescription("E9 Webhook Poll Race Deal");
        testDeal.setBuyerId(buyerUser.getId());
        testDeal.setSellerId(adminUser.getId());
        testDeal.setBuyerOrganizationId(buyerOrg.getId());
        testDeal.setSellerOrganizationId(sellerOrg.getId());
        testDeal.setTotalAmount(new BigDecimal("50000.0000"));
        testDeal.setCurrency("INR");
        testDeal.setStatus(DealStatus.FUNDED);
        testDeal = dealRepo.save(testDeal);

        // Pre-fund ledger accounts: Dr 1010 Escrow Pool, Cr 2010 Escrow Liability
        ledgerService.postJournalEntry(new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                testDeal.getId(),
                testDeal.getId(),
                null,
                "INR",
                "Escrow funding",
                adminUser.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, new BigDecimal("50000.0000"), "Fund escrow pool"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, new BigDecimal("50000.0000"), "Escrow liability")
                )
        ));

        Instant now = Instant.now();
        testMilestone = new Milestone(testDeal.getId(), "M1", "M1 desc", 1, new BigDecimal("50000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400));
        testMilestone.setStatus(MilestoneStatus.APPROVED);
        testMilestone = milestoneRepo.save(testMilestone);

        testEscrow = new EscrowAccount(testDeal.getId(), new BigDecimal("50000.0000"), "INR");
        testEscrow.setStatus(EscrowAccountStatus.ACTIVE);
        testEscrow = escrowRepo.save(testEscrow);

        PaymentInstruction pi = new PaymentInstruction(
                "PI-" + suffix, testDeal.getId(), null, buyerOrg.getId(), sellerOrg.getId(),
                new BigDecimal("50000.0000"), "INR", "MOCK", "idem-pi-" + suffix, buyerUser.getId()
        );
        pi.setProviderAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        paymentInstructionRepo.save(pi);

        PaymentTransaction pt = new PaymentTransaction(
                "TX-" + suffix, pi.getId(), "MOCK", "REF-" + suffix,
                new BigDecimal("50000.0000"), "INR", "SUCCESS", "UTR-" + suffix, "{}"
        );
        paymentTransactionRepo.save(pt);

        policy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", adminUser.getId(), now
        ));
    }

    private EngineDecision createDecision(UUID milestoneId) {
        Instant now = Instant.now();
        EngineDecision d = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(d, buyerOrg.getId());
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(d, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(d, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(d, milestoneId);
            var fDec = EngineDecision.class.getDeclaredField("decision"); fDec.setAccessible(true); fDec.set(d, DecisionStatus.ALLOW);
            var fOut = EngineDecision.class.getDeclaredField("evaluationOutcome"); fOut.setAccessible(true); fOut.set(d, EvaluationOutcome.SUCCESS);
            var fInFp = EngineDecision.class.getDeclaredField("inputFingerprint"); fInFp.setAccessible(true); fInFp.set(d, "0".repeat(64));
            var fDecFp = EngineDecision.class.getDeclaredField("decisionFingerprint"); fDecFp.setAccessible(true); fDecFp.set(d, "1".repeat(64));
            var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(d, policy.getId());
            var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(d, policy.getContentHash());
            var fEvId = EngineDecision.class.getDeclaredField("evaluationId"); fEvId.setAccessible(true); fEvId.set(d, UUID.randomUUID());
            var fCoId = EngineDecision.class.getDeclaredField("correlationId"); fCoId.setAccessible(true); fCoId.set(d, UUID.randomUUID());
            var fRuleV = EngineDecision.class.getDeclaredField("ruleVersion"); fRuleV.setAccessible(true); fRuleV.set(d, "1.0.0");
            var fRea = EngineDecision.class.getDeclaredField("reasonCodesJson"); fRea.setAccessible(true); fRea.set(d, "[]");
            var fEvi = EngineDecision.class.getDeclaredField("evidenceReferencesJson"); fEvi.setAccessible(true); fEvi.set(d, "[]");
            var fEvAt = EngineDecision.class.getDeclaredField("evaluatedAt"); fEvAt.setAccessible(true); fEvAt.set(d, now);
            var fVal = EngineDecision.class.getDeclaredField("validUntil"); fVal.setAccessible(true); fVal.set(d, now.plusSeconds(3600));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return decisionRepo.save(d);
    }

    private ReleaseInstruction createInstruction(UUID id, ReleaseInstructionStatus status) {
        Instant now = Instant.now();
        Milestone m = new Milestone(testDeal.getId(), "M-" + UUID.randomUUID().toString().substring(0, 8), "M desc", 1, new BigDecimal("50000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400));
        m.setStatus(MilestoneStatus.APPROVED);
        m = milestoneRepo.save(m);

        EngineDecision dec = createDecision(m.getId());

        ReleaseInstruction instruction = new ReleaseInstruction(
                id, buyerOrg.getId(), testDeal.getId(), m.getId(), dec.getId(),
                "1".repeat(64), policy.getId(), policy.getContentHash(), sellerBankAcc.getId(),
                "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT, new BigDecimal("50000.0000"), "INR",
                "idem-" + UUID.randomUUID(), "3".repeat(64), adminUser.getId()
        );
        instruction.markAuthorized(adminUser.getId(), now);
        instruction.markReserved();
        instruction.markQueued();
        instruction.markDispatching();
        if (status == ReleaseInstructionStatus.PENDING_SETTLEMENT) {
            instruction.markPendingSettlement();
        } else if (status == ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED) {
            instruction.markUnknownReconciliationRequired();
        }
        return instructionRepo.save(instruction);
    }

    private ReleaseReservation createReservation(ReleaseInstruction instruction) {
        ReleaseReservation res = new ReleaseReservation(
                UUID.randomUUID(), instruction.getId(), testEscrow.getId(), instruction.getMilestoneId(),
                new BigDecimal("50000.0000"), "INR"
        );
        return reservationRepo.save(res);
    }

    private SettlementExecutionAttempt createAttempt(ReleaseInstruction instruction, String providerSettlementId) {
        Instant now = Instant.now();
        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(), instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now, now, now
        );
        outbox.markLeased("worker-1", now);
        outbox = outboxRepo.save(outbox);

        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), instruction.getId(), outbox.getId(), 1,
                instruction.getId().toString(), "FINX-REL-" + instruction.getId(), "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT,
                sellerBankAcc.getId(), new BigDecimal("50000.0000"), "INR", instruction.getRequestHash(), "worker-1", now
        );
        attempt.markSending(now);
        attempt.markAcknowledged(providerSettlementId, "ACKNOWLEDGED", now);
        return attemptRepo.save(attempt);
    }

    private String validTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    @Test
    @DisplayName("PART R: Concurrent webhook + poll + operation lookup + finalizer race -> distinct observations, 1 financial finalization")
    void partR_concurrentWebhookPollAndOperationLookupRace() throws Exception {
        UUID instructionId = UUID.randomUUID();
        ReleaseInstruction instruction = createInstruction(instructionId, ReleaseInstructionStatus.PENDING_SETTLEMENT);
        ReleaseReservation reservation = createReservation(instruction);
        String providerSettlementId = "MOCK-STL-R-" + UUID.randomUUID().toString().substring(0, 8);
        SettlementExecutionAttempt attempt = createAttempt(instruction, providerSettlementId);

        String webhookEventId = "evt-race-r-" + UUID.randomUUID().toString().substring(0, 8);
        String webhookPayload = objectMapper.writeValueAsString(Map.of(
                "eventId", webhookEventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", providerSettlementId,
                "status", "SETTLED",
                "utrNumber", "UTR-RACE-001",
                "amount", "50000.0000",
                "currency", "INR",
                "direction", "DEBIT",
                "providerAccountId", MockBankingProvider.MOCK_ESCROW_ACCOUNT
        ));
        String ts = validTimestamp();

        // Concurrently run:
        // 1. Webhook delivery
        // 2. Status poll observation
        // 3. Operation lookup observation
        // 4. Finalizer 1
        // 5. Finalizer 2
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        // Thread 1: Webhook
        executor.submit(() -> {
            try {
                startLatch.await(5, TimeUnit.SECONDS);
                mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(webhookPayload)
                                .header("X-FinX-Signature", VALID_SIGNATURE)
                                .header("X-FinX-Timestamp", ts))
                        .andExpect(status().isOk());
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Status poll observation
        executor.submit(() -> {
            try {
                startLatch.await(5, TimeUnit.SECONDS);
                RecordObservationCommand cmd = com.finx.release.observation.service.RecordObservationCommand.builder()
                        .releaseInstructionId(instructionId)
                        .executionAttemptId(attempt.getId())
                        .source(SettlementObservationSource.STATUS_POLL)
                        .sourceReference(providerSettlementId)
                        .provider(attempt.getProvider())
                        .providerOperationKey(attempt.getProviderOperationKey())
                        .providerSettlementId(providerSettlementId)
                        .rawProviderStatus("SETTLED")
                        .utrNumber("UTR-RACE-POLL")
                        .observedAt(Instant.now())
                        .build();
                observationService.recordObservation(cmd);
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 3: Operation lookup observation
        executor.submit(() -> {
            try {
                startLatch.await(5, TimeUnit.SECONDS);
                RecordObservationCommand cmd = com.finx.release.observation.service.RecordObservationCommand.builder()
                        .releaseInstructionId(instructionId)
                        .executionAttemptId(attempt.getId())
                        .source(SettlementObservationSource.OPERATION_LOOKUP)
                        .sourceReference(attempt.getProviderOperationKey())
                        .provider(attempt.getProvider())
                        .providerOperationKey(attempt.getProviderOperationKey())
                        .providerSettlementId(providerSettlementId)
                        .rawProviderStatus("SETTLED")
                        .utrNumber("UTR-RACE-LOOKUP")
                        .observedAt(Instant.now())
                        .build();
                observationService.recordObservation(cmd);
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 4 & 5: Finalizer calls (with retry if observation not yet committed)
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    // Bounded retry until at least one observation is available
                    boolean success = false;
                    for (int r = 0; r < 20; r++) {
                        try {
                            finalizer.finalizeRelease(instructionId, adminPrincipal);
                            success = true;
                            break;
                        } catch (Exception ex) {
                            Thread.sleep(50);
                        }
                    }
                    if (!success) {
                        finalizer.finalizeRelease(instructionId, adminPrincipal);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        // Observations exist across distinct sources
        List<SettlementObservation> observations = observationRepo.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations.size()).isGreaterThanOrEqualTo(2);

        // Crucial invariant: Exactly 1 observation is bound to ReleaseInstruction
        ReleaseInstruction finalInstruction = instructionRepo.findById(instructionId).orElseThrow();
        assertThat(finalInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        assertThat(finalInstruction.getFinalSettlementObservationId()).isNotNull();

        // Exactly 1 reservation consumed
        ReleaseReservation finalReservation = reservationRepo.findById(reservation.getId()).orElseThrow();
        assertThat(finalReservation.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);

        // Exactly 1 MILESTONE_SETTLEMENT journal entry
        List<JournalEntry> settlementJournals = journalRepo.findAll().stream()
                .filter(j -> j.getTransactionType() == LedgerTransactionType.MILESTONE_SETTLEMENT)
                .toList();
        assertThat(settlementJournals).hasSize(1);
        assertThat(settlementJournals.get(0).getReferenceId()).isEqualTo(instructionId);

        // Exactly 1 EscrowLedger RELEASE entry
        List<EscrowLedger> escrowReleases = escrowLedgerRepo.findAll().stream()
                .filter(el -> el.getTransactionType() == TransactionType.RELEASE)
                .toList();
        assertThat(escrowReleases).hasSize(1);
        assertThat(escrowReleases.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("50000.0000"));

        // Escrow balance exact deduction
        EscrowAccount finalEscrow = escrowRepo.findById(testEscrow.getId()).orElseThrow();
        assertThat(finalEscrow.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finalEscrow.getTotalReleased()).isEqualByComparingTo(new BigDecimal("50000.0000"));

        // Milestone marked COMPLETED
        Milestone finalMilestone = milestoneRepo.findById(instruction.getMilestoneId()).orElseThrow();
        assertThat(finalMilestone.getStatus()).isEqualTo(MilestoneStatus.COMPLETED);
    }

    @Test
    @DisplayName("PART S: Same webhook sent 50x concurrently -> exactly 1 WebhookEvent, 1 observation, 0 duplicate financial effects")
    void partS_sameWebhook50XConcurrentDeliveries() throws Exception {
        UUID instructionId = UUID.randomUUID();
        ReleaseInstruction instruction = createInstruction(instructionId, ReleaseInstructionStatus.PENDING_SETTLEMENT);
        ReleaseReservation reservation = createReservation(instruction);
        String providerSettlementId = "MOCK-STL-S-" + UUID.randomUUID().toString().substring(0, 8);
        SettlementExecutionAttempt attempt = createAttempt(instruction, providerSettlementId);

        String eventId = "evt-dup-50x-" + UUID.randomUUID().toString().substring(0, 8);
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", providerSettlementId,
                "status", "SETTLED",
                "utrNumber", "UTR-50X-12345",
                "amount", "50000.0000",
                "currency", "INR",
                "direction", "DEBIT",
                "providerAccountId", MockBankingProvider.MOCK_ESCROW_ACCOUNT
        ));
        String ts = validTimestamp();

        int callCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(callCount);

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < callCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload)
                                    .header("X-FinX-Signature", VALID_SIGNATURE)
                                    .header("X-FinX-Timestamp", ts))
                            .andExpect(status().isOk());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        // Exactly 1 WebhookEvent in database
        List<WebhookEvent> webhookEvents = webhookEventRepo.findAll().stream()
                .filter(we -> eventId.equals(we.getProviderEventId()))
                .toList();
        assertThat(webhookEvents).hasSize(1);
        assertThat(webhookEvents.get(0).getProcessingStatus()).isEqualTo("PROCESSED");

        // Exactly 1 SettlementObservation recorded for this webhook event
        List<SettlementObservation> observations = observationRepo.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getSource()).isEqualTo(SettlementObservationSource.WEBHOOK);
        assertThat(observations.get(0).getFinality()).isEqualTo(SettlementObservationFinality.FINAL_SUCCESS);

        // Webhook delivery alone does NOT finalize release: release remains PENDING_SETTLEMENT, reservation ACTIVE
        ReleaseInstruction refreshedInst = instructionRepo.findById(instructionId).orElseThrow();
        assertThat(refreshedInst.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        ReleaseReservation refreshedRes = reservationRepo.findById(reservation.getId()).orElseThrow();
        assertThat(refreshedRes.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        // Zero milestone settlement journals from webhook ingestion
        assertThat(journalRepo.findAll().stream().noneMatch(j -> j.getTransactionType() == LedgerTransactionType.MILESTONE_SETTLEMENT)).isTrue();
    }

    @Test
    @DisplayName("PART T: Webhook event-ID collision with conflicting payload -> reject conflict, existing event unchanged")
    void partT_webhookEventIdCollisionWithConflictingPayload() throws Exception {
        UUID instructionId = UUID.randomUUID();
        ReleaseInstruction instruction = createInstruction(instructionId, ReleaseInstructionStatus.PENDING_SETTLEMENT);
        createReservation(instruction);
        String providerSettlementId = "MOCK-STL-T-" + UUID.randomUUID().toString().substring(0, 8);
        SettlementExecutionAttempt attempt = createAttempt(instruction, providerSettlementId);

        String collisionEventId = "evt-collision-" + UUID.randomUUID().toString().substring(0, 8);

        // Payload 1: Amount 50000.0000
        String payload1 = objectMapper.writeValueAsString(Map.of(
                "eventId", collisionEventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", providerSettlementId,
                "status", "SETTLED",
                "utrNumber", "UTR-COLLISION-ORIGINAL",
                "amount", "50000.0000",
                "currency", "INR",
                "direction", "DEBIT",
                "providerAccountId", MockBankingProvider.MOCK_ESCROW_ACCOUNT
        ));

        // Delivery 1: Accepted and processed
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        WebhookEvent eventAfter1 = webhookEventRepo.findByProviderAndProviderEventId("MOCK", collisionEventId).orElseThrow();
        String originalHash = eventAfter1.getPayloadHash();

        // Payload 2: Same eventId, but conflicting amount (99999.0000)
        String payload2 = objectMapper.writeValueAsString(Map.of(
                "eventId", collisionEventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", providerSettlementId,
                "status", "SETTLED",
                "utrNumber", "UTR-COLLISION-TAMPERED",
                "amount", "99999.0000",
                "currency", "INR",
                "direction", "DEBIT",
                "providerAccountId", MockBankingProvider.MOCK_ESCROW_ACCOUNT
        ));

        // Delivery 2: Must be rejected with 400 Bad Request (payload hash mismatch)
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest());

        // Existing WebhookEvent remains unchanged with original hash
        WebhookEvent eventAfterConflict = webhookEventRepo.findByProviderAndProviderEventId("MOCK", collisionEventId).orElseThrow();
        assertThat(eventAfterConflict.getPayloadHash()).isEqualTo(originalHash);

        // Only 1 observation recorded (from payload 1), none from conflicting payload
        List<SettlementObservation> observations = observationRepo.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getUtrNumber()).isEqualTo("UTR-COLLISION-ORIGINAL");
    }

    @Test
    @DisplayName("PART U: Webhook/poll conflict -> CONFLICT blocks finalizer before E7, REVERSAL_RECOVERY_REQUIRED after E7, 0 auto compensation")
    void partU_webhookAndPollConflict_reversalSafety() throws Exception {
        // Scenario A: Conflict before finalization
        UUID instructionA = UUID.randomUUID();
        ReleaseInstruction instA = createInstruction(instructionA, ReleaseInstructionStatus.PENDING_SETTLEMENT);
        ReleaseReservation resA = createReservation(instA);
        String provSettleIdA = "MOCK-STL-UA-" + UUID.randomUUID().toString().substring(0, 8);
        SettlementExecutionAttempt attemptA = createAttempt(instA, provSettleIdA);

        // Record FINAL_SUCCESS via webhook
        String eventIdA = "evt-ua-" + UUID.randomUUID().toString().substring(0, 8);
        String payloadA = objectMapper.writeValueAsString(Map.of(
                "eventId", eventIdA,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", provSettleIdA,
                "status", "SETTLED",
                "utrNumber", "UTR-UA-SUCCESS",
                "amount", "50000.0000",
                "currency", "INR",
                "direction", "DEBIT",
                "providerAccountId", MockBankingProvider.MOCK_ESCROW_ACCOUNT
        ));
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadA)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk());

        // Subsequent poll reports REVERSED
        observationService.recordObservation(
                com.finx.release.observation.service.RecordObservationCommand.builder()
                        .releaseInstructionId(instructionA)
                        .executionAttemptId(attemptA.getId())
                        .source(SettlementObservationSource.STATUS_POLL)
                        .sourceReference("reversal-" + provSettleIdA)
                        .provider(attemptA.getProvider())
                        .providerOperationKey(attemptA.getProviderOperationKey())
                        .providerSettlementId(provSettleIdA)
                        .rawProviderStatus("REVERSED")
                        .utrNumber("UTR-UA-REVERSED")
                        .observedAt(Instant.now().plusSeconds(1))
                        .build()
        );

        // Evidence reader detects conflict
        AuthoritativeObservationResult obsResultA = evidenceReader.getLatestAuthoritativeFinalObservation(instructionA);
        assertThat(obsResultA.status()).isEqualTo(AuthoritativeStatus.CONFLICT);

        // Finalizer before E7 must fail closed and block finalization
        assertThatThrownBy(() -> finalizer.finalizeRelease(instructionA, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authoritative FINAL_SUCCESS required, but found CONFLICT");

        // Release remains PENDING_SETTLEMENT, reservation remains ACTIVE
        ReleaseInstruction refreshedA = instructionRepo.findById(instructionA).orElseThrow();
        assertThat(refreshedA.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        ReleaseReservation refreshedResA = reservationRepo.findById(resA.getId()).orElseThrow();
        assertThat(refreshedResA.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        // 0 journal writes for instruction A
        assertThat(journalRepo.findAll().stream().noneMatch(j -> j.getReferenceId().equals(instructionA))).isTrue();


        // Scenario B: Conflict after finalization
        UUID instructionB = UUID.randomUUID();
        ReleaseInstruction instB = createInstruction(instructionB, ReleaseInstructionStatus.PENDING_SETTLEMENT);
        ReleaseReservation resB = createReservation(instB);
        String provSettleIdB = "MOCK-STL-UB-" + UUID.randomUUID().toString().substring(0, 8);
        SettlementExecutionAttempt attemptB = createAttempt(instB, provSettleIdB);

        // Record clean FINAL_SUCCESS
        observationService.recordObservation(
                com.finx.release.observation.service.RecordObservationCommand.builder()
                        .releaseInstructionId(instructionB)
                        .executionAttemptId(attemptB.getId())
                        .source(SettlementObservationSource.STATUS_POLL)
                        .sourceReference(provSettleIdB)
                        .provider(attemptB.getProvider())
                        .providerOperationKey(attemptB.getProviderOperationKey())
                        .providerSettlementId(provSettleIdB)
                        .rawProviderStatus("SETTLED")
                        .utrNumber("UTR-UB-CLEAN")
                        .observedAt(Instant.now())
                        .build()
        );

        // Finalize successfully to SETTLED
        finalizer.finalizeRelease(instructionB, adminPrincipal);

        ReleaseInstruction settledB = instructionRepo.findById(instructionB).orElseThrow();
        assertThat(settledB.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);

        long journalsBeforeReversal = journalRepo.findAll().stream()
                .filter(j -> j.getReferenceId().equals(instructionB))
                .count();
        assertThat(journalsBeforeReversal).isEqualTo(1);

        // Later: bank sends REVERSED observation
        observationService.recordObservation(
                com.finx.release.observation.service.RecordObservationCommand.builder()
                        .releaseInstructionId(instructionB)
                        .executionAttemptId(attemptB.getId())
                        .source(SettlementObservationSource.STATUS_POLL)
                        .sourceReference("reversal-" + provSettleIdB)
                        .provider(attemptB.getProvider())
                        .providerOperationKey(attemptB.getProviderOperationKey())
                        .providerSettlementId(provSettleIdB)
                        .rawProviderStatus("REVERSED")
                        .utrNumber("UTR-UB-REVERSED")
                        .observedAt(Instant.now().plusSeconds(10))
                        .build()
        );

        // Subsequent call to finalizer must detect conflict and report REVERSAL_RECOVERY_REQUIRED
        assertThatThrownBy(() -> finalizer.finalizeRelease(instructionB, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVERSAL_RECOVERY_REQUIRED");

        // Invariant: ZERO automatic compensating journal entries created!
        long journalsAfterReversal = journalRepo.findAll().stream()
                .filter(j -> j.getReferenceId().equals(instructionB))
                .count();
        assertThat(journalsAfterReversal).isEqualTo(1); // exactly 1, no second reverse journal!

        // Reservation remains CONSUMED (not unconsumed automatically)
        ReleaseReservation resAfterReversal = reservationRepo.findById(resB.getId()).orElseThrow();
        assertThat(resAfterReversal.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
    }
}
