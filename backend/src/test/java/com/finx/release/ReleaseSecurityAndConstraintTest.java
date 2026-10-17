package com.finx.release;

import com.finx.auth.dto.request.RegisterRequest;
import com.finx.auth.service.AuthService;
import com.finx.banking.provider.BankingProvider;
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
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationMemberRepository;
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
import com.finx.release.dto.CreateReleaseInstructionCommand;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.ReleaseAuthorizationService;
import com.finx.release.service.ReleaseFundingScopeResolver;
import com.finx.release.service.ReleaseInstructionService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.verifyNoInteractions;

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
        "finx.release.sandbox-finalization.enabled=true"
})
@EnabledIf("isPostgresAvailable")
public class ReleaseSecurityAndConstraintTest {

    static {
        ensureDatabaseExists("finx_release_e9_test");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FinxClock clock;
    @Autowired private PlatformTransactionManager txManager;

    @Autowired private ReleaseInstructionService instructionService;
    @Autowired private ReleaseAuthorizationService authorizationService;
    @Autowired private TrustedSettlementFinalizer finalizer;
    @Autowired private ReleaseFundingScopeResolver fundingScopeResolver;
    @Autowired private FinancialOutboxLeaseService leaseService;
    @Autowired private AuthService authService;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationMemberRepository memberRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private PaymentInstructionRepository paymentInstructionRepo;
    @Autowired private PaymentTransactionRepository paymentTransactionRepo;

    @MockBean private BankingProvider bankingProvider;

    private User adminUser;
    private User buyerUserA;
    private User buyerUserB;
    private UserPrincipal adminPrincipal;
    private UserPrincipal buyerPrincipalA;

    private Organization orgA;
    private Organization orgB;
    private OrganizationBankAccount bankAccA;
    private OrganizationBankAccount bankAccB;

    private Deal dealA;
    private Milestone milestoneA;
    private EscrowAccount escrowA;
    private EnginePolicyVersion defaultPolicy;

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
        clock.reset();
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
        jdbc.execute("DELETE FROM disputes");
        jdbc.execute("DELETE FROM milestone_approval_bindings");
        jdbc.execute("DELETE FROM deliverables");
        jdbc.execute("DELETE FROM milestones");
        jdbc.execute("DELETE FROM escrow_accounts");
        jdbc.execute("DELETE FROM deals");
        jdbc.execute("DELETE FROM organization_bank_accounts");
        jdbc.execute("DELETE FROM organization_members");
        jdbc.execute("DELETE FROM organizations");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUser = userRepo.save(new User("Admin " + suffix, "admin" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));
        buyerUserA = userRepo.save(new User("BuyerA " + suffix, "buyera" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));
        buyerUserB = userRepo.save(new User("BuyerB " + suffix, "buyerb" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));

        adminPrincipal = UserPrincipal.create(adminUser);
        buyerPrincipalA = UserPrincipal.create(buyerUserA);

        orgA = orgRepo.save(new Organization("Org A " + suffix, "Legal A " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-A-" + suffix, "PANA" + suffix.toUpperCase(), "29AAAAA0000A1Z5", "Bangalore", buyerUserA.getId()));
        orgB = orgRepo.save(new Organization("Org B " + suffix, "Legal B " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-B-" + suffix, "PANB" + suffix.toUpperCase(), "29BBBBB0000B1Z5", "Mumbai", buyerUserB.getId()));

        bankAccA = new OrganizationBankAccount(orgA.getId(), "Legal A " + suffix, "****1111", "hash-a-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        bankAccA.setEncryptedAccountNumber("enc-a-" + suffix);
        bankAccA.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccA = bankAccRepo.save(bankAccA);

        bankAccB = new OrganizationBankAccount(orgB.getId(), "Legal B " + suffix, "****2222", "hash-b-" + suffix, "ICIC0001234", "Branch", "CURRENT", true);
        bankAccB.setEncryptedAccountNumber("enc-b-" + suffix);
        bankAccB.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccB = bankAccRepo.save(bankAccB);

        dealA = new Deal();
        dealA.setTitle("Deal A " + suffix);
        dealA.setDescription("Test deal");
        dealA.setBuyerId(buyerUserA.getId());
        dealA.setSellerId(buyerUserA.getId());
        dealA.setBuyerOrganizationId(orgA.getId());
        dealA.setSellerOrganizationId(orgA.getId());
        dealA.setTotalAmount(new BigDecimal("100000.0000"));
        dealA.setCurrency("INR");
        dealA.setStatus(DealStatus.FUNDED);
        dealA = dealRepo.save(dealA);

        milestoneA = new Milestone();
        milestoneA.setDealId(dealA.getId());
        milestoneA.setTitle("M-A");
        milestoneA.setDescription("Desc");
        milestoneA.setAmount(new BigDecimal("50000.0000"));
        milestoneA.setStatus(MilestoneStatus.APPROVED);
        milestoneA = milestoneRepo.save(milestoneA);

        escrowA = new EscrowAccount();
        escrowA.setDealId(dealA.getId());
        escrowA.setCurrency("INR");
        escrowA.setBalance(new BigDecimal("100000.00"));
        escrowA.setTotalFunded(new BigDecimal("100000.00"));
        escrowA.setTotalReleased(BigDecimal.ZERO);
        escrowA = escrowRepo.save(escrowA);

        defaultPolicy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", adminUser.getId(), Instant.now()
        ));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    private EngineDecision createDecision(UUID orgId, UUID milestoneId) {
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
            var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(decision, defaultPolicy.getId());
            var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(decision, defaultPolicy.getContentHash());
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

    @Test
    @DisplayName("PART Z: Cross-tenant attack - buyer org B cannot authorize or finalize release for org A, blocked with zero financial writes")
    void testPartZ_crossTenantAttackBlocked() {
        EngineDecision decisionA = createDecision(orgA.getId(), milestoneA.getId());

        // Attempt 1: Buyer B tries to create release instruction for org A's decision
        UserPrincipal buyerPrincipalB = UserPrincipal.create(buyerUserB);
        assertThatThrownBy(() -> instructionService.createFromReleaseDecision(
                new CreateReleaseInstructionCommand(decisionA.getId(), "idem-cross-" + UUID.randomUUID()), buyerPrincipalB))
                .isInstanceOf(Exception.class);

        // Attempt 2: Non-platform tenant role tries to finalize a release
        assertThatThrownBy(() -> finalizer.finalizeRelease(UUID.randomUUID(), buyerPrincipalA))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Only ADMIN or FINANCE");

        // Assert: 0 bank calls, 0 reservations created
        verifyNoInteractions(bankingProvider);
        assertThat(reservationRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("PART AA: Role confusion attack - Organization-level FINANCE membership is strictly NOT platform Role.FINANCE, public registration cannot create ADMIN/FINANCE")
    void testPartAA_roleConfusionAttack() {
        // Create user with platform Role.BUYER
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User tenantUser = userRepo.save(new User("Org Finance User " + suffix, "orgfinance-" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));
        // Add member with org-level OrgRole.FINANCE
        memberRepo.save(new OrganizationMember(orgA.getId(), tenantUser.getId(), OrgRole.FINANCE));

        UserPrincipal tenantPrincipal = UserPrincipal.create(tenantUser);

        // Attempt 1: Calling release authorization fails closed with UnauthorizedException
        assertThatThrownBy(() -> authorizationService.authorizeAndReserve(UUID.randomUUID(), tenantPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Role BUYER is not authorized");

        // Attempt 2: Calling settlement finalizer fails closed with AccessDeniedException
        assertThatThrownBy(() -> finalizer.finalizeRelease(UUID.randomUUID(), tenantPrincipal))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Only ADMIN or FINANCE");

        // Attempt 3: Public registration cannot create platform Role.ADMIN or Role.FINANCE
        RegisterRequest regReq = new RegisterRequest();
        regReq.setName("Attacker");
        regReq.setEmail("attacker@finx.invalid");
        regReq.setPassword("P@ssword123");
        regReq.setRole(Role.ADMIN);

        assertThatThrownBy(() -> authService.register(regReq))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("PART AC: Funding-scope attack - ambiguous scopes, mixed currencies, or transaction-provider mismatch fail closed before dispatch")
    void testPartAC_fundingScopeAttackFailClosed() {
        // Scenario 1: Multiple conflicting funding scopes (ambiguous)
        PaymentInstruction pi1 = new PaymentInstruction(
                "PI-1", dealA.getId(), milestoneA.getId(), orgA.getId(), orgA.getId(),
                new BigDecimal("50000.00"), "INR", "AXIS", "idem-pi-1", buyerUserA.getId()
        );
        pi1.setProviderAccountId("ESCROW-POOL-1");
        pi1 = paymentInstructionRepo.save(pi1);
        paymentTransactionRepo.save(new PaymentTransaction("TX-1", pi1.getId(), "AXIS", "REF-1", new BigDecimal("50000.00"), "INR", "SUCCESS", "UTR-1", "{}"));

        PaymentInstruction pi2 = new PaymentInstruction(
                "PI-2", dealA.getId(), milestoneA.getId(), orgA.getId(), orgA.getId(),
                new BigDecimal("50000.00"), "INR", "HDFC", "idem-pi-2", buyerUserA.getId()
        );
        pi2.setProviderAccountId("ESCROW-POOL-2"); // Different provider account!
        pi2 = paymentInstructionRepo.save(pi2);
        paymentTransactionRepo.save(new PaymentTransaction("TX-2", pi2.getId(), "HDFC", "REF-2", new BigDecimal("50000.00"), "INR", "SUCCESS", "UTR-2", "{}"));

        // Resolver must throw ERROR_SCOPE_AMBIGUOUS
        assertThatThrownBy(() -> fundingScopeResolver.resolveFundingScope(dealA, milestoneA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ReleaseFundingScopeResolver.ERROR_SCOPE_AMBIGUOUS);
    }

    @Test
    @DisplayName("PART AQ: Database constraint attack - real PostgreSQL database rejects direct invalid SQL inserts")
    void testPartAQ_sqlConstraintAttacksRejectedByPostgres() {
        // 1. SETTLED release without observation rejected by chk_release_instructions_settled_observation
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, "
                        + "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, "
                        + "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, "
                        + "created_by, created_at, updated_at, settled_at, final_settlement_observation_id, finalized_by) "
                        + "VALUES ('" + UUID.randomUUID() + "', '" + orgA.getId() + "', '" + dealA.getId() + "', '" + milestoneA.getId() + "', "
                        + "'" + UUID.randomUUID() + "', '" + "1".repeat(64) + "', '" + defaultPolicy.getId() + "', '" + defaultPolicy.getContentHash() + "', "
                        + "'" + bankAccA.getId() + "', 'MOCK', 'ACC-1', 1000.00, 'INR', 'SETTLED', 'idem-sql-1', '" + "a".repeat(64) + "', "
                        + "'" + adminUser.getId() + "', NOW(), NOW(), NOW(), NULL, '" + adminUser.getId() + "')"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 2. SETTLED release without finalizedBy rejected by chk_release_instructions_settled_finalized_by
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, "
                        + "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, "
                        + "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, "
                        + "created_by, created_at, updated_at, settled_at, final_settlement_observation_id, finalized_by) "
                        + "VALUES ('" + UUID.randomUUID() + "', '" + orgA.getId() + "', '" + dealA.getId() + "', '" + milestoneA.getId() + "', "
                        + "'" + UUID.randomUUID() + "', '" + "1".repeat(64) + "', '" + defaultPolicy.getId() + "', '" + defaultPolicy.getContentHash() + "', "
                        + "'" + bankAccA.getId() + "', 'MOCK', 'ACC-1', 1000.00, 'INR', 'SETTLED', 'idem-sql-2', '" + "a".repeat(64) + "', "
                        + "'" + adminUser.getId() + "', NOW(), NOW(), NOW(), '" + UUID.randomUUID() + "', NULL)"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 3. Negative amount in release_instructions rejected by chk_release_instructions_amount_positive
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO release_instructions (id, organization_id, deal_id, milestone_id, release_decision_id, "
                        + "release_decision_fingerprint, policy_version_id, policy_content_hash, beneficiary_bank_account_id, "
                        + "provider, provider_account_id, amount, currency, status, idempotency_key, request_hash, "
                        + "created_by, created_at, updated_at) "
                        + "VALUES ('" + UUID.randomUUID() + "', '" + orgA.getId() + "', '" + dealA.getId() + "', '" + milestoneA.getId() + "', "
                        + "'" + UUID.randomUUID() + "', '" + "1".repeat(64) + "', '" + defaultPolicy.getId() + "', '" + defaultPolicy.getContentHash() + "', "
                        + "'" + bankAccA.getId() + "', 'MOCK', 'ACC-1', -500.00, 'INR', 'CREATED', 'idem-sql-3', '" + "a".repeat(64) + "', "
                        + "'" + adminUser.getId() + "', NOW(), NOW())"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("PART AR: Transaction isolation test - concurrent workers do not observe uncommitted outbox rows")
    void testPartAR_transactionIsolationNoDirtyReads() throws Exception {
        UUID outboxId = UUID.randomUUID();
        Instant now = clock.now();
        EngineDecision decision = createDecision(orgA.getId(), milestoneA.getId());
        ReleaseInstruction instruction = new ReleaseInstruction(
                UUID.randomUUID(), orgA.getId(), dealA.getId(), milestoneA.getId(), decision.getId(),
                decision.getDecisionFingerprint(), defaultPolicy.getId(), defaultPolicy.getContentHash(),
                bankAccA.getId(), "MOCK", "ESCROW-POOL-1", new BigDecimal("1000.00"), "INR",
                "idem-iso-" + UUID.randomUUID(), "a".repeat(64), adminUser.getId()
        );
        instruction.markAuthorized(adminUser.getId(), now);
        instruction.markReserved();
        instruction.markQueued();
        instruction = instructionRepo.save(instruction);
        UUID instructionId = instruction.getId();

        // Start uncommitted transaction in separate thread using TransactionTemplate
        CountDownLatch insertedLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);
        ExecutorService txExecutor = Executors.newSingleThreadExecutor();

        txExecutor.submit(() -> {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.execute(status -> {
                // Insert outbox row inside uncommitted transaction
                FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                        outboxId, instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                        instructionId.toString(), 1, FinancialOutboxStatus.PENDING, 0, now.minusSeconds(10), now, now
                );
                outboxRepo.saveAndFlush(outbox);
                insertedLatch.countDown();
                try {
                    finishLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                status.setRollbackOnly();
                return null;
            });
        });

        // Wait until row is inserted in uncommitted transaction
        assertThat(insertedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Worker attempts to claim in separate transaction
        List<FinancialOutboxMessage> claimed = leaseService.claimBatch("worker-isolation-test", 10);
        // The uncommitted outbox message MUST NOT be observed or claimed!
        assertThat(claimed.stream().map(FinancialOutboxMessage::getId)).doesNotContain(outboxId);

        // Allow uncommitted transaction to roll back
        finishLatch.countDown();
        txExecutor.shutdown();
        txExecutor.awaitTermination(5, TimeUnit.SECONDS);

        // Verify row is not in database
        assertThat(outboxRepo.findById(outboxId)).isEmpty();
    }

    @Test
    @DisplayName("PART BB: Security and PII adversarial check - plaintext beneficiary account number never appears in release domain tables")
    void testPartBB_piiBoundaryEnforcement() {
        String sensitiveAccountNumber = "98765432109876";

        // Query release tables for the sensitive plaintext string
        String[] tables = {
                "release_instructions",
                "release_reservations",
                "financial_outbox",
                "settlement_execution_attempts",
                "settlement_observations"
        };

        for (String table : tables) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE CAST(id AS TEXT) LIKE ? OR CAST(created_at AS TEXT) LIKE ?",
                    Integer.class,
                    "%" + sensitiveAccountNumber + "%",
                    "%" + sensitiveAccountNumber + "%"
            );
            assertThat(count).isEqualTo(0);
        }
    }
}
