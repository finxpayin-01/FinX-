package com.finx.reconciliation;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.dto.DeterministicReconciliationResult;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.BankStatementEvidenceFingerprint;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.security.service.UserPrincipal;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL Integration Test for DeterministicReconciliationMatcher.
 * Executes against finx_postgres container with Flyway V1 -> V28.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_b06_test}",
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("isPostgresAvailable")
public class PostgresDeterministicReconciliationMatcherTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5432;
    private static final String TEST_DB = "finx_b06_test";
    private static final String USER = System.getProperty("spring.datasource.username",
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "finx_user"));
    private static final String PASS = System.getProperty("spring.datasource.password",
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "finx_password"));

    private static String testJdbcUrl;

    @Autowired
    private DeterministicReconciliationMatcher matcher;

    @Autowired
    private BankStatementImportRepository importRepository;
    @Autowired
    private BankStatementLineRepository lineRepository;
    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired
    private PaymentInstructionRepository paymentInstructionRepository;
    @Autowired
    private SettlementInstructionRepository settlementInstructionRepository;
    @Autowired
    private ReconciliationRunRepository runRepository;
    @Autowired
    private ReconciliationItemRepository itemRepository;

    private static final String PROVIDER = "MOCK";
    private static final String PROVIDER_ACCOUNT_ID = "MOCK_ESCROW_REC_TEST";
    private static final String CURRENCY = "INR";

    private UserPrincipal adminUser;

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void initDatabase() throws Exception {
        testJdbcUrl = System.getProperty("spring.datasource.url",
                System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://" + HOST + ":" + PORT + "/" + TEST_DB));

        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, USER, PASS)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .load();

        flyway.clean();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(Integer.parseInt(result.targetSchemaVersion)).isGreaterThanOrEqualTo(28);
    }

    @BeforeEach
    void setUp() throws SQLException {
        UUID adminId = UUID.randomUUID();
        try (Connection conn = getConnection()) {
            conn.createStatement().execute("DELETE FROM bank_statement_lines");
            conn.createStatement().execute("DELETE FROM bank_statement_imports");
            conn.createStatement().execute("DELETE FROM reconciliation_items");
            conn.createStatement().execute("DELETE FROM reconciliation_runs");
            conn.createStatement().execute("DELETE FROM payment_transactions");
            conn.createStatement().execute("DELETE FROM payment_instructions");
            conn.createStatement().execute("DELETE FROM settlement_instructions");
            conn.createStatement().execute("DELETE FROM users WHERE email = 'admin_matcher_pg@finx.com'");

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, name, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?, 'Admin Matcher', 'admin_matcher_pg@finx.com', 'hashedpass', 'ADMIN', 'ACTIVE', NOW(), NOW())")) {
                ps.setObject(1, adminId);
                ps.executeUpdate();
            }
        }
        adminUser = new UserPrincipal(adminId, "Admin Matcher", "admin_matcher_pg@finx.com", "hash", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(testJdbcUrl, USER, PASS);
    }

    private BankStatementLine buildLine(UUID importId, String providerLineId, String txnRef, String utr,
                                       BigDecimal amount, String currency, FinancialDirection direction,
                                       BankStatementLineStatus status, Instant txnTime) {
        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint(
                PROVIDER, PROVIDER_ACCOUNT_ID, providerLineId, txnRef, utr, amount, currency,
                direction != null ? direction.name() : null, "SUCCESS",
                status != null ? status.name() : null, txnTime, LocalDate.now()
        );
        return new BankStatementLine(importId, PROVIDER, PROVIDER_ACCOUNT_ID, providerLineId,
                txnRef, utr, amount, currency, direction, "SUCCESS", status, txnTime, LocalDate.now(), fp);
    }

    private BankStatementImport buildImport(List<BankStatementLine> lines, BigDecimal opening, BigDecimal closing,
                                           Instant start, Instant end) {
        List<String> fingerprints = lines.stream().map(BankStatementLine::getLineFingerprint).toList();
        String sourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                PROVIDER, PROVIDER_ACCOUNT_ID, CURRENCY, opening, closing, "IMP-PG-1", fingerprints, start, end
        );

        BankStatementImport imp = new BankStatementImport(
                PROVIDER, PROVIDER_ACCOUNT_ID, start, end, sourceHash,
                opening, closing, CURRENCY, adminUser.getId()
        );
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        imp.setProviderImportReference("IMP-PG-1");
        imp.setTotalLines(lines.size());
        return imp;
    }

    @Test
    @Order(1)
    @DisplayName("1. Verify PostgreSQL product and V28 columns exist in information_schema")
    void testPostgresV28ColumnsExist() throws Exception {
        try (Connection conn = getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");

            DatabaseMetaData meta = conn.getMetaData();
            List<String> runCols = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(null, null, "reconciliation_runs", "%")) {
                while (rs.next()) {
                    runCols.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(runCols).contains("matcher_version", "statement_source_hash");

            List<String> itemCols = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(null, null, "reconciliation_items", "%")) {
                while (rs.next()) {
                    itemCols.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(itemCols).contains("reason_code");
        }
    }

    @Test
    @Order(2)
    @DisplayName("2. Legacy run allows matcher_version NULL and V28 constraints work")
    void testLegacyRunNullMatcherVersion() throws Exception {
        try (Connection conn = getConnection()) {
            UUID legacyRunId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reconciliation_runs (id, run_reference, provider, start_time, total_internal_transactions, total_bank_transactions, matched_count, mismatch_count, status, created_at, matcher_version, statement_source_hash) " +
                            "VALUES (?, ?, 'MOCK', NOW(), 0, 0, 0, 0, 'COMPLETED', NOW(), NULL, NULL)")) {
                ps.setObject(1, legacyRunId);
                ps.setString(2, "LEGACY-RUN-" + legacyRunId.toString().substring(0, 8));
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT matcher_version, statement_source_hash FROM reconciliation_runs WHERE id = ?")) {
                ps.setObject(1, legacyRunId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("matcher_version")).isNull();
                    assertThat(rs.getString("statement_source_hash")).isNull();
                }
            }
        }
    }

    private UUID createTestUser(Connection conn, String role) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, name, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, 'Test User', ?, 'hash', ?, 'ACTIVE', NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, "user-" + id + "@test.com");
            ps.setString(3, role);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID createTestOrganization(Connection conn) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO organizations (id, name, legal_name, entity_type, registration_number, verification_status, created_at, updated_at) " +
                        "VALUES (?, 'Test Org', 'Test Org Legal', 'PRIVATE_LIMITED', ?, 'VERIFIED', NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, "REG-" + id.toString().substring(0, 8));
            ps.executeUpdate();
        }
        return id;
    }

    private UUID createTestOrgBankAccount(Connection conn, UUID orgId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO organization_bank_accounts (id, organization_id, account_holder_name, account_number_masked, account_number_hash, ifsc_code, bank_name, verification_status, created_at, updated_at) " +
                        "VALUES (?, ?, 'Test Holder', 'XXXX1234', 'hash1234', 'TEST0001001', 'Test Bank', 'VERIFIED', NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, orgId);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID createTestDeal(Connection conn, UUID buyerId, UUID sellerId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO deals (id, title, buyer_id, seller_id, total_amount, currency, status, created_at, updated_at) " +
                        "VALUES (?, 'Test Deal', ?, ?, 100000.00, 'INR', 'IN_PROGRESS', NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, buyerId);
            ps.setObject(3, sellerId);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID createTestMilestone(Connection conn, UUID dealId, int sequence) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO milestones (id, deal_id, title, sequence, amount, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'Milestone " + sequence + "', ?, 50000.00, 'INR', 'APPROVED', NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, dealId);
            ps.setInt(3, sequence);
            ps.executeUpdate();
        }
        return id;
    }

    @Test
    @Order(3)
    @DisplayName("3. End-to-end deterministic reconciliation on PostgreSQL with 0 financial side effects")
    void testEndToEndDeterministicReconciliationOnPostgres() throws Exception {
        UUID buyerId;
        UUID sellerId;
        UUID dealId;
        UUID milestoneId1;
        UUID milestoneId2;
        UUID sellerOrgId;
        UUID beneficiaryBankAccountId;

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM reconciliation_items");
            st.execute("DELETE FROM reconciliation_runs");
            st.execute("DELETE FROM bank_statement_lines");
            st.execute("DELETE FROM bank_statement_imports");
            st.execute("DELETE FROM payment_transactions");
            st.execute("DELETE FROM payment_instructions");
            st.execute("DELETE FROM settlement_instructions");

            buyerId = createTestUser(conn, "BUYER");
            sellerId = createTestUser(conn, "SELLER");
            dealId = createTestDeal(conn, buyerId, sellerId);
            milestoneId1 = createTestMilestone(conn, dealId, 1);
            milestoneId2 = createTestMilestone(conn, dealId, 2);
            sellerOrgId = createTestOrganization(conn);
            beneficiaryBankAccountId = createTestOrgBankAccount(conn, sellerOrgId);
        }

        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.DAYS);
        Instant end = start.plus(2, ChronoUnit.DAYS);

        // 1. Setup durable bank statement lines
        UUID importId = UUID.randomUUID();
        // Line 1: Inbound matching payment
        BankStatementLine line1 = buildLine(importId, "PL-1", "BANK-TXN-1", "UTR-PG-1", BigDecimal.valueOf(5000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, start.plusSeconds(100));
        // Line 2: Outbound matching settlement
        BankStatementLine line2 = buildLine(importId, "PL-2", "BANK-TXN-2", "UTR-PG-2", BigDecimal.valueOf(2000), CURRENCY,
                FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, start.plusSeconds(200));
        // Line 3: Amount mismatch payment
        BankStatementLine line3 = buildLine(importId, "PL-3", "BANK-TXN-3", "UTR-PG-3", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, start.plusSeconds(300));
        // Line 4: Unclaimed final bank line
        BankStatementLine line4 = buildLine(importId, "PL-4", "BANK-TXN-4", "UTR-PG-4", BigDecimal.valueOf(800), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, start.plusSeconds(400));

        List<BankStatementLine> lines = List.of(line1, line2, line3, line4);
        // opening 0, credits: 5000 + 1000 + 800 = 6800, debits: 2000 -> expected closing = 4800
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal closing = BigDecimal.valueOf(4800);

        BankStatementImport statementImport = buildImport(lines, opening, closing, start, end);
        statementImport.setId(importId);

        importRepository.save(statementImport);
        lineRepository.saveAll(lines);

        // 2. Setup internal records
        // Internal 1: Matching payment
        PaymentInstruction pi1 = new PaymentInstruction("INST-PG-1", dealId, milestoneId1, null, sellerOrgId,
                BigDecimal.valueOf(5000), CURRENCY, PROVIDER, "IDEM-PG-1", adminUser.getId());
        pi1.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        pi1.setStatus(InstructionStatus.SUCCESS);
        paymentInstructionRepository.save(pi1);

        PaymentTransaction pt1 = new PaymentTransaction("TXN-PG-1", pi1.getId(), PROVIDER, "PROV-TXN-1",
                BigDecimal.valueOf(5000), CURRENCY, "SUCCESS", "UTR-PG-1", "{}");
        pt1.setCreatedAt(start.plusSeconds(90));
        paymentTransactionRepository.save(pt1);

        // Internal 2: Matching settlement
        SettlementInstruction si2 = new SettlementInstruction("SETT-PG-2", dealId, milestoneId1, sellerOrgId, beneficiaryBankAccountId,
                BigDecimal.valueOf(2000), CURRENCY, PROVIDER, "IDEM-PG-2", adminUser.getId());
        si2.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        si2.setStatus(SettlementStatus.SETTLED);
        si2.setSettledAt(start.plusSeconds(190));
        si2.setCreatedAt(start.plusSeconds(190));
        si2.setUtrNumber("UTR-PG-2");
        settlementInstructionRepository.save(si2);

        // Internal 3: Amount mismatch payment (internal is 1500, bank is 1000)
        PaymentInstruction pi3 = new PaymentInstruction("INST-PG-3", dealId, milestoneId1, null, sellerOrgId,
                BigDecimal.valueOf(1500), CURRENCY, PROVIDER, "IDEM-PG-3", adminUser.getId());
        pi3.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        pi3.setStatus(InstructionStatus.SUCCESS);
        paymentInstructionRepository.save(pi3);

        PaymentTransaction pt3 = new PaymentTransaction("TXN-PG-3", pi3.getId(), PROVIDER, "PROV-TXN-3",
                BigDecimal.valueOf(1500), CURRENCY, "SUCCESS", "UTR-PG-3", "{}");
        pt3.setCreatedAt(start.plusSeconds(290));
        paymentTransactionRepository.save(pt3);

        // Internal 4: Missing outbound settlement (in DB as SETTLED, not in bank)
        SettlementInstruction si4 = new SettlementInstruction("SETT-PG-4", dealId, milestoneId2, sellerOrgId, beneficiaryBankAccountId,
                BigDecimal.valueOf(750), CURRENCY, PROVIDER, "IDEM-PG-4", adminUser.getId());
        si4.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        si4.setStatus(SettlementStatus.SETTLED);
        si4.setSettledAt(start.plusSeconds(350));
        si4.setCreatedAt(start.plusSeconds(350));
        si4.setUtrNumber("UTR-PG-4-MISSING");
        settlementInstructionRepository.save(si4);

        // Snapshot counts before matching
        long countPIBefore = paymentInstructionRepository.count();
        long countPTBefore = paymentTransactionRepository.count();
        long countSIBefore = settlementInstructionRepository.count();
        long countImportsBefore = importRepository.count();
        long countLinesBefore = lineRepository.count();

        // 3. Execute Matcher
        DeterministicReconciliationResult result = matcher.reconcileImport(statementImport.getId(), adminUser);

        // 4. Verify Matcher result
        assertThat(result.run()).isNotNull();
        assertThat(result.run().getMatcherVersion()).isEqualTo(DeterministicReconciliationMatcher.MATCHER_VERSION);
        assertThat(result.run().getStatementSourceHash()).isEqualTo(statementImport.getSourceHash());
        assertThat(result.run().getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(result.clean()).isFalse(); // Because of amount mismatch, missing outbound, unclaimed line

        // Total evaluated internal effects = 4 (pt1, si2, pt3, si4)
        assertThat(result.run().getTotalInternalTransactions()).isEqualTo(4);
        assertThat(result.run().getTotalBankTransactions()).isEqualTo(4);
        // Matched = 2 (pt1, si2)
        assertThat(result.run().getMatchedCount()).isEqualTo(2);
        // Mismatches = 3 (pt3 amount mismatch, si4 missing external, line4 unclaimed final line)
        assertThat(result.run().getMismatchCount()).isEqualTo(3);

        // Verify items in DB
        List<ReconciliationItem> persistedItems = itemRepository.findByReconciliationRunId(result.run().getId());
        assertThat(persistedItems).hasSize(5); // 2 matched + 3 discrepancies

        // Verify reason codes are persisted
        List<ReconciliationReasonCode> reasons = persistedItems.stream().map(ReconciliationItem::getReasonCode).toList();
        assertThat(reasons).contains(
                ReconciliationReasonCode.MATCHED_CONFIRMED,
                ReconciliationReasonCode.AMOUNT_MISMATCH,
                ReconciliationReasonCode.MISSING_EXTERNAL,
                ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE
        );

        // 5. Verify ZERO financial side effects (count deltas = 0)
        assertThat(paymentInstructionRepository.count()).isEqualTo(countPIBefore);
        assertThat(paymentTransactionRepository.count()).isEqualTo(countPTBefore);
        assertThat(settlementInstructionRepository.count()).isEqualTo(countSIBefore);
        assertThat(importRepository.count()).isEqualTo(countImportsBefore);
        assertThat(lineRepository.count()).isEqualTo(countLinesBefore);
    }
}
