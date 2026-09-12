package com.finx;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FlywayCleanMigrationTest {

    @Test
    public void testCompleteFlywaySequenceAgainstCleanDatabase() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:flyway_clean_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        String user = "sa";
        String pass = "";

        System.out.println("\n=== FLYWAY CLEAN DATABASE MIGRATION TEST ===");
        System.out.println("Configuring Flyway against clean in-memory database: " + jdbcUrl);

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, pass)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();

        var result = flyway.migrate();
        System.out.println("Migrations successfully executed: " + result.migrationsExecuted);
        assertTrue(result.migrationsExecuted >= 5, "Expected at least 5 migrations to execute");

        MigrationInfo[] infoList = flyway.info().all();
        for (MigrationInfo info : infoList) {
            System.out.printf("  Migration V%s: %s | State: %s | Type: %s%n",
                    info.getVersion(), info.getDescription(), info.getState(), info.getType());
            assertEquals("SUCCESS", info.getState().name(), "Migration " + info.getVersion() + " should succeed");
        }

        // Verify tables exist in the database
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass);
             Statement stmt = conn.createStatement()) {

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

            // V4 & V5 columns verification
            List<String> userColumns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "%", "%")) {
                while (rs.next()) {
                    if ("users".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        userColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            }
            assertTrue(userColumns.contains("uid"), "V5 column 'uid' on 'users' must exist, found: " + userColumns);

            List<String> dealColumns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "%", "%")) {
                while (rs.next()) {
                    if ("deals".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        dealColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            }
            assertTrue(dealColumns.contains("project_id"), "V4 column 'project_id' on 'deals' must exist, found: " + dealColumns);

            System.out.println("ALL V1, V2, V3, V4, V5 TABLES AND COLUMNS VERIFIED IN SEQUENTIAL CLEAN ORDER!\n");
        }
    }
}
