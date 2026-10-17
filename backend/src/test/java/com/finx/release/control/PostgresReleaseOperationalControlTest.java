package com.finx.release.control;

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
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        "finx.banking.provider=mock"
})
@EnabledIf("isPostgresAvailable")
public class PostgresReleaseOperationalControlTest {

    static {
        ensureDatabaseExists("finx_release_e9_test");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private ReleaseOperationalControlService controlService;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;

    private User adminUser;
    private UserPrincipal adminPrincipal;
    private Organization testOrg;
    private OrganizationBankAccount bankAccount;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;
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
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE " + dbName);
            }
        } catch (SQLException ignored) {
        }
    }

    @BeforeEach
    void setUp() {
        cleanupDb();

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        adminUser = userRepo.save(new User("Admin E11 " + suffix, "admin.e11." + suffix + "@finx.invalid", "hash", Role.ADMIN, UserStatus.ACTIVE));
        adminPrincipal = UserPrincipal.create(adminUser);

        testOrg = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", adminUser.getId()));

        bankAccount = new OrganizationBankAccount(testOrg.getId(), "Legal " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        bankAccount.setEncryptedAccountNumber("enc-" + suffix);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount = bankAccRepo.save(bankAccount);

        deal = new Deal();
        deal.setTitle("E11 Control Deal " + suffix);
        deal.setDescription("Test deal");
        deal.setBuyerId(adminUser.getId());
        deal.setSellerId(adminUser.getId());
        deal.setBuyerOrganizationId(testOrg.getId());
        deal.setSellerOrganizationId(testOrg.getId());
        deal.setTotalAmount(BigDecimal.valueOf(100000));
        deal.setCurrency("INR");
        deal.setStatus(DealStatus.FUNDED);
        deal = dealRepo.save(deal);

        milestone = new Milestone();
        milestone.setDealId(deal.getId());
        milestone.setTitle("M1-" + suffix);
        milestone.setDescription("Desc");
        milestone.setAmount(BigDecimal.valueOf(50000));
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone = milestoneRepo.save(milestone);

        escrowAccount = new EscrowAccount();
        escrowAccount.setDealId(deal.getId());
        escrowAccount.setCurrency("INR");
        escrowAccount.setBalance(BigDecimal.valueOf(100000));
        escrowAccount.setTotalFunded(BigDecimal.valueOf(100000));
        escrowAccount.setTotalReleased(BigDecimal.ZERO);
        escrowAccount = escrowRepo.save(escrowAccount);

        policy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", adminUser.getId(), Instant.now()
        ));

        decision = createDecision(testOrg.getId(), milestone.getId());
    }

    private EngineDecision createDecision(UUID orgId, UUID milestoneId) {
        Instant now = Instant.now();
        EngineDecision dec = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(dec, orgId);
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(dec, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(dec, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(dec, milestoneId);
            var fDec = EngineDecision.class.getDeclaredField("decision"); fDec.setAccessible(true); fDec.set(dec, DecisionStatus.ALLOW);
            var fOut = EngineDecision.class.getDeclaredField("evaluationOutcome"); fOut.setAccessible(true); fOut.set(dec, EvaluationOutcome.SUCCESS);
            var fInFp = EngineDecision.class.getDeclaredField("inputFingerprint"); fInFp.setAccessible(true); fInFp.set(dec, "0".repeat(64));
            var fDecFp = EngineDecision.class.getDeclaredField("decisionFingerprint"); fDecFp.setAccessible(true); fDecFp.set(dec, "1".repeat(64));
            var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(dec, policy.getId());
            var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(dec, policy.getContentHash());
            var fEvId = EngineDecision.class.getDeclaredField("evaluationId"); fEvId.setAccessible(true); fEvId.set(dec, UUID.randomUUID());
            var fCoId = EngineDecision.class.getDeclaredField("correlationId"); fCoId.setAccessible(true); fCoId.set(dec, UUID.randomUUID());
            var fRuleV = EngineDecision.class.getDeclaredField("ruleVersion"); fRuleV.setAccessible(true); fRuleV.set(dec, "1.0.0");
            var fRea = EngineDecision.class.getDeclaredField("reasonCodesJson"); fRea.setAccessible(true); fRea.set(dec, "[]");
            var fEvi = EngineDecision.class.getDeclaredField("evidenceReferencesJson"); fEvi.setAccessible(true); fEvi.set(dec, "[]");
            var fEvAt = EngineDecision.class.getDeclaredField("evaluatedAt"); fEvAt.setAccessible(true); fEvAt.set(dec, now);
            var fVal = EngineDecision.class.getDeclaredField("validUntil"); fVal.setAccessible(true); fVal.set(dec, now.plusSeconds(3600));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return decisionRepo.save(dec);
    }

    private void cleanupDb() {
        jdbc.execute("UPDATE release_instructions SET status = 'PENDING_SETTLEMENT', settled_at = NULL, final_settlement_observation_id = NULL");
        jdbc.execute("DELETE FROM journal_lines");
        jdbc.execute("DELETE FROM journal_entries");
        jdbc.execute("DELETE FROM escrow_ledger");
        jdbc.execute("DELETE FROM milestone_approval_bindings");
        jdbc.execute("DELETE FROM deliverables");
        jdbc.execute("DELETE FROM settlement_observations");
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");
        jdbc.execute("DELETE FROM engine_decisions");
        jdbc.execute("DELETE FROM engine_policy_versions");
        jdbc.execute("DELETE FROM milestones");
        jdbc.execute("DELETE FROM escrow_accounts");
        jdbc.execute("DELETE FROM deals");
    }

    private ReleaseInstruction createQueuedRelease(BigDecimal amount) {
        return txTemplate.execute(status -> {
            ReleaseInstruction ri = new ReleaseInstruction(
                    UUID.randomUUID(), testOrg.getId(), deal.getId(), milestone.getId(),
                    decision.getId(), decision.getDecisionFingerprint(), policy.getId(), policy.getContentHash(),
                    bankAccount.getId(), "MOCK", "acc-1", amount, "INR",
                    "idemp-" + UUID.randomUUID(), "a".repeat(64), adminUser.getId()
            );
            ri.markAuthorized(adminUser.getId(), Instant.now());
            ri.markReserved();
            ri.markQueued();
            ri = instructionRepo.save(ri);

            ReleaseReservation res = new ReleaseReservation(
                    UUID.randomUUID(), ri.getId(), escrowAccount.getId(), milestone.getId(), amount, "INR"
            );
            reservationRepo.save(res);

            FinancialOutboxMessage msg = FinancialOutboxMessage.createReleaseDispatch(ri, Instant.now());
            outboxRepo.save(msg);

            return ri;
        });
    }

    @Test
    @DisplayName("V36 Migration: Outbox HELD status persists cleanly under PostgreSQL constraint")
    void v36Migration_outboxHeldStatusPersists() {
        ReleaseInstruction ri = createQueuedRelease(BigDecimal.valueOf(5000));

        controlService.holdPreDispatchRelease(ri.getId(), adminPrincipal, "INVESTIGATION");

        FinancialOutboxMessage heldMsg = outboxRepo.findByReleaseInstructionId(ri.getId()).orElseThrow();
        assertThat(heldMsg.getStatus()).isEqualTo(FinancialOutboxStatus.HELD);
        assertThat(heldMsg.getLockedAt()).isNull();
        assertThat(heldMsg.getLockedBy()).isNull();
        assertThat(heldMsg.getProcessedAt()).isNull();
        assertThat(heldMsg.getDeadLetteredAt()).isNull();

        ReleaseInstruction heldRi = instructionRepo.findById(ri.getId()).orElseThrow();
        assertThat(heldRi.getStatus()).isEqualTo(ReleaseInstructionStatus.MANUAL_REVIEW);

        ReleaseReservation res = reservationRepo.findByReleaseInstructionId(ri.getId()).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("Resume: Held release transitions MANUAL_REVIEW -> QUEUED and HELD -> PENDING")
    void resumeHeldRelease_roundTrip() {
        ReleaseInstruction ri = createQueuedRelease(BigDecimal.valueOf(5000));

        controlService.holdPreDispatchRelease(ri.getId(), adminPrincipal, "INVESTIGATION");
        controlService.resumeHeldRelease(ri.getId(), adminPrincipal, "CLEARED");

        FinancialOutboxMessage pendingMsg = outboxRepo.findByReleaseInstructionId(ri.getId()).orElseThrow();
        assertThat(pendingMsg.getStatus()).isEqualTo(FinancialOutboxStatus.PENDING);

        ReleaseInstruction queuedRi = instructionRepo.findById(ri.getId()).orElseThrow();
        assertThat(queuedRi.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        ReleaseReservation res = reservationRepo.findByReleaseInstructionId(ri.getId()).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("Hold vs Worker race: Worker claim query skips HELD messages")
    void holdVsWorker_workerClaimQuerySkipsHeld() {
        ReleaseInstruction ri = createQueuedRelease(BigDecimal.valueOf(5000));

        // 1. Admin applies hold
        controlService.holdPreDispatchRelease(ri.getId(), adminPrincipal, "FRAUD_CHECK");

        // 2. Worker executes claim query
        List<FinancialOutboxMessage> claimed = outboxRepo.claimEligibleMessages(Instant.now().plusSeconds(10), 10);
        assertThat(claimed).noneMatch(m -> m.getReleaseInstructionId().equals(ri.getId()));
    }

    @Test
    @DisplayName("Hold vs Worker race: If worker leases first, hold fails safely")
    void holdVsWorker_workerLeasePreventsHold() {
        ReleaseInstruction ri = createQueuedRelease(BigDecimal.valueOf(5000));

        // Worker leases message
        txTemplate.executeWithoutResult(s -> {
            FinancialOutboxMessage msg = outboxRepo.findByReleaseInstructionIdForUpdate(ri.getId()).orElseThrow();
            msg.markLeased("worker-node-1", Instant.now());
            outboxRepo.save(msg);
        });

        // Admin hold must fail safely
        assertThatThrownBy(() -> controlService.holdPreDispatchRelease(ri.getId(), adminPrincipal, "HOLD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot hold release: outbox status is LEASED");
    }

    @Test
    @DisplayName("Cancel: Cancels release pre-dispatch, dead-letters outbox, releases reservation, zero balance mutation")
    void cancelPreDispatch_releasesReservationCleanly() {
        ReleaseInstruction ri = createQueuedRelease(BigDecimal.valueOf(5000));

        BigDecimal escrowBalanceBefore = escrowRepo.findById(escrowAccount.getId()).orElseThrow().getBalance();

        controlService.cancelPreDispatchRelease(ri.getId(), adminPrincipal, "ADMIN_ERROR");

        ReleaseInstruction cancelledRi = instructionRepo.findById(ri.getId()).orElseThrow();
        assertThat(cancelledRi.getStatus()).isEqualTo(ReleaseInstructionStatus.CANCELLED);

        FinancialOutboxMessage deadMsg = outboxRepo.findByReleaseInstructionId(ri.getId()).orElseThrow();
        assertThat(deadMsg.getStatus()).isEqualTo(FinancialOutboxStatus.DEAD_LETTER);

        ReleaseReservation res = reservationRepo.findByReleaseInstructionId(ri.getId()).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.RELEASED);

        BigDecimal escrowBalanceAfter = escrowRepo.findById(escrowAccount.getId()).orElseThrow().getBalance();
        assertThat(escrowBalanceAfter).isEqualByComparingTo(escrowBalanceBefore);
    }

    @Test
    @DisplayName("Cancel BLOCKED when network request has started")
    void cancelBlockedWhenNetworkStarted() {
        ReleaseInstruction ri = createQueuedRelease(BigDecimal.valueOf(5000));

        // Simulate network-started attempt
        txTemplate.executeWithoutResult(s -> {
            FinancialOutboxMessage outbox = outboxRepo.findByReleaseInstructionId(ri.getId()).orElseThrow();
            SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                    UUID.randomUUID(), ri.getId(), outbox.getId(), 1, "op-1", "prov-op-1", "MOCK", "acc-1",
                    bankAccount.getId(), BigDecimal.valueOf(5000), "INR", "a".repeat(64), "worker-1", Instant.now()
            );
            attempt.markSending(Instant.now());
            attemptRepo.save(attempt);
        });

        assertThatThrownBy(() -> controlService.cancelPreDispatchRelease(ri.getId(), adminPrincipal, "CANCEL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL_SAFETY_VIOLATION");
    }
}
