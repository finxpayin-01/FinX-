package com.finx.reconciliation;

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
import com.finx.ledger.entity.JournalEntry;
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
import com.finx.reconciliation.dto.DeterministicReconciliationResult;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.dto.ReleaseReconciliationResult;
import com.finx.reconciliation.model.ReleaseReconciliationStatus;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.BankStatementEvidenceFingerprint;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.reconciliation.service.ReleaseReconciliationEvidenceReader;
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
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e8_test}",
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
public class PostgresReleaseClosedLoopReconciliationTest {

    private static final String DB_NAME = "finx_release_e8_test";

    static {
        ensureDatabaseExists(DB_NAME);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeterministicReconciliationMatcher matcher;
    @Autowired private ReleaseReconciliationEvidenceReader reader;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private BankStatementImportRepository importRepo;
    @Autowired private BankStatementLineRepository lineRepo;
    @Autowired private ReconciliationRunRepository runRepo;
    @Autowired private ReconciliationItemRepository itemRepo;

    @Autowired private UserRepository userRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private EscrowAccountRepository escrowRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;

    private User admin;
    private UserPrincipal adminPrincipal;
    private Organization testOrg;
    private OrganizationBankAccount bankAccount;
    private Deal testDeal;
    private Milestone testMilestone;

    private static final String PROVIDER = "MOCK";
    private static final String PROVIDER_ACCOUNT_ID = "MOCK_ACC_E8";
    private static final String CURRENCY = "INR";

    private Instant windowStart;
    private Instant windowEnd;

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
        jdbc.execute("UPDATE release_instructions SET status = 'CANCELLED', settled_at = NULL, final_settlement_observation_id = NULL");
        jdbc.execute("DELETE FROM reconciliation_items");
        jdbc.execute("DELETE FROM reconciliation_runs");
        jdbc.execute("DELETE FROM bank_statement_lines");
        jdbc.execute("DELETE FROM bank_statement_imports");
        jdbc.execute("DELETE FROM settlement_observations");
        jdbc.execute("DELETE FROM settlement_execution_attempts");
        jdbc.execute("DELETE FROM financial_outbox");
        jdbc.execute("DELETE FROM release_reservations");
        jdbc.execute("DELETE FROM release_instructions");

        windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.DAYS);
        windowEnd = windowStart.plus(3, ChronoUnit.DAYS);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepo.save(new User("Admin " + suffix, "admin_" + suffix + "@finx.invalid", "hash", Role.ADMIN, UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(admin.getId(), admin.getName(), admin.getEmail(), "hash", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());

        testOrg = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", admin.getId()));

        bankAccount = new OrganizationBankAccount(testOrg.getId(), "Beneficiary " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        bankAccount.setEncryptedAccountNumber("enc-" + suffix);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount = bankAccRepo.save(bankAccount);

        testDeal = new Deal();
        testDeal.setTitle("Deal " + suffix);
        testDeal.setDescription("Deal desc");
        testDeal.setBuyerId(admin.getId());
        testDeal.setSellerId(admin.getId());
        testDeal.setTotalAmount(new BigDecimal("10000.0000"));
        testDeal.setCurrency(CURRENCY);
        testDeal.setStatus(DealStatus.FUNDED);
        testDeal = dealRepo.save(testDeal);

        testMilestone = new Milestone();
        testMilestone.setDealId(testDeal.getId());
        testMilestone.setTitle("Milestone " + suffix);
        testMilestone.setDescription("Desc");
        testMilestone.setAmount(new BigDecimal("5000.0000"));
        testMilestone.setCurrency(CURRENCY);
        testMilestone.setSequence(1);
        testMilestone.setDueDate(Instant.now().plus(7, ChronoUnit.DAYS));
        testMilestone.setStatus(MilestoneStatus.APPROVED);
        testMilestone = milestoneRepo.save(testMilestone);
    }

    private EngineDecision createDecision() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        EnginePolicyVersion policy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", admin.getId(), Instant.now()
        ));

