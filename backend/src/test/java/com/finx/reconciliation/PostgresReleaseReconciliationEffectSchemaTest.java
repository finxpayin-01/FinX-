package com.finx.reconciliation;

import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
public class PostgresReleaseReconciliationEffectSchemaTest {

    private static final String DB_NAME = "finx_release_e8_test";

    static {
        ensureDatabaseExists(DB_NAME);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReconciliationRunRepository runRepo;
    @Autowired private ReconciliationItemRepository itemRepo;

    private ReconciliationRun testRun;

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
            // Already exists or concurrent creation
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM reconciliation_items");
        jdbc.execute("DELETE FROM reconciliation_runs");

        testRun = new ReconciliationRun();
        testRun.setRunReference("RUN-" + UUID.randomUUID().toString().substring(0, 8));
        testRun.setProvider("MOCK");
        testRun.setProviderAccountId("ACC-1");
        testRun.setCurrency("INR");
        testRun.setStatus(ReconciliationRunStatus.COMPLETED);
        testRun.setMatcherVersion("FINX_RECONCILIATION_MATCHER_V2");
        testRun.setStatementSourceHash("a".repeat(64));
        testRun.setStartTime(Instant.now().minusSeconds(60));
        testRun.setEndTime(Instant.now());
        testRun.setMatchedCount(0);
        testRun.setMismatchCount(0);
        testRun.setTotalInternalTransactions(0);
        testRun.setTotalBankTransactions(0);
        testRun = runRepo.save(testRun);
    }

    private ReconciliationItem createItem(ReconciliationEffectType effectType, UUID effectId, BigDecimal amount) {
        ReconciliationItem item = new ReconciliationItem();
        item.setReconciliationRunId(testRun.getId());
        item.setInternalEffectType(effectType);
        item.setInternalEffectId(effectId);
        item.setDirection(FinancialDirection.DEBIT);
        item.setFinxAmount(amount);
        item.setCurrency("INR");
        item.setStatus(ReconciliationItemStatus.MATCHED);
        item.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        item.setFinxTransactionReference("REF-" + UUID.randomUUID());
        return item;
    }

    @Test
    @DisplayName("1. PAYMENT_TRANSACTION accepted by chk_recon_items_internal_effect_type")
    void paymentTransactionAccepted() {
        ReconciliationItem item = createItem(ReconciliationEffectType.PAYMENT_TRANSACTION, UUID.randomUUID(), new BigDecimal("100.00"));
        item.setDirection(FinancialDirection.CREDIT);

        ReconciliationItem saved = itemRepo.saveAndFlush(item);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getInternalEffectType()).isEqualTo(ReconciliationEffectType.PAYMENT_TRANSACTION);
    }

    @Test
    @DisplayName("2. SETTLEMENT_INSTRUCTION accepted by chk_recon_items_internal_effect_type")
    void settlementInstructionAccepted() {
        ReconciliationItem item = createItem(ReconciliationEffectType.SETTLEMENT_INSTRUCTION, UUID.randomUUID(), new BigDecimal("100.00"));

        ReconciliationItem saved = itemRepo.saveAndFlush(item);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getInternalEffectType()).isEqualTo(ReconciliationEffectType.SETTLEMENT_INSTRUCTION);
    }

    @Test
    @DisplayName("3. RELEASE_INSTRUCTION accepted by chk_recon_items_internal_effect_type (V35)")
    void releaseInstructionAccepted() {
        ReconciliationItem item = createItem(ReconciliationEffectType.RELEASE_INSTRUCTION, UUID.randomUUID(), new BigDecimal("100.00"));

        ReconciliationItem saved = itemRepo.saveAndFlush(item);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getInternalEffectType()).isEqualTo(ReconciliationEffectType.RELEASE_INSTRUCTION);
    }

    @Test
    @DisplayName("4. Unsupported effect type rejected by chk_recon_items_internal_effect_type")
    void unsupportedEffectTypeRejected() {
        UUID itemId = UUID.randomUUID();
        UUID effectId = UUID.randomUUID();

        assertThatThrownBy(() -> {
            jdbc.execute(String.format(
                    "INSERT INTO reconciliation_items (id, reconciliation_run_id, internal_effect_type, internal_effect_id, direction, finx_amount, currency, status, reason_code, finx_transaction_reference, resolution_status, created_at) " +
                    "VALUES ('%s', '%s', 'ESCROW_REFUND', '%s', 'DEBIT', 100.00, 'INR', 'MATCHED', 'MATCHED_CONFIRMED', 'REF-1', 'UNRESOLVED', NOW())",
                    itemId, testRun.getId(), effectId
            ));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("5. Generic unique index uk_recon_items_run_internal_effect prevents duplicate (run, effectType, effectId)")
    void uniqueIndexPreventsDuplicates() {
        UUID effectId = UUID.randomUUID();

        ReconciliationItem item1 = createItem(ReconciliationEffectType.RELEASE_INSTRUCTION, effectId, new BigDecimal("100.00"));
        itemRepo.saveAndFlush(item1);

        ReconciliationItem item2 = createItem(ReconciliationEffectType.RELEASE_INSTRUCTION, effectId, new BigDecimal("100.00"));

        assertThatThrownBy(() -> itemRepo.saveAndFlush(item2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("6. Distinct effectIds with same effectType accepted")
    void distinctEffectIdsAccepted() {
        UUID effectId1 = UUID.randomUUID();
        UUID effectId2 = UUID.randomUUID();

        ReconciliationItem item1 = createItem(ReconciliationEffectType.RELEASE_INSTRUCTION, effectId1, new BigDecimal("100.00"));
        itemRepo.saveAndFlush(item1);

        ReconciliationItem item2 = createItem(ReconciliationEffectType.RELEASE_INSTRUCTION, effectId2, new BigDecimal("200.00"));
        itemRepo.saveAndFlush(item2);

        assertThat(itemRepo.findById(item1.getId())).isPresent();
        assertThat(itemRepo.findById(item2.getId())).isPresent();
    }
}
