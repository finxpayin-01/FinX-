package com.finx.release;

import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.repository.DisputeRepository;
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
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.execution.service.SettlementDispatchHandler;
import com.finx.release.execution.service.SettlementDispatchStateService;
import com.finx.release.finalizer.model.SettlementFinalizationResult;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.verification.service.PiiEncryptionService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e9_test}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:finx_user}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:finx_password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.connection-timeout=20000",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "rate-limit.enabled=false",
        "finx.banking.provider=mock",
        "finx.release.sandbox-finalization.enabled=true",
        "finx.release.sandbox-dispatch.enabled=true",
        "razorpay.sandbox-mode=true"
})
@EnabledIf("isPostgresAvailable")
public class DisputeRaisedDuringDispatchRaceTest {

    private static final String DB_NAME = "finx_release_e9_test";

    static {
        ensureDatabaseExists(DB_NAME);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SettlementDispatchHandler dispatchHandler;
    @Autowired private SettlementDispatchStateService dispatchStateService;
    @Autowired private TrustedSettlementFinalizer finalizer;
    @Autowired private LedgerService ledgerService;
    @SpyBean private BankingProvider bankingProvider;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private DisputeRepository disputeRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private EscrowLedgerRepository escrowLedgerRepo;
    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private SettlementInstructionRepository settlementInstructionRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PaymentInstructionRepository paymentInstructionRepo;
    @Autowired private PaymentTransactionRepository paymentTransactionRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private PiiEncryptionService piiEncryptionService;
    @Autowired private com.finx.milestone.repository.DeliverableRepository deliverableRepo;
    @Autowired private com.finx.approval.repository.MilestoneApprovalBindingRepository approvalBindingRepo;
    @Autowired private com.finx.approval.service.MilestoneApprovalFingerprintService fingerprintService;
    @org.springframework.boot.test.mock.mockito.MockBean private com.finx.engine.release.ReleaseDecisionEngine releaseDecisionEngine;

    private User adminUser;
    private User buyerUser;
    private UserPrincipal adminPrincipal;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private OrganizationBankAccount sellerBankAcc;
    private Deal testDeal;
    private Milestone testMilestone;
    private EscrowAccount testEscrow;
    private ReleaseInstruction testInstruction;
    private ReleaseReservation testReservation;
    private FinancialOutboxMessage testOutbox;
    private SettlementExecutionAttempt testAttempt;

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
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE DATABASE " + dbName);
            }
        } catch (Exception ignored) {
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            hikari.setMaximumPoolSize(35);
        }
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }

        jdbcTemplate.execute("UPDATE release_instructions SET status = 'PENDING_SETTLEMENT', settled_at = NULL, final_settlement_observation_id = NULL");
        jdbcTemplate.execute("DELETE FROM journal_lines");
        jdbcTemplate.execute("DELETE FROM journal_entries");
        jdbcTemplate.execute("DELETE FROM escrow_ledger");
        jdbcTemplate.execute("DELETE FROM settlement_observations");
        jdbcTemplate.execute("DELETE FROM webhook_events");
        jdbcTemplate.execute("DELETE FROM settlement_execution_attempts");
        jdbcTemplate.execute("DELETE FROM settlement_instructions");
        jdbcTemplate.execute("DELETE FROM financial_outbox");
        jdbcTemplate.execute("DELETE FROM release_reservations");
        jdbcTemplate.execute("DELETE FROM release_instructions");
        jdbcTemplate.execute("DELETE FROM engine_decisions");
        jdbcTemplate.execute("DELETE FROM engine_policy_versions");
        jdbcTemplate.execute("DELETE FROM payment_transactions");
        jdbcTemplate.execute("DELETE FROM payment_instructions");
        jdbcTemplate.execute("DELETE FROM disputes");
        jdbcTemplate.execute("DELETE FROM milestone_approval_bindings");
        jdbcTemplate.execute("DELETE FROM deliverables");
        jdbcTemplate.execute("DELETE FROM milestones");
        jdbcTemplate.execute("DELETE FROM escrow_accounts");
        jdbcTemplate.execute("DELETE FROM deals");
        jdbcTemplate.execute("DELETE FROM organization_bank_accounts");
        jdbcTemplate.execute("DELETE FROM organization_members");
        jdbcTemplate.execute("DELETE FROM organizations");

        String suffix = UUID.randomUUID().toString().substring(0, 8);

        adminUser = userRepo.save(new User("Admin " + suffix, "admin" + suffix + "@finx.invalid", "pass", Role.ADMIN, UserStatus.ACTIVE));
        buyerUser = userRepo.save(new User("Buyer " + suffix, "buyer" + suffix + "@finx.invalid", "pass", Role.BUYER, UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getName(), adminUser.getEmail(), "pass", Role.ADMIN, UserStatus.ACTIVE, List.of());

        buyerOrg = orgRepo.save(new Organization("Buyer Org " + suffix, "Buyer Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-B-" + suffix, "PANB" + suffix.toUpperCase(), "29AAAAA0000A1Z5", "Bangalore", buyerUser.getId()));

        sellerOrg = orgRepo.save(new Organization("Seller Org " + suffix, "Seller Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-S-" + suffix, "PANS" + suffix.toUpperCase(), "29AAAAA0000A1Z6", "Mumbai", adminUser.getId()));

        String plainAccountNumber = "987654321098";
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
        testDeal.setDescription("E9 Dispute Test Deal");
        testDeal.setBuyerId(buyerUser.getId());
        testDeal.setSellerId(adminUser.getId());
        testDeal.setBuyerOrganizationId(buyerOrg.getId());
        testDeal.setSellerOrganizationId(sellerOrg.getId());
        testDeal.setTotalAmount(new BigDecimal("50000.0000"));
        testDeal.setCurrency("INR");
        testDeal.setStatus(DealStatus.FUNDED);
        testDeal = dealRepo.save(testDeal);

        // Pre-fund ledger accounts: Dr 1010-ESCROW-POOL, Cr 2010-ESCROW-LIABILITY
        ledgerService.postJournalEntry(new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                testDeal.getId(),
                testDeal.getId(),
                null,
                "INR",
                "Escrow initial funding",
                adminUser.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, new BigDecimal("50000.0000"), "Fund escrow pool"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, new BigDecimal("50000.0000"), "Fund escrow liability")
                )
        ));

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        testMilestone = new Milestone(testDeal.getId(), "M1", "M1 desc", 1, new BigDecimal("50000.0000"), "INR", now.plusSeconds(86400), now.plusSeconds(86400));
        testMilestone.setStatus(MilestoneStatus.APPROVED);
        testMilestone = milestoneRepo.saveAndFlush(testMilestone);
        testMilestone = milestoneRepo.findById(testMilestone.getId()).orElseThrow();

        com.finx.milestone.entity.Deliverable del = new com.finx.milestone.entity.Deliverable(testMilestone.getId(), buyerUser.getId(), "del.pdf", "https://del.pdf", "Artifact");
        del.setStatus(com.finx.milestone.entity.DeliverableStatus.APPROVED);
        del = deliverableRepo.saveAndFlush(del);
        com.finx.milestone.entity.Deliverable reloadedDel = deliverableRepo.findById(del.getId()).orElseThrow();

        String delFp = fingerprintService.computeDeliverablesFingerprint(List.of(reloadedDel));
        String mFp = fingerprintService.computeMilestoneFingerprint(testMilestone);
        String appFp = fingerprintService.computeApprovalFingerprint(
                buyerOrg.getId(), testDeal.getId(), testMilestone.getId(),
                buyerUser.getId(), com.finx.approval.entity.MilestoneApprovalAuthority.BUYER, null,
                mFp, delFp, now
        );

        com.finx.approval.entity.MilestoneApprovalBinding binding = new com.finx.approval.entity.MilestoneApprovalBinding(
                UUID.randomUUID(),
                buyerOrg.getId(),
                testDeal.getId(),
                testMilestone.getId(),
                buyerUser.getId(),
                com.finx.approval.entity.MilestoneApprovalAuthority.BUYER,
                mFp,
                delFp,
                appFp,
                null,
                now,
                now
        );
        approvalBindingRepo.save(binding);

        testEscrow = new EscrowAccount(testDeal.getId(), new BigDecimal("50000.0000"), "INR");
        testEscrow.setStatus(EscrowAccountStatus.ACTIVE);
        testEscrow = escrowRepo.save(testEscrow);

        PaymentInstruction pi = new PaymentInstruction(
                "PI-" + suffix, testDeal.getId(), null, buyerOrg.getId(), sellerOrg.getId(),
                new BigDecimal("50000.0000"), "INR", "MOCK", "idem-" + suffix, buyerUser.getId()
        );
        pi.setProviderAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        paymentInstructionRepo.save(pi);

        PaymentTransaction pt = new PaymentTransaction(
                "TX-" + suffix, pi.getId(), "MOCK", "REF-" + suffix,
                new BigDecimal("50000.0000"), "INR", "SUCCESS", "UTR-" + suffix, "{}"
        );
        paymentTransactionRepo.save(pt);

        EnginePolicyVersion policy = policyRepo.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE,
                com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_KEY,
                com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_VERSION
        ).orElseGet(() -> policyRepo.save(new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.RELEASE,
                com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_KEY,
                com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_VERSION,
                com.finx.engine.policy.release.ReleasePolicyDefinition.getExpectedContentHash(),
                "1.0",
                com.finx.engine.policy.release.ReleasePolicyDefinition.DESCRIPTION,
                com.finx.engine.policy.release.ReleasePolicyDefinition.getCanonicalPolicyContent(),
                adminUser.getId(),
                Instant.now()
        )));

        List<java.util.Map<String, Object>> refs = new java.util.ArrayList<>();
        refs.add(java.util.Map.of("sourceType", "ORGANIZATION_BANK_ACCOUNT", "sourceId", sellerBankAcc.getId().toString(), "provenance", "MOCK"));

        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();

        for (EngineType engineType : com.finx.engine.policy.release.ReleasePolicyDefinition.REQUIRED_ENGINES) {
            EngineDecision comp = new EngineDecision();
            try {
                var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(comp, buyerOrg.getId());
                var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(comp, engineType);
                var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(comp, "MILESTONE");
                var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(comp, testMilestone.getId());
                var fDec = EngineDecision.class.getDeclaredField("decision"); fDec.setAccessible(true); fDec.set(comp, DecisionStatus.ALLOW);
                var fOut = EngineDecision.class.getDeclaredField("evaluationOutcome"); fOut.setAccessible(true); fOut.set(comp, EvaluationOutcome.SUCCESS);
                var fInFp = EngineDecision.class.getDeclaredField("inputFingerprint"); fInFp.setAccessible(true); fInFp.set(comp, "0".repeat(64));
                var fDecFp = EngineDecision.class.getDeclaredField("decisionFingerprint"); fDecFp.setAccessible(true); fDecFp.set(comp, "1".repeat(64));
                var fPolId = EngineDecision.class.getDeclaredField("policyVersionId"); fPolId.setAccessible(true); fPolId.set(comp, policy.getId());
                var fPolH = EngineDecision.class.getDeclaredField("policyContentHash"); fPolH.setAccessible(true); fPolH.set(comp, policy.getContentHash());
                var fEvId = EngineDecision.class.getDeclaredField("evaluationId"); fEvId.setAccessible(true); fEvId.set(comp, evalId);
                var fCoId = EngineDecision.class.getDeclaredField("correlationId"); fCoId.setAccessible(true); fCoId.set(comp, corrId);
                var fRuleV = EngineDecision.class.getDeclaredField("ruleVersion"); fRuleV.setAccessible(true); fRuleV.set(comp, engineType.name() + ":1.0.0");
                var fRea = EngineDecision.class.getDeclaredField("reasonCodesJson"); fRea.setAccessible(true); fRea.set(comp, "[\"ALLOW_GATE\"]");
                var fEvi = EngineDecision.class.getDeclaredField("evidenceReferencesJson"); fEvi.setAccessible(true); fEvi.set(comp, "[]");
                var fEvAt = EngineDecision.class.getDeclaredField("evaluatedAt"); fEvAt.setAccessible(true); fEvAt.set(comp, now);
                var fVal = EngineDecision.class.getDeclaredField("validUntil"); fVal.setAccessible(true); fVal.set(comp, now.plusSeconds(3600));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            comp = decisionRepo.save(comp);
            refs.add(java.util.Map.of("sourceType", "ENGINE_DECISION", "sourceId", comp.getId().toString(), "provenance", engineType.name()));
        }

        String refsJson;
        try {
            refsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(refs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

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
            var fEvId = EngineDecision.class.getDeclaredField("evaluationId"); fEvId.setAccessible(true); fEvId.set(decision, evalId);
            var fCoId = EngineDecision.class.getDeclaredField("correlationId"); fCoId.setAccessible(true); fCoId.set(decision, corrId);
            var fRuleV = EngineDecision.class.getDeclaredField("ruleVersion"); fRuleV.setAccessible(true); fRuleV.set(decision, com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_KEY + ":" + com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_VERSION);
            var fRea = EngineDecision.class.getDeclaredField("reasonCodesJson"); fRea.setAccessible(true); fRea.set(decision, "[\"" + com.finx.engine.policy.release.ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW + "\"]");
            var fEvi = EngineDecision.class.getDeclaredField("evidenceReferencesJson"); fEvi.setAccessible(true); fEvi.set(decision, refsJson);
            var fEvAt = EngineDecision.class.getDeclaredField("evaluatedAt"); fEvAt.setAccessible(true); fEvAt.set(decision, now);
            var fVal = EngineDecision.class.getDeclaredField("validUntil"); fVal.setAccessible(true); fVal.set(decision, now.plusSeconds(3600));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        decision = decisionRepo.save(decision);

        com.finx.engine.model.RecordEngineDecisionCommand cmd = com.finx.engine.model.RecordEngineDecisionCommand.builder()
                .engineType(EngineType.RELEASE)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .organizationId(buyerOrg.getId())
                .subjectType("MILESTONE")
                .subjectId(testMilestone.getId())
                .policyVersionId(policy.getId())
                .policyContentHash(policy.getContentHash())
                .ruleVersion(com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_KEY + ":" + com.finx.engine.policy.release.ReleasePolicyDefinition.POLICY_VERSION)
                .inputFingerprint("0".repeat(64))
                .reasonCodes(List.of(com.finx.engine.policy.release.ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW))
                .evaluationId(evalId)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build();

        com.finx.engine.release.ReleaseEvaluationResult evalResult = new com.finx.engine.release.ReleaseEvaluationResult(
                evalId, corrId, null, List.of(), cmd, List.of()
        );

        org.mockito.Mockito.when(releaseDecisionEngine.evaluateMilestone(any(), any(), any(), any(), any(), any()))
                .thenReturn(evalResult);

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

        testReservation = new ReleaseReservation(
                UUID.randomUUID(), testInstruction.getId(), testEscrow.getId(), testMilestone.getId(),
                new BigDecimal("50000.0000"), "INR"
        );
        testReservation = reservationRepo.save(testReservation);

        testOutbox = new FinancialOutboxMessage(
                UUID.randomUUID(), testInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                testInstruction.getId().toString(), 1, FinancialOutboxStatus.PENDING, 0, now, now, now
        );
        testOutbox.markLeased("worker-e9-node", now);
        testOutbox = outboxRepo.save(testOutbox);

        testAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), testInstruction.getId(), testOutbox.getId(), 1,
                testInstruction.getId().toString(), ProviderSettlementOperationKey.release(testInstruction.getId()),
                "MOCK", MockBankingProvider.MOCK_ESCROW_ACCOUNT, sellerBankAcc.getId(),
                new BigDecimal("50000.0000"), "INR", testInstruction.getRequestHash(),
                "worker-e9-node", now
        );
        testAttempt = attemptRepo.save(testAttempt);
    }

    @Test
    @DisplayName("PART O — Scenario A: Dispute becomes OPEN before TX-D1 preflight completes -> provider calls 0, no SENDING")
    void testScenarioA_disputeRaisedBeforeDispatchPreflight() {
        // Open a dispute before dispatch
        Dispute dispute = new Dispute(testDeal.getId(), testMilestone.getId(), buyerUser.getId(), "Quality defects reported");
        disputeRepo.save(dispute);
        testDeal.setStatus(DealStatus.DISPUTED);
        dealRepo.save(testDeal);

        PreparedSettlementWorkItem workItem = new PreparedSettlementWorkItem(
                testOutbox.getId(),
                testInstruction.getId(),
                testAttempt.getId(),
                testAttempt.getOperationKey(),
                testAttempt.getProviderOperationKey(),
                "worker-e9-node"
        );

        // Dispatch must fail closed
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> dispatchHandler.dispatchPreparedWorkItem(workItem));
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).containsIgnoringCase("dispute");

        // Provider must NOT be called
        verify(bankingProvider, never()).initiateSettlement(any());

        // Attempt must NOT transition to SENDING
        SettlementExecutionAttempt attemptInDb = attemptRepo.findById(testAttempt.getId()).orElseThrow();
        assertThat(attemptInDb.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);

        // Instruction remains QUEUED
        ReleaseInstruction instructionInDb = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(instructionInDb.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        // Reservation remains ACTIVE
        ReleaseReservation reservationInDb = reservationRepo.findById(testReservation.getId()).orElseThrow();
        assertThat(reservationInDb.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("PART O — Scenario B: Dispute raised after SENDING committed -> do NOT blind cancel, finality records ledger, Deal DISPUTED preserved")
    void testScenarioB_disputeRaisedDuringExternalNetworkFlight() {
        // 1. Commit TX-D1 beginDispatch
        dispatchStateService.beginDispatch(testOutbox.getId(), testAttempt.getId(), "worker-e9-node");

        SettlementExecutionAttempt sendingAttempt = attemptRepo.findById(testAttempt.getId()).orElseThrow();
        assertThat(sendingAttempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.SENDING);
        ReleaseInstruction dispatchingInst = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(dispatchingInst.getStatus()).isEqualTo(ReleaseInstructionStatus.DISPATCHING);

        // 2. While in flight, dispute opens on Deal
        Dispute dispute = new Dispute(testDeal.getId(), testMilestone.getId(), buyerUser.getId(), "Late delivery dispute raised while bank debit in flight");
        disputeRepo.save(dispute);
        testDeal.setStatus(DealStatus.DISPUTED);
        testDeal = dealRepo.save(testDeal);

        // 3. Provider debit succeeds in external world and TX-D2 commits acknowledgement
        String providerSettlementId = "MOCK-SETTLE-" + UUID.randomUUID();
        dispatchStateService.recordAcknowledged(
                testOutbox.getId(),
                testAttempt.getId(),
                "worker-e9-node",
                providerSettlementId,
                "{\"status\":\"ACCEPTED\"}",
                Instant.now()
        );

        // 4. Trusted settlement observation recorded
        String utr = "UTR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        SettlementObservation obs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(testInstruction.getId())
                .executionAttemptId(testAttempt.getId())
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-" + UUID.randomUUID())
                .providerOperationKey(testAttempt.getProviderOperationKey())
                .providerSettlementId(providerSettlementId)
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber(utr)
                .observedAmount(new BigDecimal("50000.0000"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("e".repeat(64))
                .observedAt(Instant.now())
                .build();
        obs = observationRepo.save(obs);

        // 5. Finalizer called
        SettlementFinalizationResult finResult = finalizer.finalizeRelease(testInstruction.getId(), adminPrincipal);
        assertThat(finResult).isNotNull();
        assertThat(finResult.releaseInstructionId()).isEqualTo(testInstruction.getId());

        // 6. Assert financial truth is finalized
        ReleaseInstruction settledInst = instructionRepo.findById(testInstruction.getId()).orElseThrow();
        assertThat(settledInst.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        assertThat(settledInst.getSettledAt()).isNotNull();
        assertThat(settledInst.getFinalSettlementObservationId()).isEqualTo(obs.getId());

        ReleaseReservation consumedRes = reservationRepo.findById(testReservation.getId()).orElseThrow();
        assertThat(consumedRes.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);

        EscrowAccount finalEscrow = escrowRepo.findById(testEscrow.getId()).orElseThrow();
        assertThat(finalEscrow.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finalEscrow.getTotalReleased()).isEqualByComparingTo(new BigDecimal("50000.0000"));

        List<EscrowLedger> ledgers = escrowLedgerRepo.findByEscrowAccountIdOrderByCreatedAtDesc(testEscrow.getId());
        assertThat(ledgers).hasSize(1);
        assertThat(ledgers.get(0).getTransactionType()).isEqualTo(TransactionType.RELEASE);
        assertThat(ledgers.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("50000.0000"));

        JournalEntry journal = journalRepo.findByTransactionTypeAndReferenceId(
                LedgerTransactionType.MILESTONE_SETTLEMENT, testInstruction.getId()).orElseThrow();
        assertThat(journal.getTotalDebit()).isEqualByComparingTo(new BigDecimal("50000.0000"));
        assertThat(journal.getTotalCredit()).isEqualByComparingTo(new BigDecimal("50000.0000"));

        // Milestone is completed
        Milestone completedM = milestoneRepo.findById(testMilestone.getId()).orElseThrow();
        assertThat(completedM.getStatus()).isEqualTo(MilestoneStatus.COMPLETED);

        // Crucial E9 check: Deal status DISPUTED was preserved! Not marked COMPLETED!
        Deal dealAfterFinalization = dealRepo.findById(testDeal.getId()).orElseThrow();
        assertThat(dealAfterFinalization.getStatus()).isEqualTo(DealStatus.DISPUTED);

        // SettlementInstruction writes: 0 (legacy bypass not used)
        assertThat(settlementInstructionRepo.findAll()).isEmpty();
    }
}
