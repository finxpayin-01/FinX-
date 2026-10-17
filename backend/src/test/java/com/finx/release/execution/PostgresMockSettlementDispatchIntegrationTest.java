package com.finx.release.execution;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.provider.BankingProvider;
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
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.outbox.service.FinancialOutboxLeaseService;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.PreparedSettlementWorkItem;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementDispatchResult;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.execution.service.SettlementDispatchStateService;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e5_test}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:finx_user}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:finx_password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "rate-limit.enabled=false",
        "finx.banking.provider=mock",
        "finx.release.sandbox-dispatch.enabled=true",
        "razorpay.sandbox-mode=true"
})
@EnabledIf("isPostgresAvailable")
public class PostgresMockSettlementDispatchIntegrationTest {

    private static final String DB_NAME = "finx_release_e5_test";

    static {
        ensureDatabaseExists(DB_NAME);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementDispatchHandler dispatchHandler;
    @Autowired private SettlementDispatchStateService stateService;
    @Autowired private FinancialOutboxLeaseService leaseService;
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
    @Autowired private SettlementInstructionRepository settlementInstructionRepo;
    @Autowired private PiiEncryptionService piiEncryptionService;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;

    @SpyBean private ReleaseDecisionExecutionValidator executionValidator;
    @SpyBean private MilestoneApprovalBindingService approvalBindingService;
    @SpyBean private BankingProvider bankingProvider;

    private User adminUser;
    private Organization sellerOrg;
    private Organization buyerOrg;
    private OrganizationBankAccount sellerBankAcc;
    private Deal testDeal;
    private Milestone testMilestone;
    private EscrowAccount testEscrow;
    private ReleaseInstruction testInstruction;
    private ReleaseReservation testReservation;
    private FinancialOutboxMessage testOutbox;
    private SettlementExecutionAttempt testAttempt;
    private String plainAccountNumber;

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
        // Reset spies to pristine real invocation behavior
        reset(bankingProvider);
        doNothing().when(executionValidator).validateForExecution(any(), any(), any(), any());
        when(approvalBindingService.evaluateCurrentBinding(any(), any(), any()))
                .thenReturn(new ApprovalBindingEvaluation(true, true, "APPROVAL_BINDING_CURRENT", null));

        // Clean tables in reverse dependency order
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

        plainAccountNumber = "987654321098";
        String encAcc = piiEncryptionService.encrypt(plainAccountNumber);
        sellerBankAcc = new OrganizationBankAccount(sellerOrg.getId(), "Beneficiary Corp", "****1098", "hash-" + suffix, "MOCK0001", "Branch", "CURRENT", true);
        sellerBankAcc.setEncryptedAccountNumber(encAcc);
        sellerBankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        sellerBankAcc.setProvider("MOCK");
        sellerBankAcc.setVerificationReference("MOCK-PENNY-" + suffix);
        sellerBankAcc.setVerifiedAt(Instant.now());
        sellerBankAcc = bankAccRepo.save(sellerBankAcc);

        testDeal = new Deal();
        testDeal.setTitle("Deal " + suffix);
        testDeal.setDescription("E5 Sandbox Deal");
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

        // Reconciled PaymentInstruction funding evidence for ReleaseFundingScopeResolver
        PaymentInstruction pi = new PaymentInstruction(
                "PI-" + suffix, testDeal.getId(), null, buyerOrg.getId(), sellerOrg.getId(),
                new BigDecimal("50000.0000"), "INR", "MOCK", "idem-" + suffix, buyerUser.getId()
        );
        pi.setProviderAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        pi = paymentInstructionRepo.save(pi);

        PaymentTransaction pt = new PaymentTransaction(
                "TX-" + suffix, pi.getId(), "MOCK", "REF-" + suffix,
                new BigDecimal("50000.0000"), "INR", "SUCCESS", "UTR-" + suffix, "{}"
        );
        paymentTransactionRepo.save(pt);

        // Platform Policy and Decision for foreign key constraints
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

        // ReleaseInstruction in QUEUED status
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
        testInstruction = instructionRepo.save(testInstruction);

        // Active ReleaseReservation
        testReservation = new ReleaseReservation(
                UUID.randomUUID(), testInstruction.getId(), testEscrow.getId(), testMilestone.getId(),
                new BigDecimal("50000.0000"), "INR"
        );
        testReservation = reservationRepo.save(testReservation);

        // Leased FinancialOutboxMessage
        testOutbox = new FinancialOutboxMessage(
                UUID.randomUUID(), testInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                testInstruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now, now, now
        );
        testOutbox.markLeased("worker-e5-node", now);
        testOutbox = outboxRepo.save(testOutbox);

        // PREPARED SettlementExecutionAttempt
        testAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), testInstruction.getId(), testOutbox.getId(), 1,
                testInstruction.getId().toString(), ProviderSettlementOperationKey.release(testInstruction.getId()),
                "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT, sellerBankAcc.getId(),
                new BigDecimal("50000.0000"), "INR", testInstruction.getRequestHash(),
                "worker-e5-node", now
        );
        testAttempt = attemptRepo.save(testAttempt);
    }

    @Test
    @DisplayName("PART AW: Real Postgres Happy Path: Mock dispatch -> ACKNOWLEDGED, PENDING_SETTLEMENT, PROCESSED, reservation ACTIVE, 0 ledger/escrow writes")
    void testRealPostgresMockDispatchHappyPath() {
        PreparedSettlementWorkItem workItem = new PreparedSettlementWorkItem(
                testOutbox.getId(),
                testInstruction.getId(),
                testAttempt.getId(),
                testAttempt.getOperationKey(),
                testAttempt.getProviderOperationKey(),
                "worker-e5-node"
        );

        long journalCountBefore = journalRepo.count();
        long settlementInstructionCountBefore = settlementInstructionRepo.count();

        SettlementDispatchResult result = dispatchHandler.dispatchPreparedWorkItem(workItem);

        // Dispatch outcome
        assertThat(result.status()).isEqualTo(SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        assertThat(result.providerSettlementId()).isNotNull().isNotBlank();
        assertThat(result.providerStatus()).isEqualTo("SETTLED");

        // Reload Attempt from PostgreSQL
        SettlementExecutionAttempt reloadedAttempt = attemptRepo.findById(testAttempt.getId()).orElseThrow();
        assertThat(reloadedAttempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        assertThat(reloadedAttempt.getProviderSettlementId()).isEqualTo(result.providerSettlementId());
        assertThat(reloadedAttempt.getProviderStatus()).isEqualTo("SETTLED");
        assertThat(reloadedAttempt.getRequestStartedAt()).isNotNull();
        assertThat(reloadedAttempt.getResponseReceivedAt()).isNotNull();
        assertThat(reloadedAttempt.getRetryable()).isFalse();

        // Reload ReleaseInstruction from PostgreSQL
        ReleaseInstruction reloadedInstruction = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(reloadedInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        // Reload Outbox from PostgreSQL
        FinancialOutboxMessage reloadedOutbox = outboxRepo.findById(testOutbox.getId()).orElseThrow();
        assertThat(reloadedOutbox.getStatus()).isEqualTo(FinancialOutboxStatus.PROCESSED);
        assertThat(reloadedOutbox.getProcessedAt()).isNotNull();
        assertThat(reloadedOutbox.getLockedBy()).isNull();

        // Reload Reservation from PostgreSQL: remains ACTIVE
        ReleaseReservation reloadedReservation = reservationRepo.findById(testReservation.getId()).orElseThrow();
        assertThat(reloadedReservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(reloadedReservation.getConsumedAt()).isNull();

        // Reload Milestone from PostgreSQL: remains APPROVED
        Milestone reloadedMilestone = milestoneRepo.findById(testMilestone.getId()).orElseThrow();
        assertThat(reloadedMilestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);

        // Reload Escrow from PostgreSQL: financial amounts unchanged
        EscrowAccount reloadedEscrow = escrowRepo.findById(testEscrow.getId()).orElseThrow();
        assertThat(reloadedEscrow.getBalance()).isEqualByComparingTo(new BigDecimal("50000.0000"));
        assertThat(reloadedEscrow.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);

        // Zero Ledger and SettlementInstruction writes
        assertThat(journalRepo.count()).isEqualTo(journalCountBefore);
        assertThat(settlementInstructionRepo.count()).isEqualTo(settlementInstructionCountBefore);
    }

    @Test
    @DisplayName("PART AX: Unknown Outcome Postgres: Provider exception -> UNKNOWN, UNKNOWN_RECON_REQUIRED, PROCESSED, no second call")
    void testUnknownOutcomePostgres() {
        doThrow(new RuntimeException("Simulated bank connection drop")).when(bankingProvider).initiateSettlement(any());

        PreparedSettlementWorkItem workItem = new PreparedSettlementWorkItem(
                testOutbox.getId(),
                testInstruction.getId(),
                testAttempt.getId(),
                testAttempt.getOperationKey(),
                testAttempt.getProviderOperationKey(),
                "worker-e5-node"
        );

        SettlementDispatchResult result = dispatchHandler.dispatchPreparedWorkItem(workItem);

        assertThat(result.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("PROVIDER_DISPATCH_EXCEPTION");

        // Reload from PostgreSQL
        SettlementExecutionAttempt reloadedAttempt = attemptRepo.findById(testAttempt.getId()).orElseThrow();
        assertThat(reloadedAttempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(reloadedAttempt.getErrorCode()).isEqualTo("PROVIDER_DISPATCH_EXCEPTION");
        assertThat(reloadedAttempt.getRetryable()).isFalse();

        ReleaseInstruction reloadedInstruction = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(reloadedInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);

        FinancialOutboxMessage reloadedOutbox = outboxRepo.findById(testOutbox.getId()).orElseThrow();
        assertThat(reloadedOutbox.getStatus()).isEqualTo(FinancialOutboxStatus.PROCESSED);
        assertThat(reloadedOutbox.getLastErrorCode()).isEqualTo("PROVIDER_DISPATCH_EXCEPTION");

        ReleaseReservation reloadedReservation = reservationRepo.findById(testReservation.getId()).orElseThrow();
        assertThat(reloadedReservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        // Second dispatch attempt fails closed and makes ZERO additional provider calls
        assertThatThrownBy(() -> dispatchHandler.dispatchPreparedWorkItem(workItem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOVERY_REQUIRED");

        verify(bankingProvider, times(1)).initiateSettlement(any());
    }

    @Test
    @DisplayName("PART AY: Crash after SENDING: Lease recovery must NOT retry network-started outbox row")
    void testCrashAfterSendingLeaseRecoveryBlocksRetry() {
        // Execute beginDispatch (TX-D1 commits)
        stateService.beginDispatch(testOutbox.getId(), testAttempt.getId(), "worker-e5-node");

        // Advance lease locked_at to simulate timeout (10 minutes ago)
        jdbc.update("UPDATE financial_outbox SET locked_at = NOW() - INTERVAL '10 minutes' WHERE id = ?",
                testOutbox.getId());

        // Run E4 recoverExpiredPreDispatchLeases
        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ofMinutes(5), Duration.ofMinutes(1), 10
        );

        // Must NOT be recovered
        assertThat(recovered).isEmpty();

        FinancialOutboxMessage reloadedOutbox = outboxRepo.findById(testOutbox.getId()).orElseThrow();
        assertThat(reloadedOutbox.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
    }

    @Test
    @DisplayName("PART AZ: Crash after provider response: Lease recovery blocked and redispatch blocked")
    void testCrashAfterProviderAcceptanceBlocksRetry() {
        // beginDispatch commits
        stateService.beginDispatch(testOutbox.getId(), testAttempt.getId(), "worker-e5-node");

        // Simulate provider responded but process crashed before TX-D2
        // DB state: Attempt SENDING, Release DISPATCHING, Outbox LEASED
        jdbc.update("UPDATE financial_outbox SET locked_at = NOW() - INTERVAL '10 minutes' WHERE id = ?",
                testOutbox.getId());

        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ofMinutes(5), Duration.ofMinutes(1), 10
        );
        assertThat(recovered).isEmpty();

        PreparedSettlementWorkItem workItem = new PreparedSettlementWorkItem(
                testOutbox.getId(),
                testInstruction.getId(),
                testAttempt.getId(),
                testAttempt.getOperationKey(),
                testAttempt.getProviderOperationKey(),
                "worker-e5-node"
        );

        // Second handler call must fail closed
        assertThatThrownBy(() -> dispatchHandler.dispatchPreparedWorkItem(workItem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOVERY_REQUIRED");

        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("PART BA: Provider account mismatch -> UNKNOWN, no settlement finality")
    void testProviderAccountMismatchPostgres() {
        doReturn(new SettlementResult("STL-WRONG", "SETTLED", "UTR-1", Instant.now(), "{}", "WRONG_ACCOUNT"))
                .when(bankingProvider).initiateSettlement(any());

        PreparedSettlementWorkItem workItem = new PreparedSettlementWorkItem(
                testOutbox.getId(),
                testInstruction.getId(),
                testAttempt.getId(),
                testAttempt.getOperationKey(),
                testAttempt.getProviderOperationKey(),
                "worker-e5-node"
        );

        SettlementDispatchResult result = dispatchHandler.dispatchPreparedWorkItem(workItem);

        assertThat(result.status()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("PROVIDER_ACCOUNT_MISMATCH");

        ReleaseInstruction reloadedInstruction = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(reloadedInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);
    }

    @Test
    @DisplayName("PART BB: Immediate Mock 'SETTLED' response does NOT finalize financial settlement")
    void testImmediateSettledDoesNotFinalizeSettlement() {
        PreparedSettlementWorkItem workItem = new PreparedSettlementWorkItem(
                testOutbox.getId(),
                testInstruction.getId(),
                testAttempt.getId(),
                testAttempt.getOperationKey(),
                testAttempt.getProviderOperationKey(),
                "worker-e5-node"
        );

        dispatchHandler.dispatchPreparedWorkItem(workItem);

        ReleaseInstruction reloadedInstruction = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(reloadedInstruction.getStatus())
                .withFailMessage("ReleaseInstruction MUST be PENDING_SETTLEMENT, not SETTLED")
                .isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT)
                .isNotEqualTo(ReleaseInstructionStatus.SETTLED);

        ReleaseReservation reloadedReservation = reservationRepo.findById(testReservation.getId()).orElseThrow();
        assertThat(reloadedReservation.getStatus())
                .withFailMessage("ReleaseReservation MUST remain ACTIVE, not CONSUMED")
                .isEqualTo(ReleaseReservationStatus.ACTIVE)
                .isNotEqualTo(ReleaseReservationStatus.CONSUMED);

        Milestone reloadedMilestone = milestoneRepo.findById(testMilestone.getId()).orElseThrow();
        assertThat(reloadedMilestone.getStatus())
                .withFailMessage("Milestone MUST remain APPROVED, not COMPLETED")
                .isEqualTo(MilestoneStatus.APPROVED)
                .isNotEqualTo(MilestoneStatus.COMPLETED);

        assertThat(journalRepo.count()).isEqualTo(0);
        assertThat(settlementInstructionRepo.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("PART BC: Sensitive Data Protection: Plaintext bank account never persisted in any table")
    void testNoPlaintextAccountPersisted() {
        PreparedSettlementWorkItem workItem = new PreparedSettlementWorkItem(
                testOutbox.getId(),
                testInstruction.getId(),
                testAttempt.getId(),
                testAttempt.getOperationKey(),
                testAttempt.getProviderOperationKey(),
                "worker-e5-node"
        );

        dispatchHandler.dispatchPreparedWorkItem(workItem);

        // Database scan for plaintext account number
        Integer countInAttempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_execution_attempts WHERE CAST(id AS TEXT) LIKE ? " +
                        "OR operation_key LIKE ? OR provider_operation_key LIKE ? OR COALESCE(error_code, '') LIKE ?",
                Integer.class,
                "%" + plainAccountNumber + "%",
                "%" + plainAccountNumber + "%",
                "%" + plainAccountNumber + "%",
                "%" + plainAccountNumber + "%"
        );
        assertThat(countInAttempts).isEqualTo(0);

        Integer countInOutbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM financial_outbox WHERE operation_key LIKE ? OR COALESCE(last_error_code, '') LIKE ?",
                Integer.class,
                "%" + plainAccountNumber + "%",
                "%" + plainAccountNumber + "%"
        );
        assertThat(countInOutbox).isEqualTo(0);

        Integer countInInstructions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_instructions WHERE idempotency_key LIKE ?",
                Integer.class,
                "%" + plainAccountNumber + "%"
        );
        assertThat(countInInstructions).isEqualTo(0);

        // Verify column structure of settlement_execution_attempts has NO raw response column
        List<String> columnNames = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'settlement_execution_attempts'",
                String.class
        );
        assertThat(columnNames).doesNotContain("raw_response", "rawresponse", "utr", "utr_number");
    }
}
