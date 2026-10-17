package com.finx.release;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.banking.dto.BankingDtos;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.time.FinxClock;
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
import com.finx.exception.DuplicateResourceException;
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
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.outbox.service.FinancialOutboxLeaseService;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.PreparedSettlementWorkItem;
import com.finx.release.execution.model.SettlementDispatchResult;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementRecoveryResult;
import com.finx.release.observation.service.SettlementStatusRecoveryService;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.ReleaseDecisionExecutionValidator;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.verification.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
@EnabledIf("isPostgresAvailable")
public class WorkerKilledMidHttpCallRecoveryTest {

    static {
        ensureDatabaseExists("finx_release_e9_test");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementStatusRecoveryService recoveryService;
    @Autowired private FinancialOutboxLeaseService leaseService;
    @Autowired private SettlementDispatchHandler dispatchHandler;
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
    @Autowired private com.finx.paymentorchestration.repository.PaymentInstructionRepository paymentInstructionRepo;
    @Autowired private com.finx.paymentorchestration.repository.PaymentTransactionRepository paymentTransactionRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private PiiEncryptionService piiEncryptionService;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private FinxClock clock;

    @MockBean private ReleaseDecisionExecutionValidator executionValidator;
    @MockBean private MilestoneApprovalBindingService approvalBindingService;

    @SpyBean private BankingProvider bankingProvider;

    private User adminUser;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private OrganizationBankAccount sellerBankAcc;
    private Deal testDeal;
    private Milestone testMilestone;
    private EscrowAccount testEscrow;
    private EnginePolicyVersion policy;
    private EngineDecision decision;

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

    @Autowired private javax.sql.DataSource dataSource;

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

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUser = userRepo.save(new User("Admin " + suffix, "admin" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));
        User buyerUser = userRepo.save(new User("Buyer " + suffix, "buyer" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));

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
        testDeal.setDescription("E9 Recovery Deal");
        testDeal.setBuyerId(buyerUser.getId());
        testDeal.setSellerId(adminUser.getId());
        testDeal.setBuyerOrganizationId(buyerOrg.getId());
        testDeal.setSellerOrganizationId(sellerOrg.getId());
        testDeal.setTotalAmount(new BigDecimal("50000.0000"));
        testDeal.setCurrency("INR");
        testDeal.setStatus(DealStatus.FUNDED);
        testDeal = dealRepo.save(testDeal);

        Instant now = Instant.now();
        testMilestone = new Milestone(testDeal.getId(), "M1", "M1 desc", 1, new BigDecimal("50000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400));
        testMilestone.setStatus(MilestoneStatus.APPROVED);
        testMilestone = milestoneRepo.save(testMilestone);

        testEscrow = new EscrowAccount(testDeal.getId(), new BigDecimal("50000.0000"), "INR");
        testEscrow.setStatus(EscrowAccountStatus.ACTIVE);
        testEscrow = escrowRepo.save(testEscrow);

        com.finx.paymentorchestration.entity.PaymentInstruction pi = new com.finx.paymentorchestration.entity.PaymentInstruction(
                "PI-" + suffix, testDeal.getId(), null, buyerOrg.getId(), sellerOrg.getId(),
                new BigDecimal("50000.0000"), "INR", "MOCK", "idem-pi-" + suffix, buyerUser.getId()
        );
        pi.setProviderAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        pi = paymentInstructionRepo.save(pi);

        com.finx.paymentorchestration.entity.PaymentTransaction pt = new com.finx.paymentorchestration.entity.PaymentTransaction(
                "TX-" + suffix, pi.getId(), "MOCK", "REF-" + suffix,
                new BigDecimal("50000.0000"), "INR", "SUCCESS", "UTR-" + suffix, "{}"
        );
        pt = paymentTransactionRepo.save(pt);

        policy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", adminUser.getId(), now
        ));

        decision = new EngineDecision();
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

        // Configure default mock behaviors for pre-dispatch guards
        doNothing().when(executionValidator).validateForExecution(any(), any(), any(), any());
        when(approvalBindingService.evaluateCurrentBinding(any(), any(), any()))
                .thenReturn(new ApprovalBindingEvaluation(true, true, null, null));
    }

