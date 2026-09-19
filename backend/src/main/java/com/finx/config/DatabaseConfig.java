package com.finx.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        String databaseUrl = System.getenv("SPRING_DATASOURCE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = System.getenv("DATABASE_URL");
        }
        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = properties.getUrl();
        }

        // If the URL is in postgres:// or postgresql:// format (standard for Supabase and Render), parse it
        if (databaseUrl != null && (databaseUrl.startsWith("postgres://") || databaseUrl.startsWith("postgresql://"))) {
            try {
                log.info("Detected standard URI database connection string (Supabase/Render). Converting to JDBC...");
                URI uri = new URI(databaseUrl);

                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath(); // includes leading slash e.g. /postgres

                String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;
                if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                    jdbcUrl += "?" + uri.getQuery();
                }

                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(jdbcUrl);
                config.setDriverClassName("org.postgresql.Driver");

                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    String[] credentials = userInfo.split(":", 2);
                    config.setUsername(credentials[0]);
                    config.setPassword(credentials[1]);
                } else {
                    if (properties.getUsername() != null) config.setUsername(properties.getUsername());
                    if (properties.getPassword() != null) config.setPassword(properties.getPassword());
                }

                config.setMaximumPoolSize(10);
                config.setMinimumIdle(2);
                config.setIdleTimeout(30000);
                config.setConnectionTimeout(60000); // 60s to allow serverless waking up
                config.setInitializationFailTimeout(60000); // Wait up to 60s during startup
                config.setMaxLifetime(1200000);

                log.info("[DATABASE TARGET] PostgreSQL host={}, port={}, path={}, username={}", host, port, path, config.getUsername());
                return new HikariDataSource(config);
            } catch (Exception e) {
                log.error("Failed to parse database URI; verify datasource configuration");
            }
        }

        // Standard JDBC connection (e.g. jdbc:h2:mem:finx_test_db or jdbc:postgresql://...)
        if (databaseUrl != null && databaseUrl.startsWith("jdbc:postgresql://")) {
            String sanitized = databaseUrl.replaceAll("(?i)password=[^&;]*", "password=***");
            log.info("[DATABASE TARGET] Standard JDBC URL={}, username={}", sanitized, properties.getUsername());
        }
        return properties.initializeDataSourceBuilder().build();
    }
}
