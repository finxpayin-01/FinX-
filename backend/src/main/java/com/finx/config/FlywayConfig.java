package com.finx.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            log.info("[FLYWAY] Starting managed migration strategy...");
            DataSource dataSource = flyway.getConfiguration().getDataSource();

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                // 1. Inspect existing tables in public schema
                boolean usersExist = false;
                boolean flywayHistoryExists = false;

                try (ResultSet rs = stmt.executeQuery(
                        "SELECT LOWER(table_name) AS tname FROM information_schema.tables WHERE LOWER(table_schema) = 'public'")) {
                    while (rs.next()) {
                        String name = rs.getString("tname");
                        if ("users".equalsIgnoreCase(name)) {
                            usersExist = true;
                        }
                        if ("flyway_schema_history".equalsIgnoreCase(name)) {
                            flywayHistoryExists = true;
                        }
                    }
                }

                log.info("[FLYWAY CHECK] Existing schema status: 'users' table exists = {}, 'flyway_schema_history' exists = {}",
                        usersExist, flywayHistoryExists);

                // 2. Self-healing check:
                // If 'users' table is MISSING, but flyway_schema_history exists with version 1 or 2,
                // Flyway either baselined prematurely or failed on V2.
                // We clear the invalid records so V1 is executed to create core tables.
                if (flywayHistoryExists && !usersExist) {
                    log.warn("[FLYWAY RECOVERY] 'users' table is missing while schema history exists! " +
                            "Clearing invalid/failed history records for V1 and V2 to allow clean initialization...");
                    try {
                        int deleted = stmt.executeUpdate("DELETE FROM flyway_schema_history WHERE version IN ('1', '2') OR type = 'BASELINE'");
                        log.info("[FLYWAY RECOVERY] Cleared {} invalid history record(s)", deleted);
                    } catch (Exception ex) {
                        log.warn("[FLYWAY RECOVERY] Could not delete invalid history records: {}", ex.getMessage());
                    }
                }

                // 3. Run repair to ensure checksums and failed states are clean
                try {
                    log.info("[FLYWAY REPAIR] Running flyway.repair()...");
                    flyway.repair();
                } catch (Exception ex) {
                    log.warn("[FLYWAY REPAIR] Notice during flyway.repair(): {}", ex.getMessage());
                }

            } catch (Exception e) {
                log.warn("[FLYWAY PRE-MIGRATE] Pre-migration check notice: {}", e.getMessage());
            }

            // 4. Execute all pending migrations
            int maxRetries = 5;
            int attempt = 0;
            while (true) {
                try {
                    log.info("[FLYWAY MIGRATE] Executing flyway.migrate(), attempt {}/{}...", attempt + 1, maxRetries);
                    flyway.migrate();
                    log.info("[FLYWAY MIGRATE] Database migrations completed successfully!");
                    break;
                } catch (Exception e) {
                    attempt++;
                    log.error("[FLYWAY ERROR] Migration failed on attempt {}: {}", attempt, e.getMessage());
                    if (attempt >= maxRetries) {
                        log.error("[FLYWAY FATAL] Giving up after {} attempts. Rethrowing exception...", maxRetries);
                        throw e; // Rethrow to fail fast if DB is really down
                    }
                    try {
                        log.info("[FLYWAY RETRY] Waiting 10 seconds before retrying...");
                        Thread.sleep(10000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Flyway migration interrupted", ie);
                    }
                }
            }
        };
    }
}
