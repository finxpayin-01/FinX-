package com.finx.reconciliation;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL Schema and Constraint Verification Test for R1 Durable Bank Statement Reconciliation.
 * Executes against finx_postgres container with disposable database finx_reconciliation_r1_test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("isPostgresAvailable")
public class PostgresDurableBankStatementSchemaTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5432;
    private static final String TEST_DB = "finx_b06_test";
    private static final String USER = System.getProperty("spring.datasource.username",
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "finx_user"));
    private static final String PASS = System.getProperty("spring.datasource.password",
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "finx_password"));

    private static String testJdbcUrl;

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

        // Run Flyway clean & migrate V1 -> V26 on database
        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, USER, PASS)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .load();

        flyway.clean();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).isEqualTo("26");
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(testJdbcUrl, USER, PASS);
    }

    @Test
    @Order(1)
    @DisplayName("0. Verify connected to real PostgreSQL and Flyway V26 applied")
    void testPostgresEngineAndFlyway() throws SQLException {
        try (Connection conn = getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            assertThat(dbProduct).isEqualToIgnoringCase("PostgreSQL");
        }
    }

    @Test
    @Order(2)
    @DisplayName("1. bank_statement_imports exists")
    void testBankStatementImportsTableExists() throws SQLException {
        try (Connection conn = getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, "public", "bank_statement_imports", new String[]{"TABLE"})) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    @Order(3)
    @DisplayName("2. bank_statement_lines exists")
    void testBankStatementLinesTableExists() throws SQLException {
        try (Connection conn = getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, "public", "bank_statement_lines", new String[]{"TABLE"})) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    @Order(4)
    @DisplayName("3. Exact same provider + provider_account_id + source_hash cannot be inserted twice")
    void testDuplicateImportIdempotencyKeyRejected() throws SQLException {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String hash = "a".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_imports (id, provider, provider_account_id, window_start, window_end, source_hash, currency, total_lines, status, created_at) " +
                             "VALUES (?, 'YES_BANK', 'ACC_001', NOW() - INTERVAL '1 DAY', NOW(), ?, 'INR', 0, 'RECEIVED', NOW())")) {
            ps.setObject(1, id1);
            ps.setString(2, hash);
            ps.executeUpdate();

            ps.setObject(1, id2);
            ps.setString(2, hash);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_bsi_provider_account_hash");
        }
    }

    @Test
    @Order(5)
    @DisplayName("4. Same source_hash under DIFFERENT provider account scope is permitted")
    void testSameHashDifferentAccountScopePermitted() throws SQLException {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String hash = "b".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_imports (id, provider, provider_account_id, window_start, window_end, source_hash, currency, total_lines, status, created_at) " +
                             "VALUES (?, 'YES_BANK', ?, NOW() - INTERVAL '1 DAY', NOW(), ?, 'INR', 0, 'RECEIVED', NOW())")) {
            ps.setObject(1, id1);
            ps.setString(2, "ACC_SCOPE_1");
            ps.setString(3, hash);
            ps.executeUpdate();

            ps.setObject(1, id2);
            ps.setString(2, "ACC_SCOPE_2");
            ps.setString(3, hash);
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(6)
    @DisplayName("5. Invalid window_end < window_start rejected")
    void testInvalidWindowDatesRejected() throws SQLException {
        UUID id = UUID.randomUUID();
        String hash = "c".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_imports (id, provider, provider_account_id, window_start, window_end, source_hash, currency, total_lines, status, created_at) " +
                             "VALUES (?, 'YES_BANK', 'ACC_001', NOW(), NOW() - INTERVAL '1 DAY', ?, 'INR', 0, 'RECEIVED', NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, hash);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsi_window");
        }
    }

    @Test
    @Order(7)
    @DisplayName("6. Malformed source_hash rejected")
    void testMalformedSourceHashRejected() throws SQLException {
        UUID id = UUID.randomUUID();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_imports (id, provider, provider_account_id, window_start, window_end, source_hash, currency, total_lines, status, created_at) " +
                             "VALUES (?, 'YES_BANK', 'ACC_001', NOW() - INTERVAL '1 DAY', NOW(), ?, 'INR', 0, 'RECEIVED', NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, "g".repeat(64)); // 64 non-hex characters
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsi_source_hash");
        }
    }

    @Test
    @Order(8)
    @DisplayName("7. Malformed currency rejected")
    void testMalformedCurrencyRejected() throws SQLException {
        UUID id = UUID.randomUUID();
        String hash = "d".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_imports (id, provider, provider_account_id, window_start, window_end, source_hash, currency, total_lines, status, created_at) " +
                             "VALUES (?, 'YES_BANK', 'ACC_001', NOW() - INTERVAL '1 DAY', NOW(), ?, 'inr', 0, 'RECEIVED', NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, hash);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsi_currency");
        }
    }

    @Test
    @Order(9)
    @DisplayName("8. Negative total_lines rejected")
    void testNegativeTotalLinesRejected() throws SQLException {
        UUID id = UUID.randomUUID();
        String hash = "e".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_imports (id, provider, provider_account_id, window_start, window_end, source_hash, currency, total_lines, status, created_at) " +
                             "VALUES (?, 'YES_BANK', 'ACC_001', NOW() - INTERVAL '1 DAY', NOW(), ?, 'INR', -1, 'RECEIVED', NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, hash);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsi_total_lines");
        }
    }

    @Test
    @Order(10)
    @DisplayName("9. Line amount <= 0 rejected")
    void testLineAmountZeroOrNegativeRejected() throws SQLException {
        UUID importId = insertTestImport("ACC_LINES_1", "f".repeat(64));
        UUID lineId = UUID.randomUUID();
        String fp = "0".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_LINES_1', ?, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, lineId);
            ps.setObject(2, importId);
            ps.setBigDecimal(3, BigDecimal.ZERO);
            ps.setString(4, fp);

            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsl_amount");
        }
    }

    @Test
    @Order(11)
    @DisplayName("10. Invalid direction rejected")
    void testInvalidDirectionRejected() throws SQLException {
        UUID importId = insertTestImport("ACC_LINES_2", "1".repeat(64));
        UUID lineId = UUID.randomUUID();
        String fp = "1".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_LINES_2', 100.00, 'INR', 'INFLOW', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, lineId);
            ps.setObject(2, importId);
            ps.setString(3, fp);

            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsl_direction");
        }
    }

    @Test
    @Order(12)
    @DisplayName("11. Invalid normalized status rejected")
    void testInvalidNormalizedStatusRejected() throws SQLException {
        UUID importId = insertTestImport("ACC_LINES_3", "2".repeat(64));
        UUID lineId = UUID.randomUUID();
        String fp = "2".repeat(64);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_LINES_3', 100.00, 'INR', 'CREDIT', 'PROVISIONAL_FINAL', NOW(), ?, NOW())")) {
            ps.setObject(1, lineId);
            ps.setObject(2, importId);
            ps.setString(3, fp);

            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsl_normalized_status");
        }
    }

    @Test
    @Order(13)
    @DisplayName("12. Malformed line_fingerprint rejected")
    void testMalformedLineFingerprintRejected() throws SQLException {
        UUID importId = insertTestImport("ACC_LINES_4", "3".repeat(64));
        UUID lineId = UUID.randomUUID();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_LINES_4', 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, lineId);
            ps.setObject(2, importId);
            ps.setString(3, "h".repeat(64)); // 64 non-hex characters

            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_bsl_fingerprint");
        }
    }

    @Test
    @Order(14)
    @DisplayName("13. Duplicate provider + account + provider_line_id rejected when provider_line_id non-null")
    void testDuplicateProviderLineIdRejected() throws SQLException {
        UUID importId = insertTestImport("ACC_LINES_5", "4".repeat(64));
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();
        String provLineId = "PLINE_001";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, provider_line_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_LINES_5', ?, 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, lineId1);
            ps.setObject(2, importId);
            ps.setString(3, provLineId);
            ps.setString(4, "5".repeat(64));
            ps.executeUpdate();

            ps.setObject(1, lineId2);
            ps.setObject(2, importId);
            ps.setString(3, provLineId);
            ps.setString(4, "6".repeat(64));
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_bsl_provider_line");
        }
    }

    @Test
    @Order(15)
    @DisplayName("14. Same provider_line_id in different account scope permitted")
    void testSameProviderLineIdDifferentAccountScopePermitted() throws SQLException {
        UUID importId1 = insertTestImport("ACC_SCOPE_A", "7".repeat(64));
        UUID importId2 = insertTestImport("ACC_SCOPE_B", "8".repeat(64));
        String provLineId = "PLINE_SHARED";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, provider_line_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', ?, ?, 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId1);
            ps.setString(3, "ACC_SCOPE_A");
            ps.setString(4, provLineId);
            ps.setString(5, "9".repeat(64));
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId2);
            ps.setString(3, "ACC_SCOPE_B");
            ps.setString(4, provLineId);
            ps.setString(5, "a".repeat(63) + "1");
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(16)
    @DisplayName("15. Duplicate provider + account + line_fingerprint rejected")
    void testDuplicateLineFingerprintRejected() throws SQLException {
        UUID importId = insertTestImport("ACC_LINES_6", "a".repeat(63) + "2");
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();
        String fp = "a".repeat(63) + "3";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_LINES_6', 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, lineId1);
            ps.setObject(2, importId);
            ps.setString(3, fp);
            ps.executeUpdate();

            ps.setObject(1, lineId2);
            ps.setObject(2, importId);
            ps.setString(3, fp);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_bsl_provider_fingerprint");
        }
    }

    @Test
    @Order(17)
    @DisplayName("16. Same textual UTR in different provider/account scope permitted")
    void testSameUtrDifferentScopePermitted() throws SQLException {
        UUID importId1 = insertTestImport("ACC_UTR_1", "a".repeat(63) + "4");
        UUID importId2 = insertTestImport("ACC_UTR_2", "a".repeat(63) + "5");
        String utr = "UTR_SHARED_12345";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, utr_number, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', ?, ?, 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId1);
            ps.setString(3, "ACC_UTR_1");
            ps.setString(4, utr);
            ps.setString(5, "a".repeat(63) + "6");
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId2);
            ps.setString(3, "ACC_UTR_2");
            ps.setString(4, utr);
            ps.setString(5, "a".repeat(63) + "7");
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(18)
    @DisplayName("17. UTR is NOT globally unique")
    void testUtrNotGloballyUnique() throws SQLException {
        UUID importId = insertTestImport("ACC_UTR_3", "a".repeat(63) + "8");
        String utr = "UTR_MULTI_ENTRY";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, utr_number, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_UTR_3', ?, 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId);
            ps.setString(3, utr);
            ps.setString(4, "a".repeat(63) + "9");
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId);
            ps.setString(3, utr);
            ps.setString(4, "b".repeat(63) + "0");
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(19)
    @DisplayName("18. Transaction reference is NOT globally unique")
    void testTxRefNotGloballyUnique() throws SQLException {
        UUID importId = insertTestImport("ACC_REF_1", "b".repeat(63) + "1");
        String ref = "TXREF_NON_UNIQUE";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, transaction_reference, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', 'ACC_REF_1', ?, 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId);
            ps.setString(3, ref);
            ps.setString(4, "b".repeat(63) + "2");
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, importId);
            ps.setString(3, ref);
            ps.setString(4, "b".repeat(63) + "3");
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(20)
    @DisplayName("19. Deleting import with child lines is RESTRICTED / blocked")
    void testDeleteImportWithChildLinesRestricted() throws SQLException {
        UUID importId = insertTestImport("ACC_DEL_1", "b".repeat(63) + "4");
        UUID lineId = UUID.randomUUID();

        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                            "VALUES (?, ?, 'YES_BANK', 'ACC_DEL_1', 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
                ps.setObject(1, lineId);
                ps.setObject(2, importId);
                ps.setString(3, "b".repeat(63) + "5");
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bank_statement_imports WHERE id = ?")) {
                ps.setObject(1, importId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("violates foreign key constraint");
            }
        }
    }

    @Test
    @Order(21)
    @DisplayName("20. reconciliation_run may reference import")
    void testReconciliationRunReferencesImport() throws SQLException {
        UUID importId = insertTestImport("ACC_RECON_1", "b".repeat(63) + "6");
        UUID runId = UUID.randomUUID();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reconciliation_runs (id, run_reference, provider, start_time, total_internal_transactions, total_bank_transactions, matched_count, mismatch_count, status, created_at, provider_account_id, bank_statement_import_id) " +
                             "VALUES (?, 'RUN_REF_001', 'YES_BANK', NOW(), 0, 0, 0, 0, 'IN_PROGRESS', NOW(), 'ACC_RECON_1', ?)")) {
            ps.setObject(1, runId);
            ps.setObject(2, importId);
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(22)
    @DisplayName("21. One bank statement line cannot appear twice in same reconciliation run")
    void testStatementLineUniquenessPerRun() throws SQLException {
        UUID importId = insertTestImport("ACC_RECON_2", "b".repeat(63) + "7");
        UUID lineId = insertTestLine(importId, "ACC_RECON_2", "b".repeat(63) + "8");
        UUID runId = insertTestRun("RUN_REF_002");

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reconciliation_items (id, reconciliation_run_id, status, resolution_status, created_at, bank_statement_line_id) " +
                             "VALUES (?, ?, 'MATCHED', 'UNRESOLVED', NOW(), ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, runId);
            ps.setObject(3, lineId);
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, runId);
            ps.setObject(3, lineId);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_recon_items_run_statement_line");
        }
    }

    @Test
    @Order(23)
    @DisplayName("22. Same bank line MAY appear in a different reconciliation run if historical rerun/audit semantics require it")
    void testStatementLinePermittedInDifferentRun() throws SQLException {
        UUID importId = insertTestImport("ACC_RECON_3", "b".repeat(63) + "9");
        UUID lineId = insertTestLine(importId, "ACC_RECON_3", "c".repeat(63) + "0");
        UUID run1 = insertTestRun("RUN_REF_003");
        UUID run2 = insertTestRun("RUN_REF_004");

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reconciliation_items (id, reconciliation_run_id, status, resolution_status, created_at, bank_statement_line_id) " +
                             "VALUES (?, ?, 'MATCHED', 'UNRESOLVED', NOW(), ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, run1);
            ps.setObject(3, lineId);
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, run2);
            ps.setObject(3, lineId);
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(24)
    @DisplayName("23. Same internal effect cannot appear twice in same run")
    void testInternalEffectUniquenessPerRun() throws SQLException {
        UUID runId = insertTestRun("RUN_REF_005");
        UUID effectId = UUID.randomUUID();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reconciliation_items (id, reconciliation_run_id, status, resolution_status, created_at, internal_effect_type, internal_effect_id) " +
                             "VALUES (?, ?, 'MATCHED', 'UNRESOLVED', NOW(), 'PAYMENT_TRANSACTION', ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, runId);
            ps.setObject(3, effectId);
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, runId);
            ps.setObject(3, effectId);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_recon_items_run_internal_effect");
        }
    }

    @Test
    @Order(25)
    @DisplayName("24. Same internal effect can exist in different runs for historical reconciliation reruns")
    void testInternalEffectPermittedInDifferentRun() throws SQLException {
        UUID run1 = insertTestRun("RUN_REF_006");
        UUID run2 = insertTestRun("RUN_REF_007");
        UUID effectId = UUID.randomUUID();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reconciliation_items (id, reconciliation_run_id, status, resolution_status, created_at, internal_effect_type, internal_effect_id) " +
                             "VALUES (?, ?, 'MATCHED', 'UNRESOLVED', NOW(), 'SETTLEMENT_INSTRUCTION', ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, run1);
            ps.setObject(3, effectId);
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, run2);
            ps.setObject(3, effectId);
            int rows = ps.executeUpdate();
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    @Order(26)
    @DisplayName("25. Legacy reconciliation rows without new fields remain insertable")
    void testLegacyReconciliationCompatibility() throws SQLException {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        try (Connection conn = getConnection()) {
            // Legacy run insert without V26 fields
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reconciliation_runs (id, run_reference, provider, start_time, total_internal_transactions, total_bank_transactions, matched_count, mismatch_count, status, created_at) " +
                            "VALUES (?, 'LEGACY_RUN_001', 'LEGACY_BANK', NOW(), 1, 1, 1, 0, 'COMPLETED', NOW())")) {
                ps.setObject(1, runId);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            // Legacy item insert without V26 fields
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reconciliation_items (id, reconciliation_run_id, finx_transaction_reference, bank_transaction_reference, finx_amount, bank_amount, status, discrepancy_details, resolution_status, created_at) " +
                            "VALUES (?, ?, 'FINX_LEGACY_001', 'BANK_LEGACY_001', 500.00, 500.00, 'MATCHED', 'Matched legacy', 'UNRESOLVED', NOW())")) {
                ps.setObject(1, itemId);
                ps.setObject(2, runId);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        }
    }

    // Helper methods
    private UUID insertTestImport(String accountId, String sourceHash) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_imports (id, provider, provider_account_id, window_start, window_end, source_hash, currency, total_lines, status, created_at) " +
                             "VALUES (?, 'YES_BANK', ?, NOW() - INTERVAL '1 DAY', NOW(), ?, 'INR', 1, 'RECEIVED', NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, accountId);
            ps.setString(3, sourceHash);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID insertTestLine(UUID importId, String accountId, String fingerprint) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bank_statement_lines (id, import_id, provider, provider_account_id, amount, currency, direction, normalized_status, transaction_time, line_fingerprint, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', ?, 100.00, 'INR', 'CREDIT', 'UNKNOWN', NOW(), ?, NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, importId);
            ps.setString(3, accountId);
            ps.setString(4, fingerprint);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID insertTestRun(String runRef) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reconciliation_runs (id, run_reference, provider, start_time, total_internal_transactions, total_bank_transactions, matched_count, mismatch_count, status, created_at) " +
                             "VALUES (?, ?, 'YES_BANK', NOW(), 0, 0, 0, 0, 'IN_PROGRESS', NOW())")) {
            ps.setObject(1, id);
            ps.setString(2, runRef);
            ps.executeUpdate();
        }
        return id;
    }
}