    private ReleaseInstruction createInstruction(UUID id, ReleaseInstructionStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        ReleaseInstruction instruction = new ReleaseInstruction(
                id, buyerOrg.getId(), testDeal.getId(), testMilestone.getId(), decision.getId(),
                "1".repeat(64), policy.getId(), policy.getContentHash(), sellerBankAcc.getId(),
                "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT, new BigDecimal("50000.0000"), "INR",
                "idem-" + suffix, "3".repeat(64), adminUser.getId()
        );
        instruction.markAuthorized(adminUser.getId(), now);
        instruction.markReserved();
        instruction.markQueued();
        if (status == ReleaseInstructionStatus.DISPATCHING) {
            instruction.markDispatching();
        } else if (status == ReleaseInstructionStatus.PENDING_SETTLEMENT) {
            instruction.markDispatching();
            instruction.markPendingSettlement();
        } else if (status == ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED) {
            instruction.markDispatching();
            instruction.markUnknownReconciliationRequired();
        }
        return instructionRepo.save(instruction);
    }

    private ReleaseReservation createReservation(ReleaseInstruction instruction) {
        ReleaseReservation res = new ReleaseReservation(
                UUID.randomUUID(), instruction.getId(), testEscrow.getId(), testMilestone.getId(),
                new BigDecimal("50000.0000"), "INR"
        );
        return reservationRepo.save(res);
    }

