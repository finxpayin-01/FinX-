package com.finx.engine.policy.fraud;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.service.EnginePolicyVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_fraud_policy_v101_test}",
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
public class PostgresFraudPolicyTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EnginePolicyVersionService policyVersionService;

    @Autowired
    private FraudPolicyRegistrar fraudPolicyRegistrar;

    @MockBean
    private BankingProvider bankingProvider;

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("F1.1-11: Real PostgreSQL schema verification and fraud policy v1.0.1 persistence")
    void testPostgresFraudPolicyPersistenceAndIdempotency() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            assertThat(dbProduct).isEqualToIgnoringCase("PostgreSQL");
        }

        // Register default fraud policy v1.0.1
        EnginePolicyVersion policy = fraudPolicyRegistrar.registerDefaultFraudPolicy();
        assertThat(policy).isNotNull();
        assertThat(policy.getId()).isNotNull();
        assertThat(policy.getVersion()).isEqualTo("1.0.1");

        // Verify in PostgreSQL table for v1.0.1
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT engine_type, scope, organization_id, policy_key, version, content_hash, activated_at " +
                     "FROM engine_policy_versions " +
                     "WHERE engine_type = 'FRAUD' AND policy_key = 'FINX_FRAUD_DETERMINISTIC_BASE' AND version = '1.0.1'")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("engine_type")).isEqualTo("FRAUD");
            assertThat(rs.getString("scope")).isEqualTo("PLATFORM");
            assertThat(rs.getObject("organization_id")).isNull();
            assertThat(rs.getString("policy_key")).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE");
            assertThat(rs.getString("version")).isEqualTo("1.0.1");
            assertThat(rs.getString("content_hash")).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());
            assertThat(rs.getTimestamp("activated_at")).isNotNull();
        }

        // Replay registration: exactly 1 record in PostgreSQL for v1.0.1
        fraudPolicyRegistrar.registerDefaultFraudPolicy();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM engine_policy_versions " +
                     "WHERE engine_type = 'FRAUD' AND policy_key = 'FINX_FRAUD_DETERMINISTIC_BASE' AND version = '1.0.1'")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        // Re-registering v1.0.1 with conflicting hash is blocked
        assertThatThrownBy(() -> policyVersionService.registerPolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                "1.0.1",
                "conflicting_hash_0000000000000000000000000000000000000000000000000000000",
                FraudPolicyDefinition.SCHEMA_VERSION,
                "Conflict",
                "{}",
                null
        )).isInstanceOf(IllegalStateException.class);

        // Zero banking calls
        verifyNoInteractions(bankingProvider);
    }
}
