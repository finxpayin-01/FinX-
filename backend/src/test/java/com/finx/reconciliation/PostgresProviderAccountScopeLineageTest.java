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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL Schema and Lineage Verification Test for R2.5 Internal Provider Account Scope Lineage.
 * Executes against finx_postgres container with Flyway V1 -> V27.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("isPostgresAvailable")
public class PostgresProviderAccountScopeLineageTest {

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

        // Run Flyway clean & migrate V1 -> V27 on database
        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, USER, PASS)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .load();

        flyway.clean();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(Integer.parseInt(result.targetSchemaVersion)).isGreaterThanOrEqualTo(27);
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(testJdbcUrl, USER, PASS);
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

    private UUID createTestMilestone(Connection conn, UUID dealId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO milestones (id, deal_id, title, sequence, amount, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'Milestone 1', 1, 50000.00, 'INR', 'APPROVED', NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, dealId);
            ps.executeUpdate();
        }
        return id;
    }

    @Test
    @Order(1)
    @DisplayName("0. Verify connected to real PostgreSQL and Flyway V27 applied")
    void testPostgresEngineAndFlyway() throws SQLException {
        try (Connection conn = getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            assertThat(dbProduct).isEqualToIgnoringCase("PostgreSQL");
        }
    }

    @Test
    @Order(2)
    @DisplayName("1. payment_instructions.provider_account_id exists")
    void testPaymentInstructionsProviderAccountIdExists() throws SQLException {
        try (Connection conn = getConnection()) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, "public", "payment_instructions", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(columns).contains("provider_account_id");
        }
    }

    @Test
    @Order(3)
    @DisplayName("2. settlement_instructions.provider_account_id exists")
    void testSettlementInstructionsProviderAccountIdExists() throws SQLException {
        try (Connection conn = getConnection()) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, "public", "settlement_instructions", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(columns).contains("provider_account_id");
        }
    }

    @Test
    @Order(4)
    @DisplayName("3. legacy payment instruction with NULL provider_account_id is permitted")
    void testLegacyPaymentInstructionWithNullProviderAccountPermitted() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID piId = UUID.randomUUID();
            String ref = "PI-LEGACY-" + UUID.randomUUID().toString().substring(0, 8);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, 1000.00, 'INR', 'MOCK', 'CREATED', NOW(), NOW(), NULL)")) {
                ps.setObject(1, piId);
                ps.setString(2, ref);
                ps.setObject(3, dealId);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("4. legacy settlement instruction with NULL provider_account_id is permitted")
    void testLegacySettlementInstructionWithNullProviderAccountPermitted() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID milestoneId = createTestMilestone(conn, dealId);
            UUID sellerOrgId = createTestOrganization(conn);
            UUID beneficiaryBankAccountId = createTestOrgBankAccount(conn, sellerOrgId);
            UUID siId = UUID.randomUUID();
            String ref = "SETTL-LEGACY-" + UUID.randomUUID().toString().substring(0, 8);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO settlement_instructions (id, settlement_reference, deal_id, milestone_id, seller_organization_id, beneficiary_bank_account_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 500.00, 'INR', 'MOCK', 'PENDING', NOW(), NOW(), NULL)")) {
                ps.setObject(1, siId);
                ps.setString(2, ref);
                ps.setObject(3, dealId);
                ps.setObject(4, milestoneId);
                ps.setObject(5, sellerOrgId);
                ps.setObject(6, beneficiaryBankAccountId);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("5. blank provider_account_id rejected by check constraint on payment_instructions")
    void testBlankPaymentInstructionProviderAccountRejected() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID piId = UUID.randomUUID();
            String ref = "PI-BLANK-" + UUID.randomUUID().toString().substring(0, 8);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, 1000.00, 'INR', 'MOCK', 'CREATED', NOW(), NOW(), '   ')")) {
                ps.setObject(1, piId);
                ps.setString(2, ref);
                ps.setObject(3, dealId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_pi_provider_account_id");
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("6. blank provider_account_id rejected by check constraint on settlement_instructions")
    void testBlankSettlementInstructionProviderAccountRejected() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID milestoneId = createTestMilestone(conn, dealId);
            UUID sellerOrgId = createTestOrganization(conn);
            UUID beneficiaryBankAccountId = createTestOrgBankAccount(conn, sellerOrgId);
            UUID siId = UUID.randomUUID();
            String ref = "SETTL-BLANK-" + UUID.randomUUID().toString().substring(0, 8);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO settlement_instructions (id, settlement_reference, deal_id, milestone_id, seller_organization_id, beneficiary_bank_account_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 500.00, 'INR', 'MOCK', 'PENDING', NOW(), NOW(), '   ')")) {
                ps.setObject(1, siId);
                ps.setString(2, ref);
                ps.setObject(3, dealId);
                ps.setObject(4, milestoneId);
                ps.setObject(5, sellerOrgId);
                ps.setObject(6, beneficiaryBankAccountId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_si_provider_account_id");
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("7. nonblank <=128 provider_account_id accepted on payment_instructions and settlement_instructions")
    void testValidProviderAccountIdAccepted() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID milestoneId = createTestMilestone(conn, dealId);
            UUID sellerOrgId = createTestOrganization(conn);
            UUID beneficiaryBankAccountId = createTestOrgBankAccount(conn, sellerOrgId);
            UUID piId = UUID.randomUUID();
            UUID siId = UUID.randomUUID();
            String piRef = "PI-VALID-" + UUID.randomUUID().toString().substring(0, 8);
            String siRef = "SETTL-VALID-" + UUID.randomUUID().toString().substring(0, 8);
            String accountScope = "MOCK_ESCROW_ACCOUNT_SCOPE_001";

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, 1000.00, 'INR', 'MOCK', 'CREATED', NOW(), NOW(), ?)")) {
                ps.setObject(1, piId);
                ps.setString(2, piRef);
                ps.setObject(3, dealId);
                ps.setString(4, accountScope);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO settlement_instructions (id, settlement_reference, deal_id, milestone_id, seller_organization_id, beneficiary_bank_account_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 500.00, 'INR', 'MOCK', 'PENDING', NOW(), NOW(), ?)")) {
                ps.setObject(1, siId);
                ps.setString(2, siRef);
                ps.setObject(3, dealId);
                ps.setObject(4, milestoneId);
                ps.setObject(5, sellerOrgId);
                ps.setObject(6, beneficiaryBankAccountId);
                ps.setString(7, accountScope);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(9)
    @DisplayName("8. multiple payment instructions may share the same provider and account scope")
    void testMultiplePaymentInstructionsSharedAccountScope() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID piId1 = UUID.randomUUID();
            UUID piId2 = UUID.randomUUID();
            String accountScope = "SHARED_ESCROW_POOL_001";

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, 1000.00, 'INR', 'MOCK', 'CREATED', NOW(), NOW(), ?)")) {
                ps.setObject(1, piId1);
                ps.setString(2, "PI-SHARED-1-" + UUID.randomUUID().toString().substring(0, 6));
                ps.setObject(3, dealId);
                ps.setString(4, accountScope);
                ps.executeUpdate();

                ps.setObject(1, piId2);
                ps.setString(2, "PI-SHARED-2-" + UUID.randomUUID().toString().substring(0, 6));
                ps.setObject(3, dealId);
                ps.setString(4, accountScope);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(10)
    @DisplayName("9. multiple settlement instructions may share the same provider and account scope")
    void testMultipleSettlementInstructionsSharedAccountScope() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID milestoneId1 = createTestMilestone(conn, dealId);
            UUID milestoneId2 = createTestMilestone(conn, dealId);
            UUID sellerOrgId = createTestOrganization(conn);
            UUID beneficiaryBankAccountId = createTestOrgBankAccount(conn, sellerOrgId);
            UUID siId1 = UUID.randomUUID();
            UUID siId2 = UUID.randomUUID();
            String accountScope = "SHARED_ESCROW_POOL_001";

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO settlement_instructions (id, settlement_reference, deal_id, milestone_id, seller_organization_id, beneficiary_bank_account_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 500.00, 'INR', 'MOCK', 'PENDING', NOW(), NOW(), ?)")) {
                ps.setObject(1, siId1);
                ps.setString(2, "SETTL-SHARED-1-" + UUID.randomUUID().toString().substring(0, 6));
                ps.setObject(3, dealId);
                ps.setObject(4, milestoneId1);
                ps.setObject(5, sellerOrgId);
                ps.setObject(6, beneficiaryBankAccountId);
                ps.setString(7, accountScope);
                ps.executeUpdate();

                ps.setObject(1, siId2);
                ps.setString(2, "SETTL-SHARED-2-" + UUID.randomUUID().toString().substring(0, 6));
                ps.setObject(3, dealId);
                ps.setObject(4, milestoneId2);
                ps.setObject(5, sellerOrgId);
                ps.setObject(6, beneficiaryBankAccountId);
                ps.setString(7, accountScope);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(11)
    @DisplayName("10. account-scope composite indexes exist on payment_instructions and settlement_instructions")
    void testAccountScopeIndexesExist() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'payment_instructions' AND indexname = 'idx_payment_instructions_provider_account_created'")) {
                assertThat(rs.next()).isTrue();
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'settlement_instructions' AND indexname = 'idx_settlement_instructions_provider_account_created'")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    @Test
    @Order(12)
    @DisplayName("11. Part O: PaymentTransaction -> PaymentInstruction -> providerAccountId resolves accurately without duplicate column")
    void testPaymentTransactionLineageToProviderAccountId() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID buyerId = createTestUser(conn, "BUYER");
            UUID sellerId = createTestUser(conn, "SELLER");
            UUID dealId = createTestDeal(conn, buyerId, sellerId);
            UUID piId = UUID.randomUUID();
            UUID txId = UUID.randomUUID();
            String piRef = "PI-LINEAGE-" + UUID.randomUUID().toString().substring(0, 8);
            String txRef = "TXN-" + piRef;
            String expectedAccountScope = "MOCK_ESCROW_ACCOUNT";

            // 1. Verify payment_transactions does NOT have provider_account_id column
            List<String> ptColumns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, "public", "payment_transactions", "%")) {
                while (rs.next()) {
                    ptColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(ptColumns).doesNotContain("provider_account_id");
            assertThat(ptColumns).contains("payment_instruction_id");

            // 2. Insert PaymentInstruction with MOCK_ESCROW_ACCOUNT
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at, provider_account_id) " +
                            "VALUES (?, ?, ?, 100000.00, 'INR', 'MOCK', 'SUCCESS', NOW(), NOW(), ?)")) {
                ps.setObject(1, piId);
                ps.setString(2, piRef);
                ps.setObject(3, dealId);
                ps.setString(4, expectedAccountScope);
                ps.executeUpdate();
            }

            // 3. Insert PaymentTransaction linked via payment_instruction_id
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO payment_transactions (id, transaction_reference, payment_instruction_id, provider, amount, currency, status, utr_number, created_at) " +
                            "VALUES (?, ?, ?, 'MOCK', 100000.00, 'INR', 'SUCCESS', 'UTR-MOCK-LINEAGE-01', NOW())")) {
                ps.setObject(1, txId);
                ps.setString(2, txRef);
                ps.setObject(3, piId);
                ps.executeUpdate();
            }

            // 4. Join PaymentTransaction -> PaymentInstruction and verify provider_account_id
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT pt.id AS tx_id, pt.transaction_reference, pt.payment_instruction_id, " +
                            "pi.provider, pi.provider_account_id, pi.instruction_reference " +
                            "FROM payment_transactions pt " +
                            "JOIN payment_instructions pi ON pt.payment_instruction_id = pi.id " +
                            "WHERE pt.id = ?")) {
                ps.setObject(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject("payment_instruction_id", UUID.class)).isEqualTo(piId);
                    assertThat(rs.getString("provider")).isEqualTo("MOCK");
                    assertThat(rs.getString("provider_account_id")).isEqualTo(expectedAccountScope);
                    assertThat(rs.getString("instruction_reference")).isEqualTo(piRef);
                }
            }
        }
    }
}
