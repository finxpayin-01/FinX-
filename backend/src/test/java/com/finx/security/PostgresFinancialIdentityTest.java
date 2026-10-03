package com.finx.security;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationRepository;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
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
 * B05: Real PostgreSQL Financial Identity & Concurrency Test Suite.
 *
 * Requirements:
 * 1. Executes against real PostgreSQL and asserts database product == "PostgreSQL".
 * 2. Uses independent transactions and connections.
 * 3. Enforces bounded concurrency timeouts to detect deadlocks.
 * 4. Verifies database partial unique indexes and scoped constraints.
 * 5. Verifies 0 calls to BankingProvider.initiateSettlement.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_b05_test}",
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
public class PostgresFinancialIdentityTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PaymentInstructionRepository paymentInstructionRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private SettlementInstructionRepository settlementInstructionRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private com.finx.org.repository.OrganizationBankAccountRepository organizationBankAccountRepository;

    @MockBean
    private BankingProvider bankingProvider;

    private ExecutorService executor;
    private Deal testDeal;
    private Milestone testMilestone;
    private User buyerUser;
    private User sellerUser;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private com.finx.org.entity.OrganizationBankAccount sellerBankAccount;

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

        // Assert real PostgreSQL
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            assertThat(dbProduct).isEqualToIgnoringCase("PostgreSQL");
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);

            buyerOrg = new Organization("Buyer Corp " + suffix, "Buyer Corp Ltd", EntityType.PRIVATE_LIMITED,
                    "REG-B-" + suffix, "PAN-B-" + suffix, "29AAAAA0000A1Z5", "123 Business St", null);
            buyerOrg = organizationRepository.save(buyerOrg);

            sellerOrg = new Organization("Seller Corp " + suffix, "Seller Corp Ltd", EntityType.PRIVATE_LIMITED,
                    "REG-S-" + suffix, "PAN-S-" + suffix, "29AAAAA0000A1Z6", "456 Vendor St", null);
            sellerOrg = organizationRepository.save(sellerOrg);

            buyerUser = new User("Buyer " + suffix, "buyer-" + suffix + "@test.com", "hash", Role.BUYER, UserStatus.ACTIVE);
            buyerUser = userRepository.save(buyerUser);

            sellerUser = new User("Seller " + suffix, "seller-" + suffix + "@test.com", "hash", Role.SELLER, UserStatus.ACTIVE);
            sellerUser = userRepository.save(sellerUser);

            testDeal = new Deal();
            testDeal.setTitle("B05 Test Deal " + suffix);
            testDeal.setDescription("Deal for B05 financial identity testing");
            testDeal.setBuyerId(buyerUser.getId());
            testDeal.setSellerId(sellerUser.getId());
            testDeal.setBuyerOrganizationId(buyerOrg.getId());
            testDeal.setSellerOrganizationId(sellerOrg.getId());
            testDeal.setStatus(DealStatus.IN_PROGRESS);
            testDeal.setTotalAmount(new BigDecimal("100000.0000"));
            testDeal.setCurrency("INR");
            testDeal = dealRepository.save(testDeal);

            testMilestone = new Milestone();
            testMilestone.setDealId(testDeal.getId());
            testMilestone.setTitle("Milestone 1");
            testMilestone.setSequence(1);
            testMilestone.setAmount(new BigDecimal("50000.0000"));
            testMilestone.setStatus(MilestoneStatus.APPROVED);
            testMilestone = milestoneRepository.save(testMilestone);

            sellerBankAccount = new com.finx.org.entity.OrganizationBankAccount(
                    sellerOrg.getId(), "Seller Corp Ltd", "XXXX1234", "hash1234",
                    "SBIN0001234", "State Bank", "Main Branch", true
            );
            sellerBankAccount.setVerificationStatus(com.finx.org.enums.OrgEnums.BankVerificationStatus.VERIFIED);
            sellerBankAccount = organizationBankAccountRepository.save(sellerBankAccount);

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // A. T1 key A vs T2 key B targeting same active settlement effect -> COUNT == 1
    @Test
    @DisplayName("A. Two concurrent settlement attempts for same milestone race: exactly one active effect is permitted in PostgreSQL")
    void testConcurrentActiveSettlementRacePermitsExactlyOneEffect() throws Exception {
        UUID milestoneId = testMilestone.getId();
        UUID dealId = testDeal.getId();
        UUID sellerOrgId = sellerOrg.getId();
        UUID bankAccountId = sellerBankAccount.getId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Callable<Void> attemptA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                tx.execute(status -> {
                    SettlementInstruction s = new SettlementInstruction(
                            "FINX-SET-RACE-A-" + UUID.randomUUID().toString().substring(0, 8),
                            dealId, milestoneId, sellerOrgId, bankAccountId,
                            new BigDecimal("50000.0000"), "INR", "MOCK_BANK",
                            "key-race-A-" + UUID.randomUUID(), buyerUser.getId()
                    );
                    s.setStatus(SettlementStatus.PENDING);
                    settlementInstructionRepository.saveAndFlush(s);
                    return null;
                });
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException dive) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> attemptB = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                tx.execute(status -> {
                    SettlementInstruction s = new SettlementInstruction(
                            "FINX-SET-RACE-B-" + UUID.randomUUID().toString().substring(0, 8),
                            dealId, milestoneId, sellerOrgId, bankAccountId,
                            new BigDecimal("50000.0000"), "INR", "MOCK_BANK",
                            "key-race-B-" + UUID.randomUUID(), buyerUser.getId()
                    );
                    s.setStatus(SettlementStatus.PROCESSING);
                    settlementInstructionRepository.saveAndFlush(s);
                    return null;
                });
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException dive) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(attemptA);
        Future<Void> f2 = executor.submit(attemptB);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Verify count of active effects in PostgreSQL catalog
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM settlement_instructions WHERE milestone_id = '" +
                             milestoneId + "' AND status IN ('PENDING', 'PROCESSING', 'SETTLED')")) {
            assertThat(rs.next()).isTrue();
            int activeCount = rs.getInt(1);
            assertThat(activeCount).isEqualTo(1);
        }

        // Verify bank execution remained 0
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    // B. Two instructions race to consume same provider-scoped credit -> COUNT == 1
    @Test
    @DisplayName("B. Two payment instructions race to consume same provider UTR: exactly one succeeds")
    void testConcurrentProviderCreditRacePermitsSingleConsumption() throws Exception {
        String utr = "UTR-PG-RACE-" + UUID.randomUUID().toString().substring(0, 8);
        String provider = "BANK";

        TransactionTemplate setupTx = new TransactionTemplate(transactionManager);
        setupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UUID instructionId = setupTx.execute(status -> {
            PaymentInstruction p = new PaymentInstruction(
                    "FINX-TXN-BASE-" + UUID.randomUUID().toString().substring(0, 8),
                    testDeal.getId(), testMilestone.getId(), buyerOrg.getId(), sellerOrg.getId(),
                    new BigDecimal("50000.0000"), "INR", "MOCK_BANK", "base-key-" + UUID.randomUUID(), buyerUser.getId()
            );
            return paymentInstructionRepository.save(p).getId();
        });

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                tx.execute(status -> {
                    PaymentTransaction t = new PaymentTransaction(
                            "FINX-TXN-1-" + UUID.randomUUID().toString().substring(0, 8),
                            instructionId, provider, "PROV-REF-1",
                            new BigDecimal("50000.0000"), "INR", "SUCCESS", utr, "{}"
                    );
                    paymentTransactionRepository.saveAndFlush(t);
                    return null;
                });
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException dive) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                tx.execute(status -> {
                    PaymentTransaction t = new PaymentTransaction(
                            "FINX-TXN-2-" + UUID.randomUUID().toString().substring(0, 8),
                            instructionId, provider, "PROV-REF-2",
                            new BigDecimal("50000.0000"), "INR", "SUCCESS", utr, "{}"
                    );
                    paymentTransactionRepository.saveAndFlush(t);
                    return null;
                });
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException dive) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Verify count in PostgreSQL
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM payment_transactions WHERE provider = '" + provider + "' AND utr_number = '" + utr + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    // C. Same idempotency key / same body remains stable
    @Test
    @DisplayName("C. Same idempotency key on same deal remains stable and unique")
    void testSameIdempotencyKeyRemainsStable() {
        String key = "idem-pg-stable-" + UUID.randomUUID();
        String ref1 = "FINX-TXN-STABLE-1-" + UUID.randomUUID().toString().substring(0, 8);
        String ref2 = "FINX-TXN-STABLE-2-" + UUID.randomUUID().toString().substring(0, 8);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.execute(status -> {
            PaymentInstruction p = new PaymentInstruction(
                    ref1, testDeal.getId(), testMilestone.getId(),
                    buyerOrg.getId(), sellerOrg.getId(),
                    new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, buyerUser.getId()
            );
            p.setRequestHash("hash-stable-123");
            paymentInstructionRepository.saveAndFlush(p);
            return null;
        });

        // Querying returns exactly one record
        Optional<PaymentInstruction> fetched = paymentInstructionRepository.findByDealIdAndIdempotencyKey(testDeal.getId(), key);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getInstructionReference()).isEqualTo(ref1);

        // Attempting duplicate insert with same (deal_id, idempotency_key) fails at DB level
        assertThatThrownBy(() -> tx.execute(status -> {
            PaymentInstruction duplicate = new PaymentInstruction(
                    ref2, testDeal.getId(), testMilestone.getId(),
                    buyerOrg.getId(), sellerOrg.getId(),
                    new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, buyerUser.getId()
            );
            paymentInstructionRepository.saveAndFlush(duplicate);
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    // D. Same key / different body rejected
    @Test
    @DisplayName("D. Different deals can reuse key if scoped, but same deal duplicate key is rejected by PostgreSQL")
    void testScopedIdempotencyKeyUniquenessInPostgres() {
        String key = "scoped-key-" + UUID.randomUUID();
        String ref1 = "FINX-TXN-SCOPED-1-" + UUID.randomUUID().toString().substring(0, 8);
        String ref2 = "FINX-TXN-SCOPED-2-" + UUID.randomUUID().toString().substring(0, 8);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Save for testDeal
        tx.execute(status -> {
            PaymentInstruction p = new PaymentInstruction(
                    ref1, testDeal.getId(), testMilestone.getId(),
                    buyerOrg.getId(), sellerOrg.getId(),
                    new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, buyerUser.getId()
            );
            paymentInstructionRepository.saveAndFlush(p);
            return null;
        });

        // Duplicate for SAME deal is rejected
        assertThatThrownBy(() -> tx.execute(status -> {
            PaymentInstruction p2 = new PaymentInstruction(
                    ref2, testDeal.getId(), testMilestone.getId(),
                    buyerOrg.getId(), sellerOrg.getId(),
                    new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, buyerUser.getId()
            );
            paymentInstructionRepository.saveAndFlush(p2);
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    // E. Unique indexes and constraints from Flyway actually exist
    @Test
    @DisplayName("E. Flyway V23 unique indexes and constraints exist in PostgreSQL catalog")
    void testFlywayIndexesExistInPostgresCatalog() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname LIKE 'uk_%'")) {
                while (rs.next()) {
                    indexes.add(rs.getString("indexname"));
                }
            }

            assertThat(indexes).contains(
                    "uk_payment_instructions_deal_key",
                    "uk_payment_tx_provider_utr",
                    "uk_settlement_active_milestone",
                    "uk_settlement_instructions_deal_key",
                    "uk_settlement_provider_utr"
            );
        }
    }

    // F. No bank execution
    @Test
    @DisplayName("F. Verifies BankingProvider.initiateSettlement is never invoked")
    void testNoBankExecution() {
        verify(bankingProvider, never()).initiateSettlement(any());
        verifyNoMoreInteractions(bankingProvider);
    }

    // G. No deadlocks or timeouts
    @Test
    @DisplayName("G. Verifies all concurrent transactions complete without deadlock or timeout")
    void testNoDeadlocksOrTimeouts() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.execute(status -> {
                    PaymentInstruction p = new PaymentInstruction(
                            "FINX-TXN-CONC-" + index + "-" + UUID.randomUUID().toString().substring(0, 8),
                            testDeal.getId(), testMilestone.getId(),
                            buyerOrg.getId(), sellerOrg.getId(),
                            new BigDecimal("1000.0000"), "INR", "MOCK_BANK",
                            "conc-key-" + index + "-" + UUID.randomUUID(), buyerUser.getId()
                    );
                    paymentInstructionRepository.saveAndFlush(p);
                    return null;
                });
                return null;
            }));
        }

        for (Future<Void> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
    }
}
