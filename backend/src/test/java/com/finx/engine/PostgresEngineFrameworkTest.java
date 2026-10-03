package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.engine.service.EngineDecisionService;
import com.finx.engine.service.EnginePolicyVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Real PostgreSQL Integration & Concurrency Test for the Common Engine Framework.
 * Verifies Flyway V25 schema, partial unique indexes, concurrent deduplication,
 * tenant isolation, and zero financial side effects.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_engine_framework_test}",
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
public class PostgresEngineFrameworkTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EngineDecisionService decisionService;

    @Autowired
    private EnginePolicyVersionService policyVersionService;

    @Autowired
    private EngineDecisionRepository decisionRepository;

    @Autowired
    private EnginePolicyVersionRepository policyVersionRepository;

    @MockBean
    private BankingProvider bankingProvider;

    private ExecutorService executor;

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        executor = Executors.newFixedThreadPool(4);

        // Verify connected to real PostgreSQL
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            assertThat(dbProduct).isEqualToIgnoringCase("PostgreSQL");
        }
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Section 48: Flyway V25 tables, partial indexes, and constraints exist in PostgreSQL catalog")
    void testFlywayV25SchemaAndConstraintsInPostgres() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Verify tables exist
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename LIKE 'engine_%'")) {
                while (rs.next()) {
                    tables.add(rs.getString("tablename"));
                }
            }
            assertThat(tables).contains("engine_policy_versions", "engine_decisions");

            // 2. Verify unique indexes exist
            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND (indexname LIKE 'uk_engine_%' OR indexname LIKE 'idx_engine_%')")) {
                while (rs.next()) {
                    indexes.add(rs.getString("indexname"));
                }
            }
            assertThat(indexes).contains(
                    "uk_engine_policy_platform",
                    "uk_engine_policy_org",
                    "uk_engine_decisions_logical_identity",
                    "idx_engine_decisions_org_evaluated",
                    "idx_engine_decisions_org_subject",
                    "idx_engine_decisions_org_engine_subject",
                    "idx_engine_decisions_correlation",
                    "idx_engine_decisions_evaluation"
            );

            // 3. Verify check constraints exist
            List<String> constraints = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT conname FROM pg_constraint WHERE conname LIKE 'chk_engine_%'")) {
                while (rs.next()) {
                    constraints.add(rs.getString("conname"));
                }
            }
            assertThat(constraints).contains(
                    "chk_engine_policy_scope",
                    "chk_engine_policy_engine_type",
                    "chk_engine_policy_scope_org",
                    "chk_engine_decisions_decision",
                    "chk_engine_decisions_outcome",
                    "chk_engine_decisions_engine_type",
                    "chk_engine_decisions_error_invariant",
                    "chk_engine_decisions_valid_until"
            );
        }
    }

    @Test
    @DisplayName("Section 49: PostgreSQL concurrent duplicate evaluation race produces EXACTLY ONE EngineDecision record")
    void testConcurrentDuplicateEvaluationRaceInPostgres() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Instant now = Instant.now();

        RecordEngineDecisionCommand cmd = RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(new BigDecimal("88.5000"))
                .reasonCodes(List.of("LOW_RISK_APPROVED"))
                .evaluationId(evalId)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build();

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                decisionService.recordDecision(cmd);
                successCount.incrementAndGet();
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                decisionService.recordDecision(cmd);
                successCount.incrementAndGet();
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        assertThat(successCount.get() + conflictCount.get()).isEqualTo(2);
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);

        // Verify count of records in PostgreSQL is EXACTLY 1
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM engine_decisions WHERE evaluation_id = '" + evalId +
                             "' AND engine_type = 'RISK' AND subject_id = '" + subjectId + "'")) {
            assertThat(rs.next()).isTrue();
            int count = rs.getInt(1);
            assertThat(count).isEqualTo(1);
        }

        // Sequential replay also produces no new records and returns existing
        EngineDecision replayed = decisionService.recordDecision(cmd);
        assertThat(replayed).isNotNull();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM engine_decisions WHERE evaluation_id = '" + evalId +
                             "' AND engine_type = 'RISK' AND subject_id = '" + subjectId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Section 50: PostgreSQL tenant query isolation prevents cross-tenant data leakage")
    void testPostgresTenantQueryIsolation() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID evalA = UUID.randomUUID();
        UUID evalB = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        Instant now = Instant.now();

        RecordEngineDecisionCommand cmdA = RecordEngineDecisionCommand.builder()
                .organizationId(orgA)
                .engineType(EngineType.DOCUMENT)
                .subjectType("INVOICE")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .evaluationId(evalA)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build();

        RecordEngineDecisionCommand cmdB = RecordEngineDecisionCommand.builder()
                .organizationId(orgB)
                .engineType(EngineType.DOCUMENT)
                .subjectType("INVOICE")
                .subjectId(subjectId)
                .decision(DecisionStatus.BLOCK)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .evaluationId(evalB)
                .correlationId(corrId)
                .evaluatedAt(now)
                .build();

        decisionService.recordDecision(cmdA);
        decisionService.recordDecision(cmdB);

        // Query Org A scoped
        Optional<EngineDecision> foundA = decisionService.findLatestForSubjectAndEngine(
                orgA, EngineType.DOCUMENT, "INVOICE", subjectId
        );
        assertThat(foundA).isPresent();
        assertThat(foundA.get().getOrganizationId()).isEqualTo(orgA);
        assertThat(foundA.get().getDecision()).isEqualTo(DecisionStatus.ALLOW);

        // Query Org B scoped
        Optional<EngineDecision> foundB = decisionService.findLatestForSubjectAndEngine(
                orgB, EngineType.DOCUMENT, "INVOICE", subjectId
        );
        assertThat(foundB).isPresent();
        assertThat(foundB.get().getOrganizationId()).isEqualTo(orgB);
        assertThat(foundB.get().getDecision()).isEqualTo(DecisionStatus.BLOCK);

        // Cross-query: Org A querying Org B's evaluation returns empty list
        List<EngineDecision> crossQuery = decisionService.findByEvaluation(orgA, evalB);
        assertThat(crossQuery).isEmpty();
    }

    @Test
    @DisplayName("Section 51 & 52: Policy version platform vs org uniqueness in PostgreSQL")
    void testPostgresPolicyVersionScopeAndUniqueness() {
        String policyKey = "RISK_TIER_RULES_" + UUID.randomUUID().toString().substring(0, 8);
        String version = "v1.0.0";
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        // 1. Register platform policy
        EnginePolicyVersion platform = policyVersionService.registerPolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RISK, policyKey, version,
                "sha256_platform_hash", "1.0", "Platform default", "{}", null
        );
        assertThat(platform.getId()).isNotNull();

        // 2. Register org-specific override for same key and version
        EnginePolicyVersion orgAPolicy = policyVersionService.registerPolicyVersion(
                orgA, PolicyScope.ORGANIZATION, EngineType.RISK, policyKey, version,
                "sha256_orgA_hash", "1.0", "Org A override", "{}", null
        );
        assertThat(orgAPolicy.getId()).isNotNull();
        assertThat(orgAPolicy.getId()).isNotEqualTo(platform.getId());

        // 3. Register for orgB for same key and version
        EnginePolicyVersion orgBPolicy = policyVersionService.registerPolicyVersion(
                orgB, PolicyScope.ORGANIZATION, EngineType.RISK, policyKey, version,
                "sha256_orgB_hash", "1.0", "Org B override", "{}", null
        );
        assertThat(orgBPolicy.getId()).isNotNull();

        // 4. Duplicate platform policy with conflicting hash is rejected
        assertThatThrownBy(() -> policyVersionService.registerPolicyVersion(
                null, PolicyScope.PLATFORM, EngineType.RISK, policyKey, version,
                "sha256_CONFLICTING_hash", "1.0", "Conflict", "{}", null
        )).isInstanceOf(IllegalStateException.class);

        // 5. Duplicate orgA policy with conflicting hash is rejected
        assertThatThrownBy(() -> policyVersionService.registerPolicyVersion(
                orgA, PolicyScope.ORGANIZATION, EngineType.RISK, policyKey, version,
                "sha256_CONFLICTING_hash", "1.0", "Conflict", "{}", null
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Section 47: Persisting RELEASE ALLOW in PostgreSQL produces ZERO banking or ledger writes")
    void testPostgresReleaseDecisionZeroFinancialSideEffects() throws SQLException {
        UUID orgId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();

        int settlementCountBefore;
        int journalCountBefore;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM settlement_instructions")) {
                rs.next();
                settlementCountBefore = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM journal_entries")) {
                rs.next();
                journalCountBefore = rs.getInt(1);
            }
        }

        RecordEngineDecisionCommand releaseCmd = RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RELEASE)
                .subjectType("MILESTONE")
                .subjectId(milestoneId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .explanation("Pre-release evaluation completed - recommendation only")
                .evaluationId(evalId)
                .correlationId(corrId)
                .evaluatedAt(Instant.now())
                .build();

        EngineDecision decision = decisionService.recordDecision(releaseCmd);
        assertThat(decision.getId()).isNotNull();

        // Verify banking provider was NEVER called
        verify(bankingProvider, never()).initiateSettlement(any());

        // Verify database counts for financial records remain unchanged (delta = 0)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM settlement_instructions")) {
                rs.next();
                int settlementCountAfter = rs.getInt(1);
                assertThat(settlementCountAfter).isEqualTo(settlementCountBefore);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM journal_entries")) {
                rs.next();
                int journalCountAfter = rs.getInt(1);
                assertThat(journalCountAfter).isEqualTo(journalCountBefore);
            }
        }
    }
}
