package com.finx.release.cert;

import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.approval.repository.MilestoneApprovalBindingRepository;
import com.finx.approval.service.MilestoneApprovalFingerprintService;
import com.finx.banking.dto.BankStatementDirection;
import com.finx.banking.dto.BankStatementEntry;
import com.finx.banking.dto.BankStatementFinality;
import com.finx.banking.dto.BankStatementQuery;
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
import com.finx.reconciliation.dto.DeterministicReconciliationResult;
import com.finx.reconciliation.dto.ReleaseReconciliationResult;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.model.ReleaseReconciliationStatus;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.BankStatementIngestionService;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.reconciliation.service.ReleaseReconciliationEvidenceReader;
import com.finx.release.certification.ProductionReadinessCertificationService;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.gate.ProductionSettlementDispatchGate;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_release_e12_test}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:finx_user}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:finx_password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "rate-limit.enabled=false",
        "finx.banking.provider=mock",
        "finx.release.sandbox-finalization.enabled=true",
        "finx.release.sandbox-dispatch.enabled=true",
        "finx.release.production-execution.enabled=false",
        "razorpay.sandbox-mode=true"
})
@EnabledIf("isPostgresAvailable")
public class FullPlatformPostgresEndToEndCertificationTest {

    private static final String DB_NAME = "finx_release_e12_test";

