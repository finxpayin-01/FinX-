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
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.dto.DeterministicReconciliationResult;
import com.finx.reconciliation.dto.ReleaseReconciliationResult;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
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
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

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
        "razorpay.sandbox-mode=true"
})
@EnabledIf("isPostgresAvailable")
public class ReconciliationMismatchAlertTest {

    private static final String DB_NAME = "finx_release_e9_test";

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
    @Autowired private SettlementInstructionRepository legacySettlementRepo;

    @Autowired private UserRepository userRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;

    private User admin;
    private UserPrincipal adminPrincipal;
    private Organization testOrg;
    private OrganizationBankAccount bankAccount;
    private Deal testDeal;
    private Milestone testMilestone;

    private static final String PROVIDER = "MOCK";
    private static final String PROVIDER_ACCOUNT_ID = "MOCK_ACC_E9";
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
        } catch (SQLException ignored) {
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
        jdbc.execute("DELETE FROM settlement_instructions");

        windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.DAYS);
        windowEnd = windowStart.plus(3, ChronoUnit.DAYS);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepo.save(new User("Admin " + suffix, "admin_" + suffix + "@finx.invalid", "hash", Role.ADMIN, UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(admin.getId(), admin.getName(), admin.getEmail(), "hash", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());

        testOrg = orgRepo.save(new Organization("Org " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", admin.getId()));

        bankAccount = new OrganizationBankAccount(testOrg.getId(), "Beneficiary " + suffix, "****1234", "hash-" + suffix, "MOCK0001234", "Branch", "CURRENT", true);
        bankAccount.setEncryptedAccountNumber("enc-" + suffix);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount = bankAccRepo.save(bankAccount);

        testDeal = new Deal();
        testDeal.setTitle("Deal " + suffix);
        testDeal.setDescription("Deal desc");
        testDeal.setBuyerId(admin.getId());
        testDeal.setSellerId(admin.getId());
        testDeal.setTotalAmount(new BigDecimal("50000.0000"));
        testDeal.setCurrency(CURRENCY);
        testDeal.setStatus(DealStatus.FUNDED);
        testDeal = dealRepo.save(testDeal);

        testMilestone = new Milestone();
        testMilestone.setDealId(testDeal.getId());
        testMilestone.setTitle("Milestone " + suffix);
        testMilestone.setDescription("Desc");
        testMilestone.setAmount(new BigDecimal("50000.0000"));
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

    private ReleaseInstruction createSettledRelease(BigDecimal amount, String currency, String providerAccountId, String provider) {
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
                provider != null ? provider : PROVIDER,
                providerAccountId != null ? providerAccountId : PROVIDER_ACCOUNT_ID,
                amount,
                currency != null ? currency : CURRENCY,
                UUID.randomUUID().toString(),
                "e".repeat(64),
                admin.getId()
        );
        ri.markAuthorized(admin.getId(), windowStart.plusSeconds(1800));
        ri.markReserved();
        ri.markQueued();
        ri.markDispatching();
        ri.markPendingSettlement();
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
                ri.getProvider(),
                ri.getProviderAccountId(),
                bankAccount.getId(),
                amount,
                ri.getCurrency(),
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
                .provider(ri.getProvider())
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-" + UUID.randomUUID())
                .providerOperationKey(opKey)
                .providerSettlementId(settleId)
                .providerAccountId(ri.getProviderAccountId())
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber(utr)
                .evidenceHash("a".repeat(64))
                .observedAmount(amount)
                .observedCurrency(ri.getCurrency())
                .observedDirection("DEBIT")
                .observedAt(windowStart.plusSeconds(3600))
                .createdAt(windowStart.plusSeconds(3600))
                .build();
        obs = observationRepo.save(obs);

        ri.markSettledFromObservation(obs.getId(), admin.getId(), windowStart.plusSeconds(3600));
        instructionRepo.save(ri);
        return obs;
    }

    private BankStatementImport createImportWithLines(BigDecimal opening, BigDecimal closing, List<BankStatementLine> lines) {
        return createImportWithLines(opening, closing, lines, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID);
    }

    private BankStatementImport createImportWithLines(BigDecimal opening, BigDecimal closing, List<BankStatementLine> lines, String currency) {
        return createImportWithLines(opening, closing, lines, currency, PROVIDER, PROVIDER_ACCOUNT_ID);
    }

    private BankStatementImport createImportWithLines(BigDecimal opening, BigDecimal closing, List<BankStatementLine> lines, String currency, String provider, String providerAccountId) {
        UUID importId = UUID.randomUUID();
        for (BankStatementLine line : lines) {
            ReflectionTestUtils.setField(line, "importId", importId);
        }
        List<String> fps = lines.stream().map(BankStatementLine::getLineFingerprint).toList();
        String curr = currency != null ? currency : CURRENCY;
        String prov = provider != null ? provider : PROVIDER;
        String acc = providerAccountId != null ? providerAccountId : PROVIDER_ACCOUNT_ID;
        String sourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                prov, acc, curr, opening, closing, "IMP-REF-1", fps, windowStart, windowEnd
        );

        BankStatementImport imp = new BankStatementImport(
                prov, acc, windowStart, windowEnd, sourceHash,
                opening, closing, curr, admin.getId()
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

    private BankStatementLine buildLine(String ref, BigDecimal amount, String curr, String prov, String acc, FinancialDirection direction, BankStatementLineStatus status, String utr) {
        String lineId = "LINE-" + UUID.randomUUID().toString().substring(0, 8);
        Instant txnTime = windowStart.plusSeconds(3600);
        String currencyToUse = curr != null ? curr : CURRENCY;
        String providerToUse = prov != null ? prov : PROVIDER;
        String accountToUse = acc != null ? acc : PROVIDER_ACCOUNT_ID;
        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint(
                providerToUse, accountToUse, lineId, ref, utr, amount, currencyToUse,
                direction.name(), "SUCCESS", status.name(), txnTime, LocalDate.now()
        );
        return new BankStatementLine(
                UUID.randomUUID(), providerToUse, accountToUse, lineId, ref, utr,
                amount, currencyToUse, direction, "SUCCESS", status, txnTime, LocalDate.now(), fp
        );
    }

    @Test
    @DisplayName("PART AG: Adversarial reconciliation mismatches (amount, currency, direction, account, provider, UTR, reversal) -> never CONFIRMED")
    void partAG_adversarialReconciliationMismatches() {
        BigDecimal correctAmount = new BigDecimal("50000.0000");

        // 1. Wrong Amount Mismatch
        ReleaseInstruction riAmount = createSettledRelease(correctAmount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        SettlementObservation obsAmount = createFinalSuccessObservation(riAmount, "UTR-AMT-001", correctAmount);
        BankStatementLine lineAmount = buildLine("FINX-REL-" + riAmount.getId(), new BigDecimal("49999.0000"), CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-AMT-001");
        BankStatementImport impAmount = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("50001.0000"), List.of(lineAmount));

        matcher.reconcileImport(impAmount.getId(), adminPrincipal);
        ReleaseReconciliationResult resAmount = reader.getReleaseReconciliationEvidence(riAmount.getId());
        assertThat(resAmount.getStatus()).isIn(ReleaseReconciliationStatus.BLOCKED, ReleaseReconciliationStatus.REVIEW);
        assertThat(resAmount.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);

        // 2. Wrong Currency Mismatch
        ReleaseInstruction riCurr = createSettledRelease(correctAmount, "INR", PROVIDER_ACCOUNT_ID, PROVIDER);
        SettlementObservation obsCurr = createFinalSuccessObservation(riCurr, "UTR-CURR-001", correctAmount);
        BankStatementLine lineCurr = buildLine("FINX-REL-" + riCurr.getId(), correctAmount, "USD", PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-CURR-001");
        BankStatementImport impCurr = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("50000.0000"), List.of(lineCurr), "USD");

        matcher.reconcileImport(impCurr.getId(), adminPrincipal);
        ReleaseReconciliationResult resCurr = reader.getReleaseReconciliationEvidence(riCurr.getId());
        assertThat(resCurr.getStatus()).isIn(ReleaseReconciliationStatus.BLOCKED, ReleaseReconciliationStatus.REVIEW);
        assertThat(resCurr.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);

        // 3. Wrong Direction Mismatch (CREDIT instead of DEBIT)
        ReleaseInstruction riDir = createSettledRelease(correctAmount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        SettlementObservation obsDir = createFinalSuccessObservation(riDir, "UTR-DIR-001", correctAmount);
        BankStatementLine lineDir = buildLine("FINX-REL-" + riDir.getId(), correctAmount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-DIR-001");
        BankStatementImport impDir = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("150000.0000"), List.of(lineDir));

        matcher.reconcileImport(impDir.getId(), adminPrincipal);
        ReleaseReconciliationResult resDir = reader.getReleaseReconciliationEvidence(riDir.getId());
        assertThat(resDir.getStatus()).isIn(ReleaseReconciliationStatus.BLOCKED, ReleaseReconciliationStatus.REVIEW);
        assertThat(resDir.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);

        // 4. Wrong Provider Account
        ReleaseInstruction riAcc = createSettledRelease(correctAmount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        SettlementObservation obsAcc = createFinalSuccessObservation(riAcc, "UTR-ACC-001", correctAmount);
        BankStatementLine lineAcc = buildLine("FINX-REL-" + riAcc.getId(), correctAmount, CURRENCY, PROVIDER, "WRONG_ACCOUNT_XYZ", FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-ACC-001");
        BankStatementImport impAcc = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("50000.0000"), List.of(lineAcc), CURRENCY, PROVIDER, "WRONG_ACCOUNT_XYZ");

        matcher.reconcileImport(impAcc.getId(), adminPrincipal);
        ReleaseReconciliationResult resAcc = reader.getReleaseReconciliationEvidence(riAcc.getId());
        assertThat(resAcc.getStatus()).isIn(ReleaseReconciliationStatus.BLOCKED, ReleaseReconciliationStatus.REVIEW, ReleaseReconciliationStatus.NO_EVIDENCE);
        assertThat(resAcc.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);

        // 5. Wrong Provider Mismatch
        ReleaseInstruction riProv = createSettledRelease(correctAmount, CURRENCY, PROVIDER_ACCOUNT_ID, "MOCK");
        SettlementObservation obsProv = createFinalSuccessObservation(riProv, "UTR-PROV-001", correctAmount);
        BankStatementLine lineProv = buildLine("FINX-REL-" + riProv.getId(), correctAmount, CURRENCY, "OTHER_PROVIDER", PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-PROV-001");
        BankStatementImport impProv = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("50000.0000"), List.of(lineProv), CURRENCY, "OTHER_PROVIDER", PROVIDER_ACCOUNT_ID);

        matcher.reconcileImport(impProv.getId(), adminPrincipal);
        ReleaseReconciliationResult resProv = reader.getReleaseReconciliationEvidence(riProv.getId());
        assertThat(resProv.getStatus()).isIn(ReleaseReconciliationStatus.BLOCKED, ReleaseReconciliationStatus.REVIEW, ReleaseReconciliationStatus.NO_EVIDENCE);
        assertThat(resProv.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);

        // 6. UTR Mismatch
        ReleaseInstruction riUtr = createSettledRelease(correctAmount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        SettlementObservation obsUtr = createFinalSuccessObservation(riUtr, "UTR-EXPECTED-001", correctAmount);
        BankStatementLine lineUtr = buildLine("FINX-REL-" + riUtr.getId(), correctAmount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-TAMPERED-999");
        BankStatementImport impUtr = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("50000.0000"), List.of(lineUtr));

        matcher.reconcileImport(impUtr.getId(), adminPrincipal);
        ReleaseReconciliationResult resUtr = reader.getReleaseReconciliationEvidence(riUtr.getId());
        assertThat(resUtr.getStatus()).isIn(ReleaseReconciliationStatus.BLOCKED, ReleaseReconciliationStatus.REVIEW);
        assertThat(resUtr.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);

        // 7. Statement Reversal
        ReleaseInstruction riRev = createSettledRelease(correctAmount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        SettlementObservation obsRev = createFinalSuccessObservation(riRev, "UTR-REV-001", correctAmount);
        BankStatementLine lineRev = buildLine("FINX-REL-" + riRev.getId(), correctAmount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.REVERSED, "UTR-REV-001");
        BankStatementImport impRev = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("50000.0000"), List.of(lineRev));

        matcher.reconcileImport(impRev.getId(), adminPrincipal);
        ReleaseReconciliationResult resRev = reader.getReleaseReconciliationEvidence(riRev.getId());
        assertThat(resRev.getStatus()).isEqualTo(ReleaseReconciliationStatus.REVERSAL_RECOVERY_REQUIRED);
    }

    @Test
    @DisplayName("PART AH: Older V2 clean run vs newer V2 dirty run -> Reader MUST use newer run, never fallback to old confirmed")
    void partAH_latestRunProtection() {
        BigDecimal amount = new BigDecimal("30000.0000");
        ReleaseInstruction ri = createSettledRelease(amount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        createFinalSuccessObservation(ri, "UTR-CLEAN-001", amount);

        // Run 1: Clean match
        BankStatementLine cleanLine = buildLine("FINX-REL-" + ri.getId(), amount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-CLEAN-001");
        BankStatementImport cleanImport = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("70000.0000"), List.of(cleanLine));

        matcher.reconcileImport(cleanImport.getId(), adminPrincipal);
        ReleaseReconciliationResult cleanResult = reader.getReleaseReconciliationEvidence(ri.getId());
        assertThat(cleanResult.getStatus()).isEqualTo(ReleaseReconciliationStatus.CONFIRMED);

        // Run 2: Newer run on new import where statement indicates amount mismatch
        BankStatementLine dirtyLine = buildLine("FINX-REL-" + ri.getId(), new BigDecimal("25000.0000"), CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-CLEAN-001");
        BankStatementImport dirtyImport = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("75000.0000"), List.of(dirtyLine));

        matcher.reconcileImport(dirtyImport.getId(), adminPrincipal);

        // Verify that reader strictly uses latest run and reports BLOCKED/REVIEW instead of old clean CONFIRMED
        ReleaseReconciliationResult latestResult = reader.getReleaseReconciliationEvidence(ri.getId());
        assertThat(latestResult.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);
        assertThat(latestResult.getStatus()).isIn(ReleaseReconciliationStatus.BLOCKED, ReleaseReconciliationStatus.REVIEW);
    }

    @Test
    @DisplayName("PART AI: Matcher version attack -> V1 and V2 valid for funding, V999 rejected; V1 NOT authoritative for release closed-loop")
    void partAI_matcherVersionAttack() {
        assertThat(DeterministicReconciliationMatcher.isAuthoritativeFundingMatcherVersion("FINX_RECONCILIATION_MATCHER_V1")).isTrue();
        assertThat(DeterministicReconciliationMatcher.isAuthoritativeFundingMatcherVersion("FINX_RECONCILIATION_MATCHER_V2")).isTrue();
        assertThat(DeterministicReconciliationMatcher.isAuthoritativeFundingMatcherVersion("FINX_RECONCILIATION_MATCHER_V999")).isFalse();

        // Release closed loop: a run marked V1 is not authoritative
        BigDecimal amount = new BigDecimal("15000.0000");
        ReleaseInstruction ri = createSettledRelease(amount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        createFinalSuccessObservation(ri, "UTR-V1-001", amount);

        BankStatementLine line = buildLine("FINX-REL-" + ri.getId(), amount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-V1-001");
        BankStatementImport imp = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("85000.0000"), List.of(line));

        DeterministicReconciliationResult matchResult = matcher.reconcileImport(imp.getId(), adminPrincipal);

        // Manually downgrade run to V1 to simulate legacy / attacker version
        ReconciliationRun run = runRepo.findById(matchResult.run().getId()).orElseThrow();
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION_V1);
        runRepo.save(run);

        ReleaseReconciliationResult v1Result = reader.getReleaseReconciliationEvidence(ri.getId());
        // For release closed loop, V1 is NOT authoritative -> NO_EVIDENCE
        assertThat(v1Result.getStatus()).isEqualTo(ReleaseReconciliationStatus.NO_EVIDENCE);
    }

    @Test
    @DisplayName("PART AJ: Legacy SettlementInstruction collision with modern ReleaseInstruction -> exactly 1-to-1 match, 0 double matches")
    void partAJ_legacySettlementCollision() {
        BigDecimal amount = new BigDecimal("20000.0000");
        ReleaseInstruction ri = createSettledRelease(amount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        createFinalSuccessObservation(ri, "UTR-COLLIDE-001", amount);

        String sharedRef = "FINX-REL-" + ri.getId();

        // Create legacy settlement instruction attempting to claim the same reference
        SettlementInstruction legacy = new SettlementInstruction(
                sharedRef,
                testDeal.getId(),
                testMilestone.getId(),
                testOrg.getId(),
                bankAccount.getId(),
                amount,
                CURRENCY,
                PROVIDER,
                "idem-legacy-" + UUID.randomUUID(),
                admin.getId()
        );
        legacy.setStatus(SettlementStatus.SETTLED);
        legacySettlementRepo.save(legacy);

        // Bank statement line has this reference
        BankStatementLine line = buildLine(sharedRef, amount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-COLLIDE-001");
        BankStatementImport imp = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("80000.0000"), List.of(line));

        DeterministicReconciliationResult matchResult = matcher.reconcileImport(imp.getId(), adminPrincipal);

        // Total matched items must not exceed number of statement lines (1 line -> at most 1 MATCHED item)
        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(matchResult.run().getId());
        long matchedCount = items.stream().filter(it -> it.getStatus() == ReconciliationItemStatus.MATCHED).count();
        assertThat(matchedCount).isLessThanOrEqualTo(1);

        // Line is not double-claimed
        long distinctLines = items.stream().map(ReconciliationItem::getBankStatementLineId).distinct().count();
        assertThat(distinctLines).isEqualTo(items.size());
    }

    @Test
    @DisplayName("PART AK: Statement tamper test -> modified persisted source hash/fingerprint triggers REVIEW")
    void partAK_statementTamperTriggersReview() {
        BigDecimal amount = new BigDecimal("40000.0000");
        ReleaseInstruction ri = createSettledRelease(amount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        createFinalSuccessObservation(ri, "UTR-TAMPER-001", amount);

        BankStatementLine line = buildLine("FINX-REL-" + ri.getId(), amount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-TAMPER-001");
        BankStatementImport imp = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("60000.0000"), List.of(line));

        matcher.reconcileImport(imp.getId(), adminPrincipal);

        // Tamper with import source hash post-run (exactly 64 chars)
        imp.setSourceHash("0".repeat(64));
        importRepo.save(imp);

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(ri.getId());
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.REVIEW);
        assertThat(result.getReason()).contains("hash mismatch");
    }

    @Test
    @DisplayName("PART AL: Reconciliation idempotency -> repeated runs produce identical deterministic outcome, 0 mutation of old items")
    void partAL_reconciliationIdempotency() {
        BigDecimal amount = new BigDecimal("10000.0000");
        ReleaseInstruction ri = createSettledRelease(amount, CURRENCY, PROVIDER_ACCOUNT_ID, PROVIDER);
        createFinalSuccessObservation(ri, "UTR-IDEM-001", amount);

        BankStatementLine line = buildLine("FINX-REL-" + ri.getId(), amount, CURRENCY, PROVIDER, PROVIDER_ACCOUNT_ID, FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, "UTR-IDEM-001");
        BankStatementImport imp = createImportWithLines(new BigDecimal("100000.0000"), new BigDecimal("90000.0000"), List.of(line));

        // Run 1
        DeterministicReconciliationResult run1 = matcher.reconcileImport(imp.getId(), adminPrincipal);
        List<ReconciliationItem> items1 = itemRepo.findByReconciliationRunId(run1.run().getId());

        // Run 2
        DeterministicReconciliationResult run2 = matcher.reconcileImport(imp.getId(), adminPrincipal);
        List<ReconciliationItem> items2 = itemRepo.findByReconciliationRunId(run2.run().getId());

        assertThat(run1.run().getMatchedCount()).isEqualTo(run2.run().getMatchedCount());
        assertThat(run1.run().getMismatchCount()).isEqualTo(run2.run().getMismatchCount());
        assertThat(items1).hasSameSizeAs(items2);

        // Verify items in run 1 remain completely unchanged
        List<ReconciliationItem> items1ReRead = itemRepo.findByReconciliationRunId(run1.run().getId());
        assertThat(items1ReRead).hasSameSizeAs(items1);
        for (int i = 0; i < items1.size(); i++) {
            assertThat(items1ReRead.get(i).getStatus()).isEqualTo(items1.get(i).getStatus());
            assertThat(items1ReRead.get(i).getInternalEffectId()).isEqualTo(items1.get(i).getInternalEffectId());
        }
    }
}