    private FinancialOutboxMessage createOutbox(ReleaseInstruction instruction) {
        Instant now = Instant.now();
        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(), instruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now, now, now
        );
        outbox.markLeased("worker-1", now);
        return outboxRepo.save(outbox);
    }

    @Test
    @DisplayName("PART J: Worker killed mid HTTP call -> lease recovery does not retry, operation lookup recovers identity")
    void partJ_workerKilledMidHttpCallRecovery() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.DISPATCHING);
        ReleaseReservation reservation = createReservation(instruction);
        FinancialOutboxMessage outbox = createOutbox(instruction);

        String stableOpKey = "FINX-REL-" + instruction.getId();

        // Simulate provider accepts request using stable operation key
        BankingDtos.SettlementResult bankAccepted = bankingProvider.initiateSettlement(new BankingDtos.SettlementRequest(
                "SETTLE-REF-" + instruction.getId(),
                testDeal.getId(),
                testMilestone.getId(),
                new BigDecimal("50000.0000"),
                "INR",
                "Beneficiary Corp",
                "987654321098",
                "MOCK0001",
                stableOpKey
        ));

        // Process dies before TX-D2: Attempt committed as SENDING, Outbox is LEASED
        Instant now = Instant.now();
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), instruction.getId(), outbox.getId(), 1,
                instruction.getId().toString(), stableOpKey, "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT,
                sellerBankAcc.getId(), new BigDecimal("50000.0000"), "INR", instruction.getRequestHash(), "worker-1", now
        );
        attempt.markSending(now);
        attempt = attemptRepo.saveAndFlush(attempt);

        // 1. E4 Lease recovery MUST NOT place row into RETRY because attempt is network-started (SENDING)
        List<FinancialOutboxMessage> recoveredLeases = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ZERO, Duration.ofMinutes(5), 10);
        assertThat(recoveredLeases).noneMatch(m -> m.getId().equals(outbox.getId()));

        FinancialOutboxMessage outboxAfterLeaseScan = outboxRepo.findById(outbox.getId()).orElseThrow();
        assertThat(outboxAfterLeaseScan.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);

        // 2. E6 Operation-key recovery recovers identity
        reset(bankingProvider); // clear invocation counts so we can assert initiateSettlement is 0
        SettlementRecoveryResult recoveryResult = recoveryService.recoverAttempt(attempt.getId());

        assertThat(recoveryResult.recovered()).isTrue();
        assertThat(recoveryResult.observation().getProviderSettlementId()).isEqualTo(bankAccepted.providerSettlementId());

        // Zero second initiateSettlement during recovery
        verify(bankingProvider, never()).initiateSettlement(any());

        // Attempt is now ACKNOWLEDGED with provider settlement id bound
        SettlementExecutionAttempt refreshedAttempt = attemptRepo.findById(attempt.getId()).orElseThrow();
        assertThat(refreshedAttempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        assertThat(refreshedAttempt.getProviderSettlementId()).isEqualTo(bankAccepted.providerSettlementId());

        // Release is PENDING_SETTLEMENT
        ReleaseInstruction refreshedInst = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(refreshedInst.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        // Reservation remains ACTIVE
        ReleaseReservation refreshedRes = reservationRepo.findById(reservation.getId()).orElseThrow();
        assertThat(refreshedRes.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("PART K: Crash before actual provider call -> operation lookup returns NOT_FOUND, no blind redispatch")
    void partK_crashBeforeProviderCall_operationLookupNotFound_noBlindRedispatch() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);
        ReleaseReservation reservation = createReservation(instruction);
        FinancialOutboxMessage outbox = createOutbox(instruction);

        String stableOpKey = "FINX-REL-" + instruction.getId();

        // SENDING committed, but process died before calling provider
        Instant now = Instant.now();
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), instruction.getId(), outbox.getId(), 1,
                instruction.getId().toString(), stableOpKey, "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT,
                sellerBankAcc.getId(), new BigDecimal("50000.0000"), "INR", instruction.getRequestHash(), "worker-1", now
        );
        attempt.markSending(now);
        attempt = attemptRepo.saveAndFlush(attempt);

        reset(bankingProvider);

        // Execute recovery
        SettlementRecoveryResult recoveryResult = recoveryService.recoverAttempt(attempt.getId());

        assertThat(recoveryResult.recoveryAction()).isEqualTo("OPERATION_LOOKUP_NOT_FOUND");
        assertThat(recoveryResult.observation().getFinality()).isEqualTo(SettlementObservationFinality.NOT_FOUND);

        // NO blind redispatch: initiateSettlement must NEVER be called
        verify(bankingProvider, never()).initiateSettlement(any());

        // Attempt remains in SENDING (or original state), no second attempt created
        SettlementExecutionAttempt refreshedAttempt = attemptRepo.findById(attempt.getId()).orElseThrow();
        assertThat(refreshedAttempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.SENDING);
        assertThat(refreshedAttempt.getProviderSettlementId()).isNull();

        // Release remains UNKNOWN_RECON_REQUIRED
        ReleaseInstruction refreshedInst = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(refreshedInst.getStatus()).isEqualTo(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);

        // Reservation remains ACTIVE
        ReleaseReservation refreshedRes = reservationRepo.findById(reservation.getId()).orElseThrow();
        assertThat(refreshedRes.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("PART L: Provider timeout after acceptance -> state UNKNOWN, recovery finds existing settlement, initiate=1")
    void partL_providerTimeoutAfterAcceptance_recoveryFindsExistingSettlement() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.QUEUED);
        ReleaseReservation reservation = createReservation(instruction);
        FinancialOutboxMessage outbox = createOutbox(instruction);

        String stableOpKey = "FINX-REL-" + instruction.getId();

        Instant now = Instant.now();
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), instruction.getId(), outbox.getId(), 1,
                instruction.getId().toString(), stableOpKey, "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT,
                sellerBankAcc.getId(), new BigDecimal("50000.0000"), "INR", instruction.getRequestHash(), "worker-1", now
        );
        // Saved in PREPARED state as required for dispatchHandler
        attempt = attemptRepo.saveAndFlush(attempt);

        // Configure mock provider spy: first accept internally, then throw timeout
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException("NETWORK_TIMEOUT_AFTER_ACCEPTANCE");
        }).when(bankingProvider).initiateSettlement(any());

        // Dispatch via SettlementDispatchHandler
        SettlementDispatchResult dispatchResult = dispatchHandler.dispatchPreparedWorkItem(
                new PreparedSettlementWorkItem(
                        outbox.getId(),
                        instruction.getId(),
                        attempt.getId(),
                        attempt.getOperationKey(),
                        attempt.getProviderOperationKey(),
                        "worker-1"
                )
        );

        // Result is unknown
        assertThat(dispatchResult.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);

        // Attempt is UNKNOWN, Release is UNKNOWN_RECON_REQUIRED, Outbox is PROCESSED
        SettlementExecutionAttempt attemptAfterTimeout = attemptRepo.findById(attempt.getId()).orElseThrow();
        assertThat(attemptAfterTimeout.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);

        ReleaseInstruction instAfterTimeout = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(instAfterTimeout.getStatus()).isEqualTo(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);

        FinancialOutboxMessage outboxAfterTimeout = outboxRepo.findById(outbox.getId()).orElseThrow();
        assertThat(outboxAfterTimeout.getStatus()).isEqualTo(FinancialOutboxStatus.PROCESSED);

        // Later: Recovery operation finds the existing settlement!
        SettlementRecoveryResult recoveryResult = recoveryService.recoverAttempt(attempt.getId());
        assertThat(recoveryResult.recovered()).isTrue();
        assertThat(recoveryResult.observation().getFinality()).isEqualTo(SettlementObservationFinality.FINAL_SUCCESS);

        // Release transitions to PENDING_SETTLEMENT
        ReleaseInstruction instRecovered = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(instRecovered.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        // Exactly 1 initiateSettlement call in total, never 2
        verify(bankingProvider, times(1)).initiateSettlement(any());
    }

    @Test
    @DisplayName("PART M: Provider timeout before acceptance -> recovery returns NOT_FOUND, no auto redispatch")
    void partM_providerTimeoutBeforeAcceptance_recoveryReturnsNotFound() {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), ReleaseInstructionStatus.QUEUED);
        ReleaseReservation reservation = createReservation(instruction);
        FinancialOutboxMessage outbox = createOutbox(instruction);

        String stableOpKey = "FINX-REL-" + instruction.getId();

        Instant now = Instant.now();
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), instruction.getId(), outbox.getId(), 1,
                instruction.getId().toString(), stableOpKey, "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT,
                sellerBankAcc.getId(), new BigDecimal("50000.0000"), "INR", instruction.getRequestHash(), "worker-1", now
        );
        attempt = attemptRepo.saveAndFlush(attempt);

        // Configure mock provider spy: throw BEFORE recording anything
        doThrow(new RuntimeException("NETWORK_TIMEOUT_BEFORE_ACCEPTANCE"))
                .when(bankingProvider).initiateSettlement(any());

        // Dispatch via SettlementDispatchHandler
        SettlementDispatchResult dispatchResult = dispatchHandler.dispatchPreparedWorkItem(
                new PreparedSettlementWorkItem(
                        outbox.getId(),
                        instruction.getId(),
                        attempt.getId(),
                        attempt.getOperationKey(),
                        attempt.getProviderOperationKey(),
                        "worker-1"
                )
        );

        assertThat(dispatchResult.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);

        // Attempt is UNKNOWN, Release is UNKNOWN_RECON_REQUIRED
        SettlementExecutionAttempt attemptAfterTimeout = attemptRepo.findById(attempt.getId()).orElseThrow();
        assertThat(attemptAfterTimeout.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);

        ReleaseInstruction instAfterTimeout = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(instAfterTimeout.getStatus()).isEqualTo(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);

        // Recovery lookup: returns NOT_FOUND
        SettlementRecoveryResult recoveryResult = recoveryService.recoverAttempt(attempt.getId());
        assertThat(recoveryResult.recoveryAction()).isEqualTo("OPERATION_LOOKUP_NOT_FOUND");
        assertThat(recoveryResult.observation().getFinality()).isEqualTo(SettlementObservationFinality.NOT_FOUND);

        // Total initiateSettlement calls = 1 (the initial timed-out call). Recovery made 0 calls.
        verify(bankingProvider, times(1)).initiateSettlement(any());

        // Still UNKNOWN_RECON_REQUIRED
        ReleaseInstruction instFinal = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(instFinal.getStatus()).isEqualTo(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);
    }

    @Test
    @DisplayName("PART N: Mock provider idempotency attack -> 20 concurrent threads with same key result in 1 debit")
    void partN_mockProviderIdempotencyAttack() throws Exception {
        MockBankingProvider mockProvider = (MockBankingProvider) bankingProvider;
        String stableOpKey = "FINX-REL-CONCURRENT-" + UUID.randomUUID();

        BankingDtos.SettlementRequest request = new BankingDtos.SettlementRequest(
                stableOpKey,
                testDeal.getId(),
                testMilestone.getId(),
                new BigDecimal("50000.0000"),
                "INR",
                "Beneficiary Corp",
                "987654321098",
                "MOCK0001",
                stableOpKey
        );

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<BankingDtos.SettlementResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    BankingDtos.SettlementResult res = mockProvider.initiateSettlement(request);
                    results.add(res);
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
        assertThat(results).hasSize(threadCount);

        // All 20 threads received the exact same providerSettlementId and UTR
        String expectedId = results.get(0).providerSettlementId();
        String expectedUtr = results.get(0).utrNumber();
        for (BankingDtos.SettlementResult r : results) {
            assertThat(r.providerSettlementId()).isEqualTo(expectedId);
            assertThat(r.utrNumber()).isEqualTo(expectedUtr);
        }

        // Check simulated bank feed: exactly 1 debit entry for this settlement reference
        List<BankingDtos.BankTransactionRecord> feed = mockProvider.fetchTransactions(null, null);
        long matchingDebits = feed.stream()
                .filter(tx -> stableOpKey.equals(tx.transactionReference()))
                .count();
        assertThat(matchingDebits).isEqualTo(1);

        // Conflicting request under the same idempotency key must be rejected
        BankingDtos.SettlementRequest conflictingRequest = new BankingDtos.SettlementRequest(
                stableOpKey,
                testDeal.getId(),
                testMilestone.getId(),
                new BigDecimal("99999.0000"), // Different amount!
                "INR",
                "Beneficiary Corp",
                "987654321098",
                "MOCK0001",
                stableOpKey
        );

        assertThatThrownBy(() -> mockProvider.initiateSettlement(conflictingRequest))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Conflicting mock settlement request");

        // Verify still exactly 1 debit entry in feed
        long debitsAfterConflict = mockProvider.fetchTransactions(null, null).stream()
                .filter(tx -> stableOpKey.equals(tx.transactionReference()))
                .count();
        assertThat(debitsAfterConflict).isEqualTo(1);
    }
}
