package com.finx.reconciliation;

import com.finx.banking.dto.BankStatementDirection;
import com.finx.banking.dto.BankStatementEntry;
import com.finx.banking.dto.BankStatementFinality;
import com.finx.banking.dto.BankStatementQuery;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.common.enums.Role;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.service.BankStatementEvidenceFingerprint;
import com.finx.reconciliation.service.BankStatementIngestionService;
import com.finx.reconciliation.service.BankStatementPersistenceService;
import com.finx.security.service.UserPrincipal;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("isPostgresAvailable")
public class PostgresBankStatementIngestionTest {

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
    static void initDatabase() {
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
        assertThat(result.targetSchemaVersion).isEqualTo("26");
    }

    @Autowired
    private BankStatementImportRepository importRepository;

    @Autowired
    private BankStatementLineRepository lineRepository;

    @Autowired
    private BankStatementPersistenceService persistenceService;

    private MockBankingProvider mockProvider;
    private BankStatementIngestionService ingestionService;
    private UserPrincipal adminPrincipal;

    @BeforeEach
    void setUp() throws SQLException {
        UUID adminId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, USER, PASS)) {
            conn.createStatement().execute("DELETE FROM bank_statement_lines");
            conn.createStatement().execute("DELETE FROM bank_statement_imports");
            conn.createStatement().execute("DELETE FROM reconciliation_items");
            conn.createStatement().execute("DELETE FROM reconciliation_runs");
            conn.createStatement().execute("DELETE FROM users WHERE email = 'admin_r2_test@finx.com'");

            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, name, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?, 'Admin R2', 'admin_r2_test@finx.com', 'hashedpass', 'ADMIN', 'ACTIVE', NOW(), NOW())")) {
                ps.setObject(1, adminId);
                ps.executeUpdate();
            }
        }

        mockProvider = new MockBankingProvider("test-secret-key-32-chars-length!!");
        mockProvider.clearDurableStatementEntries();
        ingestionService = new BankStatementIngestionService(mockProvider, persistenceService);
        adminPrincipal = new UserPrincipal(
                adminId,
                "Admin R2",
                "admin_r2_test@finx.com",
                "hashedpass",
                Role.ADMIN,
                com.finx.common.enums.UserStatus.ACTIVE,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(Role.ADMIN.getAuthority()))
        );
    }

    @Test
    @Order(1)
    @DisplayName("1. Real PostgreSQL connected and Flyway V26 verified")
    void testPostgresEngineVerified() throws SQLException {
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, USER, PASS)) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }
    }

    @Test
    @Order(2)
    @DisplayName("2-6. Ingest statement, persist 1 import, N lines, sourceHash and fingerprints valid, exact replay no-op")
    void testBasicIngestionAndIdempotency() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = LocalDate.now();

        mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-PG-001")
                .transactionReference("TXN-PG-001")
                .utrNumber("UTR-PG-001")
                .amount(new BigDecimal("15000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(2, ChronoUnit.HOURS))
                .valueDate(today)
                .build());

        mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-PG-002")
                .transactionReference("TXN-PG-002")
                .utrNumber("UTR-PG-002")
                .amount(new BigDecimal("7500.00"))
                .currency("INR")
                .direction(BankStatementDirection.DEBIT)
                .providerStatus("PENDING")
                .finality(BankStatementFinality.PENDING)
                .transactionTime(now.minus(1, ChronoUnit.HOURS))
                .valueDate(today)
                .build());

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(1, ChronoUnit.DAYS))
                .windowEnd(now)
                .pageSize(10)
                .build();

        // 1st ingestion
        BankStatementImport imp1 = ingestionService.ingestStatement(query, adminPrincipal);
        assertThat(imp1).isNotNull();
        assertThat(imp1.getStatus()).isEqualTo(BankStatementImportStatus.COMPLETED);
        assertThat(imp1.getTotalLines()).isEqualTo(2);
        assertThat(imp1.getSourceHash()).matches("^[a-f0-9]{64}$");

        assertThat(importRepository.count()).isEqualTo(1);
        assertThat(lineRepository.count()).isEqualTo(2);

        List<BankStatementLine> lines = lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp1.getId());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getLineFingerprint()).matches("^[a-f0-9]{64}$");
        assertThat(lines.get(1).getLineFingerprint()).matches("^[a-f0-9]{64}$");

        // 2nd ingestion (exact replay)
        BankStatementImport imp2 = ingestionService.ingestStatement(query, adminPrincipal);
        assertThat(imp2.getId()).isEqualTo(imp1.getId());

        // Count unchanged
        assertThat(importRepository.count()).isEqualTo(1);
        assertThat(lineRepository.count()).isEqualTo(2);
    }

    @Test
    @Order(3)
    @DisplayName("7. Paged statement across multiple cursors persists completely once")
    void testPagedStatementIngestion() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 1; i <= 7; i++) {
            mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                    .providerLineId(String.format("LINE-PAGE-%03d", i))
                    .transactionReference(String.format("TXN-PAGE-%03d", i))
                    .utrNumber(String.format("UTR-PAGE-%03d", i))
                    .amount(BigDecimal.valueOf(i * 1000))
                    .currency("INR")
                    .direction(BankStatementDirection.CREDIT)
                    .finality(BankStatementFinality.FINAL_SUCCESS)
                    .transactionTime(now.minus(10 - i, ChronoUnit.MINUTES))
                    .valueDate(LocalDate.now())
                    .build());
        }

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(1, ChronoUnit.DAYS))
                .windowEnd(now)
                .pageSize(3) // 3 pages (3 + 3 + 1)
                .build();

        BankStatementImport imp = ingestionService.ingestStatement(query, adminPrincipal);
        assertThat(imp.getTotalLines()).isEqualTo(7);
        assertThat(importRepository.count()).isEqualTo(1);
        assertThat(lineRepository.count()).isEqualTo(7);
    }

    @Test
    @Order(4)
    @DisplayName("8, 9. Duplicate providerLineId or fingerprint in snapshot fails with 0 rows persisted")
    void testDuplicateInSnapshotFailsCleanly() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-DUP-1")
                .transactionReference("TXN-DUP-1")
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(1, ChronoUnit.HOURS))
                .build());

        mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-DUP-1") // Same line ID!
                .transactionReference("TXN-DUP-2")
                .amount(new BigDecimal("2000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(30, ChronoUnit.MINUTES))
                .build());

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(1, ChronoUnit.DAYS))
                .windowEnd(now)
                .pageSize(10)
                .build();

        assertThatThrownBy(() -> ingestionService.ingestStatement(query, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate providerLineId");

        assertThat(importRepository.count()).isEqualTo(0);
        assertThat(lineRepository.count()).isEqualTo(0);
    }

    @Test
    @Order(5)
    @DisplayName("10-13. Field preservation: UNKNOWN finality, FINAL_SUCCESS, CREDIT/DEBIT, transaction/value dates")
    void testFieldPreservationInPostgres() {
        Instant txnTime = Instant.parse("2026-10-06T14:30:00Z");
        LocalDate valueDate = LocalDate.parse("2026-10-06");

        mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-FIELDS-1")
                .transactionReference("TXN-F-1")
                .utrNumber("UTR-F-1")
                .amount(new BigDecimal("9999.50"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("IN_SETTLEMENT")
                .finality(BankStatementFinality.UNKNOWN)
                .transactionTime(txnTime)
                .valueDate(valueDate)
                .build());

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(txnTime.minus(1, ChronoUnit.DAYS))
                .windowEnd(txnTime.plus(1, ChronoUnit.DAYS))
                .pageSize(10)
                .build();

        BankStatementImport imp = ingestionService.ingestStatement(query, adminPrincipal);
        List<BankStatementLine> lines = lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId());
        assertThat(lines).hasSize(1);

        BankStatementLine line = lines.get(0);
        assertThat(line.getNormalizedStatus()).isEqualTo(BankStatementLineStatus.UNKNOWN);
        assertThat(line.getDirection()).isEqualTo(FinancialDirection.CREDIT);
        assertThat(line.getAmount()).isEqualByComparingTo(new BigDecimal("9999.5000"));
        assertThat(line.getTransactionTime()).isEqualTo(txnTime);
        assertThat(line.getValueDate()).isEqualTo(valueDate);
    }

    @Test
    @Order(6)
    @DisplayName("14, 15. Zero reconciliation runs or items created during ingestion")
    void testZeroReconciliationRunsCreated() throws SQLException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-ZERO-REC")
                .transactionReference("TXN-ZERO-REC")
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now)
                .build());

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(1, ChronoUnit.DAYS))
                .windowEnd(now.plus(1, ChronoUnit.DAYS))
                .pageSize(10)
                .build();

        ingestionService.ingestStatement(query, adminPrincipal);

        try (Connection conn = DriverManager.getConnection(testJdbcUrl, USER, PASS)) {
            ResultSet rsRuns = conn.createStatement().executeQuery("SELECT COUNT(*) FROM reconciliation_runs");
            assertThat(rsRuns.next()).isTrue();
            assertThat(rsRuns.getLong(1)).isEqualTo(0);

            ResultSet rsItems = conn.createStatement().executeQuery("SELECT COUNT(*) FROM reconciliation_items");
            assertThat(rsItems.next()).isTrue();
            assertThat(rsItems.getLong(1)).isEqualTo(0);
        }
    }

    @Test
    @Order(7)
    @DisplayName("16-18. Concurrent ingestion of exact same snapshot results in exactly 1 import and N lines")
    void testConcurrentIngestionIdempotency() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 1; i <= 3; i++) {
            mockProvider.addDurableStatementEntry(BankStatementEntry.builder()
                    .providerLineId(String.format("LINE-CONC-%03d", i))
                    .transactionReference(String.format("TXN-CONC-%03d", i))
                    .amount(BigDecimal.valueOf(i * 500))
                    .currency("INR")
                    .direction(BankStatementDirection.CREDIT)
                    .finality(BankStatementFinality.FINAL_SUCCESS)
                    .transactionTime(now.minus(i, ChronoUnit.MINUTES))
                    .build());
        }

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(1, ChronoUnit.DAYS))
                .windowEnd(now)
                .pageSize(10)
                .build();

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        Callable<BankStatementImport> task = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return ingestionService.ingestStatement(query, adminPrincipal);
        };

        Future<BankStatementImport> f1 = executor.submit(task);
        Future<BankStatementImport> f2 = executor.submit(task);

        BankStatementImport r1 = f1.get(10, TimeUnit.SECONDS);
        BankStatementImport r2 = f2.get(10, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r1.getId()).isEqualTo(r2.getId());

        assertThat(importRepository.count()).isEqualTo(1);
        assertThat(lineRepository.count()).isEqualTo(3);
    }
}