        EngineDecision decision = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(decision, testOrg.getId());
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
            var fEvAt = EngineDecision.class.getDeclaredField("evaluatedAt"); fEvAt.setAccessible(true); fEvAt.set(decision, Instant.now());
            var fVal = EngineDecision.class.getDeclaredField("validUntil"); fVal.setAccessible(true); fVal.set(decision, Instant.now().plusSeconds(3600));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return decisionRepo.save(decision);
    }

    private ReleaseInstruction createSettledRelease(BigDecimal amount) {
        EngineDecision d = createDecision();
        ReleaseInstruction ri = new ReleaseInstruction(
                UUID.randomUUID(),
                testOrg.getId(),
                testDeal.getId(),
                testMilestone.getId(),
                d.getId(),
                d.getDecisionFingerprint(),
                d.getPolicyVersionId(),
                d.getPolicyContentHash(),
                bankAccount.getId(),
                PROVIDER,
                PROVIDER_ACCOUNT_ID,
                amount,
                CURRENCY,
                UUID.randomUUID().toString(),
                "e".repeat(64),
                admin.getId()
        );
        ReflectionTestUtils.setField(ri, "status", ReleaseInstructionStatus.AUTHORIZED);
        ReflectionTestUtils.setField(ri, "authorizedAt", windowStart.plusSeconds(1800));
        ReflectionTestUtils.setField(ri, "authorizedBy", admin.getId());
        return instructionRepo.save(ri);
    }

    private SettlementObservation createFinalSuccessObservation(ReleaseInstruction ri, String utr, BigDecimal amount) {
        String opKey = "FINX-REL-" + ri.getId();
        String settleId = "SETTLE-" + UUID.randomUUID().toString().substring(0, 8);

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(),
                ri.getId(),
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                opKey,
                1,
                FinancialOutboxStatus.PENDING,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
        outbox = outboxRepo.save(outbox);

        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                ri.getId(),
                outbox.getId(),
                1,
                opKey,
                opKey,
                PROVIDER,
                PROVIDER_ACCOUNT_ID,
                bankAccount.getId(),
                amount,
                CURRENCY,
                "d".repeat(64),
                "worker-1",
                Instant.now()
        );
        attempt.markSending(Instant.now());
        attempt.markAcknowledged(settleId, "Dispatch OK", Instant.now());
        attempt = attemptRepo.save(attempt);

        SettlementObservation obs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(ri.getId())
                .executionAttemptId(attempt.getId())
                .provider(PROVIDER)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-" + UUID.randomUUID())
                .providerOperationKey(opKey)
                .providerSettlementId(settleId)
                .providerAccountId(PROVIDER_ACCOUNT_ID)
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber(utr)
                .evidenceHash("a".repeat(64))
                .observedAmount(amount)
                .observedCurrency(CURRENCY)
                .observedDirection("DEBIT")
                .observedAt(windowStart.plusSeconds(3600))
                .createdAt(windowStart.plusSeconds(3600))
                .build();
        obs = observationRepo.save(obs);

        ReflectionTestUtils.setField(ri, "status", ReleaseInstructionStatus.SETTLED);
        ReflectionTestUtils.setField(ri, "settledAt", windowStart.plusSeconds(3600));
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());
        ReflectionTestUtils.setField(ri, "finalizedBy", admin.getId());
        instructionRepo.save(ri);
        return obs;
    }

    private BankStatementImport createImportWithLines(BigDecimal opening, BigDecimal closing, List<BankStatementLine> lines) {
        UUID importId = UUID.randomUUID();
        for (BankStatementLine line : lines) {
            ReflectionTestUtils.setField(line, "importId", importId);
        }
        List<String> fps = lines.stream().map(BankStatementLine::getLineFingerprint).toList();
        String sourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                PROVIDER, PROVIDER_ACCOUNT_ID, CURRENCY, opening, closing, "IMP-REF-1", fps, windowStart, windowEnd
        );

        BankStatementImport imp = new BankStatementImport(
                PROVIDER, PROVIDER_ACCOUNT_ID, windowStart, windowEnd, sourceHash,
                opening, closing, CURRENCY, admin.getId()
        );
        imp.setId(importId);
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        imp.setProviderImportReference("IMP-REF-1");
        imp.setTotalLines(lines.size());
        imp = importRepo.save(imp);

        for (BankStatementLine line : lines) {
            lineRepo.save(line);
        }
        return imp;
    }

    private BankStatementLine buildLine(String ref, BigDecimal amount, FinancialDirection direction, BankStatementLineStatus status, String utr) {
        String lineId = "LINE-" + UUID.randomUUID().toString().substring(0, 8);
        Instant txnTime = windowStart.plusSeconds(3600);
        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint(
                PROVIDER, PROVIDER_ACCOUNT_ID, lineId, ref, utr, amount, CURRENCY,
                direction.name(), "SUCCESS", status.name(), txnTime, LocalDate.now()
        );
        return new BankStatementLine(
                UUID.randomUUID(), PROVIDER, PROVIDER_ACCOUNT_ID, lineId, ref, utr,
                amount, CURRENCY, direction, "SUCCESS", status, txnTime, LocalDate.now(), fp
        );
    }

    @Test
    @DisplayName("PART AR: Full real PostgreSQL release closed-loop match test -> MATCHED_CONFIRMED, V2, CONFIRMED")
    void testRealPostgresReleaseClosedLoopMatch() {
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-E8-001";

        ReleaseInstruction ri = createSettledRelease(amount);
        SettlementObservation obs = createFinalSuccessObservation(ri, utr, amount);

        BankStatementLine line = buildLine("TXN-REL-001", amount, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImportWithLines(new BigDecimal("100.0000"), new BigDecimal("30.0000"), List.of(line));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminPrincipal);

        assertThat(result.clean()).isTrue();
        assertThat(result.run().getMatcherVersion()).isEqualTo(DeterministicReconciliationMatcher.MATCHER_VERSION_V2);
        assertThat(result.run().getMatchedCount()).isEqualTo(1);
        assertThat(result.run().getMismatchCount()).isEqualTo(0);

        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(result.run().getId());
        assertThat(items).hasSize(1);
        ReconciliationItem item = items.get(0);
        assertThat(item.getInternalEffectType()).isEqualTo(ReconciliationEffectType.RELEASE_INSTRUCTION);
        assertThat(item.getInternalEffectId()).isEqualTo(ri.getId());
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MATCHED);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MATCHED_CONFIRMED);
        assertThat(item.getBankStatementLineId()).isEqualTo(line.getId());

        // Check ReleaseReconciliationEvidenceReader
        ReleaseReconciliationResult closedLoopResult = reader.getReleaseReconciliationEvidence(ri.getId());
        assertThat(closedLoopResult.getStatus()).isEqualTo(ReleaseReconciliationStatus.CONFIRMED);
        assertThat(closedLoopResult.getReleaseInstructionId()).isEqualTo(ri.getId());
        assertThat(closedLoopResult.getReconciliationRunId()).isEqualTo(result.run().getId());
        assertThat(closedLoopResult.getReconciliationItemId()).isEqualTo(item.getId());
        assertThat(closedLoopResult.getBankStatementLineId()).isEqualTo(line.getId());
        assertThat(closedLoopResult.getBankStatementImportId()).isEqualTo(imp.getId());
    }

    @Test
    @DisplayName("PART AT: Duplicate bank line UTR causes DUPLICATE / REFERENCE_AMBIGUOUS")
    void testDuplicateBankLineUtr() {
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-DUP-REAL";

        ReleaseInstruction ri = createSettledRelease(amount);
        createFinalSuccessObservation(ri, utr, amount);

        BankStatementLine line1 = buildLine("TXN-1", amount, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementLine line2 = buildLine("TXN-2", amount, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImportWithLines(new BigDecimal("140.0000"), new BigDecimal("0.0000"), List.of(line1, line2));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminPrincipal);

        assertThat(result.clean()).isFalse();
        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(result.run().getId());
        assertThat(items).anyMatch(i -> i.getStatus() == ReconciliationItemStatus.DUPLICATE);
    }

    @Test
    @DisplayName("PART AU: Bank debit missing produces MISSING_EXTERNAL")
    void testBankDebitMissing() {
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-MISSING-DEBIT";

        ReleaseInstruction ri = createSettledRelease(amount);
        createFinalSuccessObservation(ri, utr, amount);

        BankStatementImport imp = createImportWithLines(new BigDecimal("100.0000"), new BigDecimal("100.0000"), Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminPrincipal);

        assertThat(result.clean()).isFalse();
        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(result.run().getId());
        assertThat(items).hasSize(1);
        ReconciliationItem item = items.get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MISSING_EXTERNAL);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MISSING_EXTERNAL);
    }

    @Test
    @DisplayName("PART AV: Unclaimed debit produces UNCLAIMED_FINAL_BANK_LINE")
    void testUnclaimedDebit() {
        BankStatementLine line = buildLine("TXN-UNCLAIMED", new BigDecimal("50.0000"), FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-NO-INTERNAL");
        BankStatementImport imp = createImportWithLines(new BigDecimal("100.0000"), new BigDecimal("50.0000"), List.of(line));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminPrincipal);

        assertThat(result.clean()).isFalse();
        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(result.run().getId());
        assertThat(items).hasSize(1);
        ReconciliationItem item = items.get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MISSING_INTERNAL);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE);
    }

    @Test
    @DisplayName("PART AW: Bank line PENDING requires review, not MATCHED")
    void testBankLinePendingRequiresReview() {
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-PENDING";

        ReleaseInstruction ri = createSettledRelease(amount);
        createFinalSuccessObservation(ri, utr, amount);

        BankStatementLine line = buildLine("TXN-PENDING", amount, FinancialDirection.DEBIT, BankStatementLineStatus.PENDING, utr);
        BankStatementImport imp = createImportWithLines(new BigDecimal("100.0000"), new BigDecimal("100.0000"), List.of(line));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminPrincipal);

        assertThat(result.clean()).isFalse();
        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(result.run().getId());
        ReconciliationItem item = items.get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_PENDING);
    }

    @Test
    @DisplayName("PART AX: One-to-one constraint: two releases claiming same bank line become ambiguous")
    void testOneToOneMatchingConflict() {
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-SHARED-LINE";

        ReleaseInstruction ri1 = createSettledRelease(amount);
        createFinalSuccessObservation(ri1, utr, amount);

        ReleaseInstruction ri2 = createSettledRelease(amount);
        createFinalSuccessObservation(ri2, utr, amount);

        BankStatementLine line = buildLine("TXN-SHARED", amount, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImportWithLines(new BigDecimal("100.0000"), new BigDecimal("30.0000"), List.of(line));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminPrincipal);

        assertThat(result.clean()).isFalse();
        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(result.run().getId());
        List<ReconciliationItem> releaseItems = items.stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .toList();
        assertThat(releaseItems).hasSize(2);
        assertThat(releaseItems).allMatch(i -> i.getStatus() == ReconciliationItemStatus.DUPLICATE);
        assertThat(items).anyMatch(i -> i.getStatus() == ReconciliationItemStatus.MISSING_INTERNAL
                && i.getReasonCode() == ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE);
    }

    @Test
    @DisplayName("PART AY: Statement balance control fails when opening - debit != closing")
    void testStatementBalanceControlFailure() {
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-BAL-FAIL";

        ReleaseInstruction ri = createSettledRelease(amount);
        createFinalSuccessObservation(ri, utr, amount);

        // Opening 100 - debit 70 should be 30, but closing reports 50.0000
        BankStatementLine line = buildLine("TXN-1", amount, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImportWithLines(new BigDecimal("100.0000"), new BigDecimal("50.0000"), List.of(line));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminPrincipal);

        assertThat(result.clean()).isFalse();
        assertThat(result.run().getMismatchCount()).isGreaterThan(0);
        assertThat(result.items()).anyMatch(i -> i.getReasonCode() == ReconciliationReasonCode.STATEMENT_BALANCE_MISMATCH);
    }

    @Test
    @DisplayName("PART AZ: Zero financial side effects during reconciliation (releases, reservations, deals, milestones, escrows unchanged)")
    void testZeroFinancialSideEffects() {
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-ZERO-EFFECT";

        ReleaseInstruction ri = createSettledRelease(amount);
        SettlementObservation obs = createFinalSuccessObservation(ri, utr, amount);

        EscrowAccount escrow = new EscrowAccount(testDeal.getId(), new BigDecimal("10000.0000"), CURRENCY);
        escrow = escrowRepo.save(escrow);

        ReleaseReservation res = new ReleaseReservation(
                UUID.randomUUID(),
                ri.getId(),
                escrow.getId(),
                testMilestone.getId(),
                amount,
                CURRENCY
        );
        ReflectionTestUtils.setField(res, "status", ReleaseReservationStatus.CONSUMED);
        ReflectionTestUtils.setField(res, "consumedAt", Instant.now());
        res = reservationRepo.save(res);

        BankStatementLine line = buildLine("TXN-1", amount, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImportWithLines(new BigDecimal("100.0000"), new BigDecimal("30.0000"), List.of(line));

        // Capture states before reconciliation
        ReleaseInstruction riBefore = instructionRepo.findById(ri.getId()).orElseThrow();
        ReleaseReservation resBefore = reservationRepo.findById(res.getId()).orElseThrow();
        EscrowAccount escrowBefore = escrowRepo.findById(escrow.getId()).orElseThrow();
        Deal dealBefore = dealRepo.findById(testDeal.getId()).orElseThrow();
        Milestone milestoneBefore = milestoneRepo.findById(testMilestone.getId()).orElseThrow();
        SettlementObservation obsBefore = observationRepo.findById(obs.getId()).orElseThrow();
        long journalCountBefore = journalRepo.count();

        // Execute reconciliation
        matcher.reconcileImport(imp.getId(), adminPrincipal);

        // Verify states after reconciliation
        ReleaseInstruction riAfter = instructionRepo.findById(ri.getId()).orElseThrow();
        ReleaseReservation resAfter = reservationRepo.findById(res.getId()).orElseThrow();
        EscrowAccount escrowAfter = escrowRepo.findById(escrow.getId()).orElseThrow();
        Deal dealAfter = dealRepo.findById(testDeal.getId()).orElseThrow();
        Milestone milestoneAfter = milestoneRepo.findById(testMilestone.getId()).orElseThrow();
        SettlementObservation obsAfter = observationRepo.findById(obs.getId()).orElseThrow();
        long journalCountAfter = journalRepo.count();

        assertThat(riAfter.getStatus()).isEqualTo(riBefore.getStatus());
        assertThat(riAfter.getAmount()).isEqualByComparingTo(riBefore.getAmount());
        assertThat(resAfter.getStatus()).isEqualTo(resBefore.getStatus());
        assertThat(escrowAfter.getBalance()).isEqualByComparingTo(escrowBefore.getBalance());
        assertThat(dealAfter.getStatus()).isEqualTo(dealBefore.getStatus());
        assertThat(milestoneAfter.getStatus()).isEqualTo(milestoneBefore.getStatus());
        assertThat(obsAfter.getFinality()).isEqualTo(obsBefore.getFinality());
        assertThat(journalCountAfter).isEqualTo(journalCountBefore);
    }
}
