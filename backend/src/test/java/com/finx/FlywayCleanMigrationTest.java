package com.finx;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIf("isPostgresAvailable")
public class FlywayCleanMigrationTest {

    static boolean isPostgresAvailable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void testCompleteFlywaySequenceAgainstCleanDatabase() throws Exception {
        boolean postgres = isPostgresAvailable();
        String jdbcUrl;
        String user;
        String pass;

        if (postgres) {
            jdbcUrl = System.getProperty("spring.datasource.url",
                    System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/finx_b06_test"));
            user = System.getProperty("spring.datasource.username",
                    System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "finx_user"));
            pass = System.getProperty("spring.datasource.password",
                    System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "finx_password"));
        } else {
            jdbcUrl = "jdbc:h2:mem:flyway_clean_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
            user = "sa";
            pass = "";
        }

        System.out.println("\n=== FLYWAY CLEAN DATABASE MIGRATION TEST ===");
        System.out.println("Configuring Flyway against clean database: " + jdbcUrl);

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, pass)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        var result = flyway.migrate();
        System.out.println("Migrations successfully executed: " + result.migrationsExecuted);
        assertTrue(result.migrationsExecuted >= 10, "Expected at least 10 migrations to execute, executed: " + result.migrationsExecuted);

        MigrationInfo[] infoList = flyway.info().all();
        for (MigrationInfo info : infoList) {
            System.out.printf("  Migration V%s: %s | State: %s | Type: %s%n",
                    info.getVersion(), info.getDescription(), info.getState(), info.getType());
            assertEquals("SUCCESS", info.getState().name(), "Migration " + info.getVersion() + " should succeed");
        }

        // Verify tables exist in the database
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass);
             Statement stmt = conn.createStatement()) {

            String product = conn.getMetaData().getDatabaseProductName();
            System.out.println("Database product: " + product);
            if (postgres) {
                assertEquals("PostgreSQL", product, "Database product must be PostgreSQL");
            }

            List<String> tables = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }

            System.out.println("\nCreated tables count: " + tables.size());
            System.out.println("Created tables: " + tables);

            // V1 tables
            assertTrue(tables.contains("users"), "V1 table 'users' must exist");
            assertTrue(tables.contains("refresh_tokens"), "V1 table 'refresh_tokens' must exist");
            assertTrue(tables.contains("audit_logs"), "V1 table 'audit_logs' must exist");

            // V2 tables
            assertTrue(tables.contains("deals"), "V2 table 'deals' must exist");

            // V3 tables
            assertTrue(tables.contains("milestones"), "V3 table 'milestones' must exist");
            assertTrue(tables.contains("deliverables"), "V3 table 'deliverables' must exist");
            assertTrue(tables.contains("payments"), "V3 table 'payments' must exist");
            assertTrue(tables.contains("escrow_accounts"), "V3 table 'escrow_accounts' must exist");
            assertTrue(tables.contains("escrow_ledger"), "V3 table 'escrow_ledger' must exist");
            assertTrue(tables.contains("disputes"), "V3 table 'disputes' must exist");
            assertTrue(tables.contains("notifications"), "V3 table 'notifications' must exist");

            // V11 tables
            assertTrue(tables.contains("organizations"), "V11 table 'organizations' must exist");
            assertTrue(tables.contains("organization_members"), "V11 table 'organization_members' must exist");
            assertTrue(tables.contains("organization_bank_accounts"), "V11 table 'organization_bank_accounts' must exist");
            assertTrue(tables.contains("kyc_profiles"), "V11 table 'kyc_profiles' must exist");
            assertTrue(tables.contains("kyb_profiles"), "V11 table 'kyb_profiles' must exist");
            assertTrue(tables.contains("verification_documents"), "V11 table 'verification_documents' must exist");

            // V12 tables
            assertTrue(tables.contains("ledger_accounts"), "V12 table 'ledger_accounts' must exist");
            assertTrue(tables.contains("journal_entries"), "V12 table 'journal_entries' must exist");
            assertTrue(tables.contains("journal_lines"), "V12 table 'journal_lines' must exist");

            // V13 tables
            assertTrue(tables.contains("payment_instructions"), "V13 table 'payment_instructions' must exist");
            assertTrue(tables.contains("payment_transactions"), "V13 table 'payment_transactions' must exist");
            assertTrue(tables.contains("settlement_instructions"), "V13 table 'settlement_instructions' must exist");
            assertTrue(tables.contains("refunds"), "V13 table 'refunds' must exist");

            // V14 tables
            assertTrue(tables.contains("reconciliation_runs"), "V14 table 'reconciliation_runs' must exist");
            assertTrue(tables.contains("reconciliation_items"), "V14 table 'reconciliation_items' must exist");
            assertTrue(tables.contains("risk_assessments"), "V14 table 'risk_assessments' must exist");
            assertTrue(tables.contains("risk_signals"), "V14 table 'risk_signals' must exist");

            // V15 tables & columns
            assertTrue(tables.contains("deal_agreements"), "V15 table 'deal_agreements' must exist");
            assertTrue(tables.contains("agreement_audit_events"), "V15 table 'agreement_audit_events' must exist");

            // V16 verification layer tables
            assertTrue(tables.contains("authorized_representatives"), "V16 table 'authorized_representatives' must exist");
            assertTrue(tables.contains("beneficial_owners"), "V16 table 'beneficial_owners' must exist");
            assertTrue(tables.contains("verification_consents"), "V16 table 'verification_consents' must exist");
            assertTrue(tables.contains("verification_events"), "V16 table 'verification_events' must exist");
            assertTrue(tables.contains("verification_checks"), "V16 table 'verification_checks' must exist");
            for (String table : List.of("risk_scores", "risk_rules", "risk_events", "risk_score_history", "risk_factors", "risk_alerts", "risk_reviews")) {
                assertTrue(tables.contains(table), "V17 risk table must exist: " + table);
            }

            assertTrue(tables.contains("milestone_reminders"), "V20 table 'milestone_reminders' must exist");
            assertTrue(tables.contains("invoices"), "V21 table 'invoices' must exist");
            assertTrue(tables.contains("invoice_extracted_fields"), "V21 table 'invoice_extracted_fields' must exist");
            assertTrue(tables.contains("invoice_checks"), "V21 table 'invoice_checks' must exist");
            assertTrue(tables.contains("invoice_reviews"), "V21 table 'invoice_reviews' must exist");

            List<String> dealColumns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "%", "%")) {
                while (rs.next()) {
                    if ("deals".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        dealColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            }
            assertTrue(dealColumns.contains("deal_reference"), "V15 column 'deal_reference' on 'deals' must exist, found: " + dealColumns);
            assertTrue(dealColumns.contains("buyer_organization_id"), "V15 column 'buyer_organization_id' on 'deals' must exist, found: " + dealColumns);

            List<String> escrowLedgerColumns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "%", "%")) {
                while (rs.next()) {
                    if ("escrow_ledger".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        escrowLedgerColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            }
            assertTrue(escrowLedgerColumns.contains("payment_instruction_id"), "V24 column 'payment_instruction_id' on 'escrow_ledger' must exist, found: " + escrowLedgerColumns);
            assertTrue(escrowLedgerColumns.contains("payment_id"), "Legacy column 'payment_id' on 'escrow_ledger' must be preserved, found: " + escrowLedgerColumns);

            // V25 engine decision framework tables
            assertTrue(tables.contains("engine_policy_versions"), "V25 table 'engine_policy_versions' must exist");
            assertTrue(tables.contains("engine_decisions"), "V25 table 'engine_decisions' must exist");

            System.out.println("ALL V1 - V25 TABLES AND COLUMNS VERIFIED IN SEQUENTIAL CLEAN ORDER!\n");
        }
    }
}