    static {
        ensureDatabaseExists(DB_NAME);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate txTemplate;

    @Autowired private TrustedSettlementFinalizer finalizer;
    @Autowired private BankStatementIngestionService ingestionService;
    @Autowired private DeterministicReconciliationMatcher matcher;
    @Autowired private ReleaseReconciliationEvidenceReader reader;
    @Autowired private ProductionSettlementDispatchGate dispatchGate;
    @Autowired private ProductionReadinessCertificationService certificationService;
    @Autowired private MilestoneApprovalFingerprintService fingerprintService;

    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private SettlementExecutionAttemptRepository attemptRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private BankStatementImportRepository importRepo;
    @Autowired private BankStatementLineRepository lineRepo;
    @Autowired private ReconciliationRunRepository runRepo;
    @Autowired private ReconciliationItemRepository itemRepo;
    @Autowired private FinancialOutboxRepository outboxRepo;
    @Autowired private MilestoneApprovalBindingRepository bindingRepo;

    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private EscrowAccountRepository escrowAccountRepo;
    @Autowired private EscrowLedgerRepository escrowLedgerRepo;
    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private OrganizationBankAccountRepository bankAccRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EngineDecisionRepository decisionRepo;
    @Autowired private EnginePolicyVersionRepository policyRepo;
    @Autowired private LedgerService ledgerService;

    @SpyBean private BankingProvider bankingProvider;

    private User adminUser;
    private UserPrincipal adminPrincipal;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;
    private Organization sellerOrg;
    private OrganizationBankAccount bankAccount;
    private EngineDecision decision;
    private EnginePolicyVersion policy;
    private MilestoneApprovalBinding approvalBinding;

    private static final String PROVIDER = "MOCK";
    private static final String PROVIDER_ACCOUNT_ID = "MOCK_ESCROW_ACCOUNT";
    private static final String CURRENCY = "INR";
    private static final BigDecimal RELEASE_AMOUNT = new BigDecimal("7500.0000");
    private static final BigDecimal TOTAL_FUNDED = new BigDecimal("100000.0000");

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
        jdbc.execute("DELETE FROM milestone_approval_bindings");
        jdbc.execute("DELETE FROM deliverables");
        jdbc.execute("DELETE FROM engine_decisions");
        jdbc.execute("DELETE FROM engine_policy_versions");
        jdbc.execute("DELETE FROM escrow_ledger");
        jdbc.execute("DELETE FROM journal_lines");
        jdbc.execute("DELETE FROM journal_entries");
        jdbc.execute("DELETE FROM payment_transactions");
        jdbc.execute("DELETE FROM payment_instructions");
        jdbc.execute("DELETE FROM milestones");
        jdbc.execute("DELETE FROM escrow_accounts");
        jdbc.execute("DELETE FROM deals");

        if (bankingProvider instanceof MockBankingProvider mbp) {
            mbp.clearDurableStatementEntries();
        }

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminUser = userRepo.save(new User("Admin E12 " + suffix, "admin_e12_" + suffix + "@finx.invalid", "hash", Role.ADMIN, UserStatus.ACTIVE));
        adminPrincipal = UserPrincipal.create(adminUser);

        sellerOrg = orgRepo.save(new Organization("Seller " + suffix, "Seller Legal " + suffix, EntityType.PRIVATE_LIMITED,
                "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "Addr", adminUser.getId()));

        bankAccount = new OrganizationBankAccount(sellerOrg.getId(), "Beneficiary " + suffix, "****1234", "hash-" + suffix, "HDFC0001234", "Branch", "CURRENT", true);
        bankAccount.setEncryptedAccountNumber("enc-" + suffix);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount = bankAccRepo.save(bankAccount);

        // 1. Deal
        deal = new Deal();
        deal.setTitle("E12 Certification Deal " + suffix);
        deal.setDescription("Deal desc");
        deal.setBuyerId(adminUser.getId());
        deal.setSellerId(adminUser.getId());
        deal.setBuyerOrganizationId(sellerOrg.getId());
        deal.setSellerOrganizationId(sellerOrg.getId());
        deal.setTotalAmount(TOTAL_FUNDED);
        deal.setCurrency(CURRENCY);
        deal.setStatus(DealStatus.FUNDED);
        deal = dealRepo.save(deal);

        // 2. Milestone
        milestone = new Milestone();
        milestone.setDealId(deal.getId());
        milestone.setTitle("Milestone E12 " + suffix);
        milestone.setDescription("Desc");
        milestone.setAmount(RELEASE_AMOUNT);
        milestone.setCurrency(CURRENCY);
        milestone.setSequence(1);
        milestone.setDueDate(Instant.now().plus(7, ChronoUnit.DAYS));
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone = milestoneRepo.save(milestone);

        // 3. Approval Binding
        Instant now = Instant.now();
        String mFp = fingerprintService.computeMilestoneFingerprint(milestone);
        String delFp = fingerprintService.computeDeliverablesFingerprint(Collections.emptyList());
        String approvalFingerprint = fingerprintService.computeApprovalFingerprint(
                sellerOrg.getId(),
                deal.getId(),
                milestone.getId(),
                adminUser.getId(),
                MilestoneApprovalAuthority.BUYER,
                null,
                mFp,
                delFp,
                now
        );
        approvalBinding = new MilestoneApprovalBinding(
                UUID.randomUUID(),
                sellerOrg.getId(),
                deal.getId(),
                milestone.getId(),
                adminUser.getId(),
                MilestoneApprovalAuthority.BUYER,
                mFp,
                delFp,
                approvalFingerprint,
                null,
                now,
                now
        );
        approvalBinding = bindingRepo.save(approvalBinding);

        // 4. Escrow Account & Ledger
        escrowAccount = new EscrowAccount(deal.getId(), TOTAL_FUNDED, CURRENCY);
        escrowAccount.setTotalFunded(TOTAL_FUNDED);
        escrowAccount.setTotalReleased(BigDecimal.ZERO);
        escrowAccount = escrowAccountRepo.save(escrowAccount);

        ledgerService.initSystemAccounts();
        ledgerService.postJournalEntry(new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                deal.getId(),
                deal.getId(),
                null,
                CURRENCY,
                "Escrow initial funding",
                adminUser.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, TOTAL_FUNDED, "Fund escrow pool"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, TOTAL_FUNDED, "Fund escrow liability")
                )
        ));

