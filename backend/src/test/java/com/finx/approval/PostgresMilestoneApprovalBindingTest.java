package com.finx.approval;

import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.approval.repository.MilestoneApprovalBindingRepository;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.milestone.service.MilestoneService;
import com.finx.org.entity.Organization;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_approval_binding_test}",
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
public class PostgresMilestoneApprovalBindingTest {

    static {
        ensureDatabaseExists("finx_approval_binding_test");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @Autowired private MilestoneApprovalBindingRepository bindingRepo;
    @Autowired private MilestoneApprovalBindingService bindingService;
    @Autowired private MilestoneService milestoneService;
    @Autowired private OrganizationRepository orgRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private DealRepository dealRepo;
    @Autowired private MilestoneRepository milestoneRepo;
    @Autowired private DeliverableRepository deliverableRepo;

    @MockBean private BankingProvider bankingProvider;

    private User buyer;
    private User seller;
    private Organization buyerOrg;
    private Deal deal;
    private Milestone milestone;
    private Deliverable deliverable;

    private static void ensureDatabaseExists(String dbName) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5432), 1000);
        } catch (Exception e) {
            return;
        }
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/postgres", "finx_user", "finx_password")) {
            try (ResultSet rs = conn.getMetaData().getCatalogs()) {
                while (rs.next()) {
                    if (dbName.equalsIgnoreCase(rs.getString(1))) return;
                }
            }
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE DATABASE " + dbName);
            }
        } catch (Exception ignored) {
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);

            buyer = new User("Buyer " + suffix, "buyer-" + suffix + "@finx.invalid", "hash", Role.BUYER, UserStatus.ACTIVE);
            buyer = userRepo.save(buyer);

            seller = new User("Seller " + suffix, "seller-" + suffix + "@finx.invalid", "hash", Role.SELLER, UserStatus.ACTIVE);
            seller = userRepo.save(seller);

            buyerOrg = new Organization("BuyerOrg " + suffix, "Legal " + suffix, EntityType.PRIVATE_LIMITED,
                    "REG-" + suffix, "PAN-" + suffix, "29AAAAA0000A1Z5", "123 Street", buyer.getId());
            buyerOrg = orgRepo.save(buyerOrg);

            deal = new Deal();
            deal.setTitle("Deal " + suffix);
            deal.setDescription("Desc");
            deal.setBuyerId(buyer.getId());
            deal.setSellerId(seller.getId());
            deal.setBuyerOrganizationId(buyerOrg.getId());
            deal.setTotalAmount(new BigDecimal("10000.00"));
            deal.setCurrency("INR");
            deal.setStatus(DealStatus.IN_PROGRESS);
            deal = dealRepo.save(deal);

            milestone = new Milestone(deal.getId(), "Milestone " + suffix, "Desc", 1, new BigDecimal("5000.00"), "INR", Instant.now().plusSeconds(86400));
            milestone.setStatus(MilestoneStatus.SUBMITTED);
            milestone = milestoneRepo.save(milestone);

            deliverable = new Deliverable(milestone.getId(), seller.getId(), "Doc", "Spec", "https://finx.local/doc.pdf");
            deliverable.setStatus(DeliverableStatus.PENDING);
            deliverable.setSubmittedAt(Instant.now().minusSeconds(100));
            deliverable = deliverableRepo.save(deliverable);

            return null;
        });
    }

    private String validSha256(char c) {
        return String.valueOf(c).repeat(64);
    }

    private int insertBindingRow(UUID id, UUID orgId, UUID dealId, UUID milestoneId, UUID approvedBy, String authority,
                                 String mFp, String dFp, String aFp, Instant approvedAt) {
        return jdbc.update(
                "INSERT INTO milestone_approval_bindings (id, organization_id, deal_id, milestone_id, approved_by, approval_authority, milestone_fingerprint, deliverables_fingerprint, approval_fingerprint, approved_at, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, orgId, dealId, milestoneId, approvedBy, authority,
                mFp, dFp, aFp,
                Timestamp.from(approvedAt), Timestamp.from(approvedAt)
        );
    }

    // 1. Table exists
    @Test
    void tableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'milestone_approval_bindings'",
                Integer.class
        );
        assertThat(count).isNotNull().isEqualTo(1);
    }

    // 2. Valid insert
    @Test
    void validInsert() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        int rows = insertBindingRow(id, buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now);
        assertThat(rows).isEqualTo(1);

        Optional<MilestoneApprovalBinding> found = bindingRepo.findByMilestoneId(milestone.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
        assertThat(found.get().getApprovalAuthority()).isEqualTo(MilestoneApprovalAuthority.BUYER);
    }

    // 3. Organization FK
    @Test
    void organizationFkEnforced() {
        UUID nonExistentOrg = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), nonExistentOrg, deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 4. Deal FK
    @Test
    void dealFkEnforced() {
        UUID nonExistentDeal = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), nonExistentDeal, milestone.getId(), buyer.getId(), "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 5. Milestone FK
    @Test
    void milestoneFkEnforced() {
        UUID nonExistentMilestone = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), nonExistentMilestone, buyer.getId(), "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 6. User FK
    @Test
    void userFkEnforced() {
        UUID nonExistentUser = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), nonExistentUser, "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 7. Valid authority constraint
    @Test
    void validAuthorityConstraint() {
        for (String auth : List.of("BUYER", "PROJECT_MANAGER", "ADMIN")) {
            Milestone m = new Milestone(deal.getId(), "M-" + auth, "Desc", 2, new BigDecimal("1000.00"), "INR", null);
            m.setStatus(MilestoneStatus.SUBMITTED);
            m = milestoneRepo.save(m);

            Instant now = Instant.now();
            int inserted = insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), m.getId(), buyer.getId(), auth,
                    validSha256('a'), validSha256('b'), validSha256('c'), now);
            assertThat(inserted).isEqualTo(1);
        }
    }

    // 8. Invalid authority rejected
    @Test
    void invalidAuthorityRejected() {
        Instant now = Instant.now();
        for (String invalidAuth : List.of("FINANCE", "SELLER", "SYSTEM", "UNKNOWN")) {
            assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), invalidAuth,
                    validSha256('a'), validSha256('b'), validSha256('c'), now))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // 9. Malformed hashes rejected
    @Test
    void malformedHashesRejected() {
        Instant now = Instant.now();
        // Short hash (63 chars)
        assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                "a".repeat(63), validSha256('b'), validSha256('c'), now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Uppercase hash
        assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('A'), validSha256('b'), validSha256('c'), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 10. Duplicate milestone binding rejected
    @Test
    void duplicateMilestoneBindingRejected() {
        Instant now = Instant.now();
        insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now);

        // Attempt second insert for the exact same milestone
        assertThatThrownBy(() -> insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('x'), validSha256('y'), validSha256('z'), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 11. Delete referenced organization blocked
    @Test
    void deleteReferencedOrganizationBlocked() {
        Instant now = Instant.now();
        insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM organizations WHERE id = ?", buyerOrg.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 12. Delete referenced milestone blocked
    @Test
    void deleteReferencedMilestoneBlocked() {
        Instant now = Instant.now();
        insertBindingRow(UUID.randomUUID(), buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('a'), validSha256('b'), validSha256('c'), now);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM milestones WHERE id = ?", milestone.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 13. Immutable historical row can be read
    @Test
    void immutableHistoricalRowCanBeRead() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        insertBindingRow(id, buyerOrg.getId(), deal.getId(), milestone.getId(), buyer.getId(), "BUYER",
                validSha256('1'), validSha256('2'), validSha256('3'), now);

        MilestoneApprovalBinding loaded = bindingRepo.findById(id).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getOrganizationId()).isEqualTo(buyerOrg.getId());
        assertThat(loaded.getDealId()).isEqualTo(deal.getId());
        assertThat(loaded.getMilestoneId()).isEqualTo(milestone.getId());
        assertThat(loaded.getApprovedBy()).isEqualTo(buyer.getId());
        assertThat(loaded.getApprovalAuthority()).isEqualTo(MilestoneApprovalAuthority.BUYER);
        assertThat(loaded.getMilestoneFingerprint()).isEqualTo(validSha256('1'));
        assertThat(loaded.getDeliverablesFingerprint()).isEqualTo(validSha256('2'));
        assertThat(loaded.getApprovalFingerprint()).isEqualTo(validSha256('3'));
    }

    // 14. No cascade deletion
    @Test
    void noCascadeDeletion() {
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                "WHERE constraint_name LIKE 'fk_milestone_approval_bindings_%' AND delete_rule = 'CASCADE'",
                Integer.class
        );
        assertThat(rowCount).isEqualTo(0);
    }

    // PART U — APPROVAL LOCKING TEST
    @Test
    void concurrentApprovalLockingPreventsDeadlockAndDuplicates() throws Exception {
        UserPrincipal principal = UserPrincipal.create(buyer);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    milestoneService.approveMilestone(milestone.getId(), principal);
                    successCount.incrementAndGet();
                } catch (BadRequestException | DataIntegrityViolationException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly one succeeds, other fails idempotently observing terminal state
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        // Exactly one binding created in DB
        Integer bindingsCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM milestone_approval_bindings WHERE milestone_id = ?",
                Integer.class,
                milestone.getId()
        );
        assertThat(bindingsCount).isEqualTo(1);

        // Milestone is in terminal APPROVED state
        Milestone m = milestoneRepo.findById(milestone.getId()).orElseThrow();
        assertThat(m.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }
}
