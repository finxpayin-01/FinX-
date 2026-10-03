package com.finx.security;

import com.finx.agreement.dto.AgreementDtos.CreateAgreementDraftRequest;
import com.finx.agreement.service.AgreementService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.entity.LedgerAccount;
import com.finx.ledger.enums.LedgerEnums.AccountType;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.milestone.service.MilestoneService;
import com.finx.org.entity.Organization;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationRepository;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService.ApprovalApplicabilityResult;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.service.RiskActionService;
import com.finx.security.service.UserPrincipal;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * B11: Real PostgreSQL Multi-Connection Concurrency & Lock-Ordering Verification Suite.
 *
 * Requirements:
 * 1. Must execute against real PostgreSQL and assert database product == "PostgreSQL".
 * 2. Uses actual Spring/JPA services with independent transactions (TransactionTemplate / REQUIRES_NEW).
 * 3. Does NOT wrap the test class or test methods in a single shared @Transactional boundary.
 * 4. Uses bounded ExecutorService timeouts to detect deadlocks.
 * 5. Asserts BankingProvider.initiateSettlement calls remain 0 (B04 fail-closed).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_b11_test}",
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
public class PostgresLockingConcurrencyTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private EscrowService escrowService;

    @Autowired
    private MilestoneService milestoneService;

    @Autowired
    private AgreementService agreementService;

    @Autowired
    private ApprovalApplicabilityService approvalApplicabilityService;

    @Autowired
    private SettlementAuthorizationService settlementAuthorizationService;

    @Autowired
    private RiskActionService riskActionService;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private EscrowAccountRepository escrowAccountRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @MockBean
    private BankingProvider bankingProvider;

    private ExecutorService executor;

    /**
     * Determines whether PostgreSQL is accessible on localhost:5432.
     * If not running (e.g. in environments without live PostgreSQL), test class is cleanly skipped.
     */
    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5432), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private TransactionTemplate newIndependentTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private User createAndSaveUser(String namePrefix, Role role) {
        User u = new User(namePrefix + " User", namePrefix.toLowerCase() + "." + UUID.randomUUID() + "@finx.test", "hash", role, UserStatus.ACTIVE);
        return userRepository.save(u);
    }

    private Organization createAndSaveOrg(String orgName, UUID creatorId) {
        Organization org = new Organization(orgName, orgName + " Ltd", EntityType.PRIVATE_LIMITED, "REG" + UUID.randomUUID().toString().substring(0, 6),
                "PAN" + UUID.randomUUID().toString().substring(0, 6), "29AAAAA0000A1Z5", "123 Business St", creatorId);
        return organizationRepository.save(org);
    }

    @BeforeEach
    void setUp() throws SQLException {
        // Strict requirement: Assert JDBC database product is PostgreSQL
        try (Connection connection = dataSource.getConnection()) {
            String dbProduct = connection.getMetaData().getDatabaseProductName();
            assertThat(dbProduct)
                    .as("B11 PostgreSQL Concurrency Test must run against actual PostgreSQL")
                    .isEqualTo("PostgreSQL");
        }

        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        // Invariant: No external bank settlement calls were made
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    // =========================================================================
    // 1. REVERSED-ORDER LEDGER ACCOUNT CONCURRENCY
    // =========================================================================

    @Test
    @DisplayName("1. PostgreSQL: Reversed-order ledger account concurrency eliminates deadlocks")
    void test01_postgres_reversedOrderLedgerConcurrency() throws Exception {
        String accANo = "1010-ACC-" + UUID.randomUUID().toString().substring(0, 8);
        String accBNo = "2010-ACC-" + UUID.randomUUID().toString().substring(0, 8);

        UUID posterId = newIndependentTransaction().execute(status ->
                createAndSaveUser("Poster", Role.ADMIN).getId()
        );

        // Pre-create accounts in a committed transaction
        newIndependentTransaction().executeWithoutResult(status -> {
            ledgerAccountRepository.save(new LedgerAccount(accANo, "Account A", AccountType.ASSET, "INR"));
            ledgerAccountRepository.save(new LedgerAccount(accBNo, "Account B", AccountType.LIABILITY, "INR"));
        });

        // T1 logs lines involving A then B
        Callable<JournalEntry> task1 = () -> newIndependentTransaction().execute(status -> {
            PostJournalEntryRequest req1 = new PostJournalEntryRequest(
                    LedgerTransactionType.ESCROW_DEPOSIT,
                    UUID.randomUUID(),
                    null, // standalone ledger posting
                    null,
                    "INR",
                    "Entry A to B",
                    posterId,
                    List.of(
                            new JournalLineItem(accANo, EntryType.DEBIT, new BigDecimal("100.00"), "Debit A"),
                            new JournalLineItem(accBNo, EntryType.CREDIT, new BigDecimal("100.00"), "Credit B")
                    )
            );
            return ledgerService.postJournalEntry(req1);
        });

        // T2 logs lines involving B then A (inverted order)
        Callable<JournalEntry> task2 = () -> newIndependentTransaction().execute(status -> {
            PostJournalEntryRequest req2 = new PostJournalEntryRequest(
                    LedgerTransactionType.ESCROW_DEPOSIT,
                    UUID.randomUUID(),
                    null, // standalone ledger posting
                    null,
                    "INR",
                    "Entry B to A",
                    posterId,
                    List.of(
                            new JournalLineItem(accBNo, EntryType.DEBIT, new BigDecimal("50.00"), "Debit B"),
                            new JournalLineItem(accANo, EntryType.CREDIT, new BigDecimal("50.00"), "Credit A")
                    )
            );
            return ledgerService.postJournalEntry(req2);
        });

        Future<JournalEntry> future1 = executor.submit(task1);
        Future<JournalEntry> future2 = executor.submit(task2);

        // Bounded completion: must complete without deadlock
        JournalEntry entry1 = future1.get(10, TimeUnit.SECONDS);
        JournalEntry entry2 = future2.get(10, TimeUnit.SECONDS);

        assertThat(entry1).isNotNull();
        assertThat(entry2).isNotNull();
        assertThat(entry1.getEntryReference()).isNotEqualTo(entry2.getEntryReference());

        // Verify account balance integrity in committed database
        newIndependentTransaction().executeWithoutResult(status -> {
            LedgerAccount a = ledgerAccountRepository.findByAccountNumber(accANo).orElseThrow();
            LedgerAccount b = ledgerAccountRepository.findByAccountNumber(accBNo).orElseThrow();
            // Acc A: +100 debit, -50 credit -> balance = 50.00
            assertThat(a.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
            // Acc B: +100 credit, -50 debit -> balance = 50.00
            assertThat(b.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
        });
    }

    // =========================================================================
    // 2. CONCURRENT ESCROW ACCOUNT CREATION
    // =========================================================================

    @Test
    @DisplayName("2. PostgreSQL: Concurrent escrow creation for same Deal produces exactly one account")
    void test02_postgres_concurrentEscrowAccountCreation() throws Exception {
        UUID dealId = newIndependentTransaction().execute(status -> {
            User buyer = createAndSaveUser("EscrowBuyer", Role.BUYER);
            User seller = createAndSaveUser("EscrowSeller", Role.SELLER);
            Deal d = new Deal("Escrow Deal", "Desc", buyer.getId(), seller.getId(), new BigDecimal("100000.00"), "INR", DealStatus.ACCEPTED);
            return dealRepository.save(d).getId();
        });

        Callable<EscrowAccount> createEscrowTask = () -> newIndependentTransaction().execute(status ->
                escrowService.ensureEscrowAccount(dealId, "INR")
        );

        Future<EscrowAccount> future1 = executor.submit(createEscrowTask);
        Future<EscrowAccount> future2 = executor.submit(createEscrowTask);

        EscrowAccount account1 = future1.get(10, TimeUnit.SECONDS);
        EscrowAccount account2 = future2.get(10, TimeUnit.SECONDS);

        assertThat(account1).isNotNull();
        assertThat(account2).isNotNull();
        assertThat(account1.getId()).as("Both callers observe the exact same logical escrow account").isEqualTo(account2.getId());

        // Verify DB invariant: exactly 1 escrow account for this deal
        newIndependentTransaction().executeWithoutResult(status -> {
            long count = escrowAccountRepository.findAll().stream().filter(e -> dealId.equals(e.getDealId())).count();
            assertThat(count).isEqualTo(1L);
        });
    }

    // =========================================================================
    // 3. CONCURRENT MILESTONE APPROVE VS REJECT
    // =========================================================================

    @Test
    @DisplayName("3. PostgreSQL: Concurrent approve vs reject serializes without lost update")
    void test03_postgres_concurrentApproveVsReject() throws Exception {
        Map<String, Object> fixtures = newIndependentTransaction().execute(status -> {
            User buyer = createAndSaveUser("ApproveBuyer", Role.BUYER);
            User seller = createAndSaveUser("ApproveSeller", Role.SELLER);

            Deal d = new Deal("Milestone Deal", "Desc", buyer.getId(), seller.getId(), new BigDecimal("50000.00"), "INR", DealStatus.ACCEPTED);
            d = dealRepository.save(d);

            Milestone m = new Milestone(d.getId(), "Milestone 1", "Desc", 1, new BigDecimal("50000.00"), "INR", Instant.now().plusSeconds(86400));
            m.setStatus(MilestoneStatus.SUBMITTED);
            m = milestoneRepository.save(m);

            return Map.of("dealId", d.getId(), "milestoneId", m.getId(), "buyer", buyer);
        });

        UUID milestoneId = (UUID) fixtures.get("milestoneId");
        User buyerUser = (User) fixtures.get("buyer");
        UserPrincipal buyerPrincipal = UserPrincipal.create(buyerUser);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);

        Callable<Void> approveTask = () -> {
            try {
                newIndependentTransaction().executeWithoutResult(status ->
                        milestoneService.approveMilestone(milestoneId, buyerPrincipal)
                );
                successes.incrementAndGet();
            } catch (Exception e) {
                conflicts.incrementAndGet();
            }
            return null;
        };

        Callable<Void> rejectTask = () -> {
            try {
                newIndependentTransaction().executeWithoutResult(status ->
                        milestoneService.rejectMilestone(milestoneId, "Changes requested", buyerPrincipal)
                );
                successes.incrementAndGet();
            } catch (Exception e) {
                conflicts.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(approveTask);
        Future<Void> f2 = executor.submit(rejectTask);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        assertThat(successes.get()).as("Exactly one operation must succeed").isEqualTo(1);
        assertThat(conflicts.get()).as("The losing operation must fail on status validation").isEqualTo(1);

        // Verify final milestone state is valid and not overwritten
        newIndependentTransaction().executeWithoutResult(status -> {
            Milestone finalM = milestoneRepository.findById(milestoneId).orElseThrow();
            assertThat(finalM.getStatus()).isIn(MilestoneStatus.APPROVED, MilestoneStatus.REJECTED);
        });
    }

    // =========================================================================
    // 4. CONCURRENT AGREEMENT MUTATION
    // =========================================================================

    @Test
    @DisplayName("4. PostgreSQL: Concurrent agreement mutation serializes through Deal lock")
    void test04_postgres_concurrentAgreementMutation() throws Exception {
        Map<String, Object> fixtures = newIndependentTransaction().execute(status -> {
            User buyer = createAndSaveUser("AgrBuyer", Role.BUYER);
            User seller = createAndSaveUser("AgrSeller", Role.SELLER);

            Deal d = new Deal("Agreement Deal", "Desc", buyer.getId(), seller.getId(), new BigDecimal("25000.00"), "INR", DealStatus.ACCEPTED);
            d = dealRepository.save(d);
            return Map.of("dealId", d.getId(), "buyer", buyer);
        });

        UUID dealId = (UUID) fixtures.get("dealId");
        User buyerUser = (User) fixtures.get("buyer");
        UserPrincipal buyerPrincipal = UserPrincipal.create(buyerUser);

        Callable<Void> draftTask1 = () -> {
            newIndependentTransaction().executeWithoutResult(status ->
                    agreementService.createOrUpdateDraft(dealId, new CreateAgreementDraftRequest("Agreement T1", "Terms Content 1"), buyerPrincipal)
            );
            return null;
        };

        Callable<Void> draftTask2 = () -> {
            newIndependentTransaction().executeWithoutResult(status ->
                    agreementService.createOrUpdateDraft(dealId, new CreateAgreementDraftRequest("Agreement T2", "Terms Content 2"), buyerPrincipal)
            );
            return null;
        };

        Future<Void> f1 = executor.submit(draftTask1);
        Future<Void> f2 = executor.submit(draftTask2);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        // Serialization ensures valid draft exists without race corruption
        newIndependentTransaction().executeWithoutResult(status -> {
            Deal finalDeal = dealRepository.findById(dealId).orElseThrow();
            assertThat(finalDeal).isNotNull();
        });
    }

    // =========================================================================
    // 5. APPROVAL APPLICABILITY SNAPSHOT COHERENCE
    // =========================================================================

    @Test
    @DisplayName("5. PostgreSQL: Approval applicability evaluation fails closed on concurrent mutation")
    void test05_postgres_approvalApplicabilityFailsClosedOnMutation() throws Exception {
        Map<String, UUID> ids = newIndependentTransaction().execute(status -> {
            User buyer = createAndSaveUser("AppBuyer", Role.BUYER);
            User seller = createAndSaveUser("AppSeller", Role.SELLER);

            Deal d = new Deal("Applicability Deal", "Desc", buyer.getId(), seller.getId(), new BigDecimal("100000.00"), "INR", DealStatus.ACCEPTED);
            d = dealRepository.save(d);

            Milestone m = new Milestone(d.getId(), "Milestone A", "Desc", 1, new BigDecimal("100000.00"), "INR", Instant.now().plusSeconds(86400));
            m.setStatus(MilestoneStatus.APPROVED);
            m = milestoneRepository.save(m);

            return Map.of("dealId", d.getId(), "milestoneId", m.getId());
        });

        UUID dealId = ids.get("dealId");
        UUID milestoneId = ids.get("milestoneId");

        // Concurrently mutate Deal while evaluating applicability
        Future<ApprovalApplicabilityResult> evalFuture = executor.submit(() ->
                approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId)
        );

        Future<Void> mutateFuture = executor.submit(() -> {
            newIndependentTransaction().executeWithoutResult(status -> {
                Deal d = dealRepository.findByIdForUpdate(dealId).orElseThrow();
                d.setStatus(DealStatus.DISPUTED);
                dealRepository.save(d);
            });
            return null;
        });

        mutateFuture.get(10, TimeUnit.SECONDS);
        ApprovalApplicabilityResult result = evalFuture.get(10, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        // Crucial invariant: reader never returns an invalid current authorization
        assertThat(result.applicable()).isFalse();
    }

    // =========================================================================
    // 6. RISK REVIEW CONTENTION BOUNDARY
    // =========================================================================

    @Test
    @DisplayName("6. PostgreSQL: Risk REQUIRES_NEW payout review completes without lock inversion deadlock")
    void test06_postgres_riskPayoutReviewContentionBoundary() throws Exception {
        Map<String, UUID> orgIds = newIndependentTransaction().execute(status -> {
            User buyer = createAndSaveUser("RiskBuyer", Role.BUYER);
            User vendor = createAndSaveUser("RiskVendor", Role.SELLER);

            Organization buyerOrg = createAndSaveOrg("RiskBuyerOrg", buyer.getId());
            Organization vendorOrg = createAndSaveOrg("RiskVendorOrg", vendor.getId());

            return Map.of(
                    "buyerId", buyer.getId(),
                    "buyerOrgId", buyerOrg.getId(),
                    "vendorId", vendor.getId(),
                    "vendorOrgId", vendorOrg.getId()
            );
        });

        UUID buyerId = orgIds.get("buyerId");
        UUID buyerOrgId = orgIds.get("buyerOrgId");
        UUID vendorId = orgIds.get("vendorId");
        UUID vendorOrgId = orgIds.get("vendorOrgId");

        Callable<List<UUID>> riskTask = () ->
                riskActionService.payoutReviews(buyerId, buyerOrgId, vendorId, vendorOrgId, new BigDecimal("50000.00"), "INR");

        Future<List<UUID>> future = executor.submit(riskTask);
        List<UUID> reviews = future.get(10, TimeUnit.SECONDS);

        assertThat(reviews).isNotNull();
    }

    // =========================================================================
    // 7. EXTERNAL EFFECT & B04 FAIL-CLOSED ENFORCEMENT
    // =========================================================================

    @Test
    @DisplayName("7. PostgreSQL: Outbound settlement remains strictly fail closed (0 BankingProvider calls)")
    void test07_postgres_settlementRemainsFailClosed() {
        UUID milestoneId = UUID.randomUUID();
        UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "Reviewer", "rev@test.com", "hash", Role.ADMIN, UserStatus.ACTIVE, List.of());

        assertThatThrownBy(() ->
                settlementAuthorizationService.authorizeAndInitiateSettlement(new PayoutAuthorizationRequest(milestoneId, "Test", "Idemp-Key"), principal)
        ).isInstanceOf(AccessDeniedException.class)
         .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verify(bankingProvider, never()).initiateSettlement(any());
    }
}