        // 5. Engine Policy & Decision
        policy = policyRepo.save(new EnginePolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RELEASE,
                "KEY-" + suffix, "1", "b".repeat(64), "1.0", "Desc", "{}", adminUser.getId(), Instant.now()
        ));

        EngineDecision d = new EngineDecision();
        try {
            var fOrg = EngineDecision.class.getDeclaredField("organizationId"); fOrg.setAccessible(true); fOrg.set(d, sellerOrg.getId());
            var fEng = EngineDecision.class.getDeclaredField("engineType"); fEng.setAccessible(true); fEng.set(d, EngineType.RELEASE);
            var fSubT = EngineDecision.class.getDeclaredField("subjectType"); fSubT.setAccessible(true); fSubT.set(d, "MILESTONE");
            var fSubI = EngineDecision.class.getDeclaredField("subjectId"); fSubI.setAccessible(true); fSubI.set(d, milestone.getId());
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
            var fEvAt = EngineDecision.class.getDeclaredField("evaluatedAt"); fEvAt.setAccessible(true); fEvAt.set(d, Instant.now());
            var fVal = EngineDecision.class.getDeclaredField("validUntil"); fVal.setAccessible(true); fVal.set(d, Instant.now().plusSeconds(3600));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        decision = decisionRepo.save(d);
    }

    @Test
    @DisplayName("E12 Full Platform Certification: End-to-end release lifecycle on real PostgreSQL with zero double-spend")
    void fullPlatformEndToEndCertification() {
        // Step 1: Create ReleaseInstruction & Reservation
        String reqHash = "a".repeat(64);
        String idempKey = "idemp-" + UUID.randomUUID();
        ReleaseInstruction instruction = new ReleaseInstruction(
                UUID.randomUUID(),
                sellerOrg.getId(),
                deal.getId(),
                milestone.getId(),
                decision.getId(),
                decision.getDecisionFingerprint(),
                policy.getId(),
                policy.getContentHash(),
                bankAccount.getId(),
                PROVIDER,
                PROVIDER_ACCOUNT_ID,
                RELEASE_AMOUNT,
                CURRENCY,
                idempKey,
                reqHash,
                adminUser.getId()
        );
        instruction.markAuthorized(adminUser.getId(), Instant.now());
        instruction.markReserved();
        instruction.markQueued();
        instruction = instructionRepo.save(instruction);

        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(),
                instruction.getId(),
                escrowAccount.getId(),
                milestone.getId(),
                RELEASE_AMOUNT,
                CURRENCY
        );
        reservation = reservationRepo.save(reservation);

        // Step 2: Enqueue Transactional Outbox
        String opKey = "FINX-REL-" + instruction.getId();
        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                UUID.randomUUID(),
                instruction.getId(),
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

        // Step 3: Worker Lease Claim & Dispatch Attempt
        outbox.markLeased("worker-e12-cert", Instant.now());
        outbox = outboxRepo.save(outbox);

        String providerSettlementId = "MOCK-STL-" + UUID.randomUUID().toString().substring(0, 8);
        String utr = "UTR-E12-CERT-" + UUID.randomUUID().toString().substring(0, 10);

        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instruction.getId(),
                outbox.getId(),
                1,
                opKey,
                opKey,
                PROVIDER,
                PROVIDER_ACCOUNT_ID,
                bankAccount.getId(),
                RELEASE_AMOUNT,
                CURRENCY,
                reqHash,
                "worker-e12-cert",
                Instant.now()
        );
        attempt.markSending(Instant.now());
        attempt.markAcknowledged(providerSettlementId, "Sandbox Mock Dispatch OK", Instant.now());
        attempt = attemptRepo.save(attempt);

        outbox.markProcessed(Instant.now(), null);
        outbox = outboxRepo.save(outbox);

        instruction.markDispatching();
        instruction.markPendingSettlement();
        instruction = instructionRepo.save(instruction);

        // Step 4: Trusted Settlement Observation
        SettlementObservation observation = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(instruction.getId())
                .executionAttemptId(attempt.getId())
                .provider(PROVIDER)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-E12-" + UUID.randomUUID())
                .providerOperationKey(opKey)
                .providerSettlementId(providerSettlementId)
                .providerAccountId(PROVIDER_ACCOUNT_ID)
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber(utr)
                .evidenceHash("b".repeat(64))
                .observedAmount(RELEASE_AMOUNT)
                .observedCurrency(CURRENCY)
                .observedDirection("DEBIT")
                .providerObservedAt(Instant.now().minus(15, ChronoUnit.MINUTES))
                .observedAt(Instant.now().minus(15, ChronoUnit.MINUTES))
                .createdAt(Instant.now().minus(15, ChronoUnit.MINUTES))
                .build();
        observation = observationRepo.save(observation);

        // Step 5: Atomic Settlement Finalization
        SettlementFinalizationResult finalizationResult = finalizer.finalizeRelease(instruction.getId(), adminPrincipal);
        assertThat(finalizationResult.releaseInstructionId()).isEqualTo(instruction.getId());

        // Verify Step 5 invariants
        ReleaseInstruction settledRi = instructionRepo.findById(instruction.getId()).orElseThrow();
        assertThat(settledRi.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        assertThat(settledRi.getFinalSettlementObservationId()).isEqualTo(observation.getId());

        ReleaseReservation consumedRes = reservationRepo.findById(reservation.getId()).orElseThrow();
        assertThat(consumedRes.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);

        Milestone completedM = milestoneRepo.findById(milestone.getId()).orElseThrow();
        assertThat(completedM.getStatus()).isEqualTo(MilestoneStatus.COMPLETED);

        EscrowAccount updatedEscrow = escrowAccountRepo.findById(escrowAccount.getId()).orElseThrow();
        assertThat(updatedEscrow.getBalance()).isEqualByComparingTo(TOTAL_FUNDED.subtract(RELEASE_AMOUNT));

        // Step 6: Bank Statement Ingestion
        MockBankingProvider mbp = (MockBankingProvider) bankingProvider;
        Instant txnTime = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(10, ChronoUnit.MINUTES);
        mbp.setDurableBalances(new BigDecimal("50000000.0000"), new BigDecimal("50000000.0000").subtract(RELEASE_AMOUNT));
        mbp.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-E12-" + UUID.randomUUID().toString().substring(0, 8))
                .transactionReference(opKey)
                .utrNumber(utr)
                .direction(BankStatementDirection.DEBIT)
                .amount(RELEASE_AMOUNT)
                .currency(CURRENCY)
                .transactionTime(txnTime)
                .valueDate(LocalDate.now())
                .providerStatus("SUCCESS")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .build());

        Instant windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.DAYS);
        Instant windowEnd = windowStart.plus(3, ChronoUnit.DAYS);

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(PROVIDER_ACCOUNT_ID)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .pageSize(50)
                .build();

        BankStatementImport statementImport = ingestionService.ingestStatement(query, adminPrincipal);
        assertThat(statementImport).isNotNull();
        assertThat(lineRepo.findByImportIdOrderByTransactionTimeAscIdAsc(statementImport.getId())).hasSize(1);

        // Step 7: Deterministic Reconciliation Matcher V2
        DeterministicReconciliationResult reconResult = matcher.reconcileImport(statementImport.getId(), adminPrincipal);
        assertThat(reconResult.clean()).isTrue();
        assertThat(reconResult.run().getMatchedCount()).isEqualTo(1);
        assertThat(reconResult.run().getMismatchCount()).isEqualTo(0);

        List<ReconciliationItem> items = itemRepo.findByReconciliationRunId(reconResult.run().getId());
        assertThat(items).hasSize(1);
        ReconciliationItem item = items.get(0);
        assertThat(item.getInternalEffectType()).isEqualTo(ReconciliationEffectType.RELEASE_INSTRUCTION);
        assertThat(item.getInternalEffectId()).isEqualTo(instruction.getId());
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MATCHED);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MATCHED_CONFIRMED);

        // Step 8: Closed-Loop Confirmation Verification
        ReleaseReconciliationResult closedLoop = reader.getReleaseReconciliationEvidence(instruction.getId());
        assertThat(closedLoop.getStatus()).isEqualTo(ReleaseReconciliationStatus.CONFIRMED);
        assertThat(closedLoop.getReleaseInstructionId()).isEqualTo(instruction.getId());
        assertThat(closedLoop.getReconciliationRunId()).isEqualTo(reconResult.run().getId());
        assertThat(closedLoop.getReconciliationItemId()).isEqualTo(item.getId());

        // EXACT SINGLETON INVARIANT CHECKS (Zero double-spend / Zero duplication):
        // 1. Exactly one provider debit line ingested
        assertThat(lineRepo.findAll()).hasSize(1);
        // 2. Exactly one settlement journal entry posted for this deal
        List<JournalEntry> settlements = journalRepo.findByDealIdOrderByPostedAtDesc(deal.getId()).stream()
                .filter(j -> j.getTransactionType() == LedgerTransactionType.MILESTONE_SETTLEMENT)
                .toList();
        assertThat(settlements).hasSize(1);
        // 3. Exactly one escrow release entry in escrow ledger
        List<EscrowLedger> escrowReleases = escrowLedgerRepo.findAll().stream()
                .filter(l -> l.getTransactionType() == TransactionType.RELEASE)
                .toList();
        assertThat(escrowReleases).hasSize(1);
        // 4. Exactly one reservation consumed
        List<ReleaseReservation> consumedReservations = reservationRepo.findAll().stream()
                .filter(r -> r.getStatus() == ReleaseReservationStatus.CONSUMED)
                .toList();
        assertThat(consumedReservations).hasSize(1);

        // PRODUCTION GATE CHECK:
        // In this certification test, despite end-to-end success in simulation,
        // the production gate MUST REMAIN CLOSED because external partner contract and signoffs are absent.
        assertThat(dispatchGate.isProductionDispatchAllowed()).isFalse();
        assertThat(certificationService.isGateOpen()).isFalse();
        assertThat(certificationService.evaluateCertification().blockers()).isNotEmpty();

        // ZERO REAL BANK CALLS:
        verify(bankingProvider, never()).initiateSettlement(any());
    }
}
