package com.finx.trust;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.*;
import java.util.UUID;

/** Repeats the API/rollback/JSON persistence suite on PostgreSQL, not an H2 compatibility mode. */
@EnabledIfEnvironmentVariable(named="FINX_TRUST_TEST_POSTGRES_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1):.+")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TrustPostgresIntegrationTest extends TrustEngineIntegrationTest {
    private static final String SCHEMA="trust_app_"+UUID.randomUUID().toString().replace("-","");
    private static String url() { return System.getenv("FINX_TRUST_TEST_POSTGRES_URL"); }
    private static String user() { return System.getenv().getOrDefault("FINX_TRUST_TEST_POSTGRES_USER","finx_trust_test"); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws SQLException {
        try(var connection=DriverManager.getConnection(url(),user(),""); var sql=connection.createStatement()) { sql.execute("CREATE SCHEMA "+SCHEMA); }
        registry.add("spring.datasource.url",()->url()+"?currentSchema="+SCHEMA);
        registry.add("spring.datasource.username",TrustPostgresIntegrationTest::user);
        registry.add("spring.datasource.password",()->"");
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @AfterAll static void cleanup() throws SQLException {
        try(var connection=DriverManager.getConnection(url(),user(),""); var sql=connection.createStatement()) { sql.execute("DROP SCHEMA IF EXISTS "+SCHEMA+" CASCADE"); }
    }
}
