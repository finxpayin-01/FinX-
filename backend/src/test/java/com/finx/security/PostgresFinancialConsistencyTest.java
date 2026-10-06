package com.finx.security;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.ledger.dto.LedgerDtos.JournalLineItem;
import com.finx.ledger.dto.LedgerDtos.PostJournalEntryRequest;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.entity.LedgerAccount;
import com.finx.ledger.enums.LedgerEnums.AccountType;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.JournalLineRepository;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationRepository;
import com.finx.payment.entity.Payment;
import com.finx.payment.entity.PaymentStatus;
import com.finx.payment.repository.PaymentRepository;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
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
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B06: Real PostgreSQL Financial Consistency, Source Lineage & Ledger Test Suite.
 *
 * Requirements verified:
 * 1. Database product == "PostgreSQL".
 * 2. Bank source FK (payment_instruction_id) correctly populated, legacy payment_id == null.
 * 3. Wrong source FK rejected by DB foreign key constraint.
 * 4. Legacy payment source preserved in payments table and escrow_ledger.payment_id.
 * 5. Source exclusivity: row cannot claim both sources; FUND requires exactly one source.
 * 6. Confirmed event uniqueness: replay creates exactly 1 journal entry, 1 escrow ledger, 1 balance change.
 * 7. Duplicate journal source effect blocked by database partial unique index.
 * 8. Atomic rollback: failure during projection rolls back preceding journal entry.
 * 9. Currency policy: INR-only supported, mismatched currencies rejected.
 * 10. Exact balance validation: debits == credits using BigDecimal scale.
 * 11. One-full-funding policy: milestone cannot be funded twice.
 * 12. Multi-connection concurrency race: exactly 1 effect, no deadlocks, no timeouts.
 * 13. BankingProvider.initiateSettlement calls == 0 throughout.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/finx_b06_test}",
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
public class PostgresFinancialConsistencyTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EscrowService escrowService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PaymentOrchestrationService paymentOrchestrationService;

    @Autowired
    private EscrowAccountRepository escrowAccountRepository;

    @Autowired
    private EscrowLedgerRepository escrowLedgerRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private JournalLineRepository journalLineRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private PaymentInstructionRepository paymentInstructionRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private com.finx.paymentorchestration.repository.SettlementInstructionRepository settlementInstructionRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @MockBean
    private BankingProvider bankingProvider;

    private User buyer;
    private User seller;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private Deal deal;
    private Milestone milestone;

    public static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            assertThat(product).isEqualTo("PostgreSQL");
        }

        cleanDatabase();
        seedBaseEntities();
    }

    @AfterEach
    void verifyBankExecutionDisabled() {
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    private void cleanDatabase() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            journalLineRepository.deleteAll();
            journalEntryRepository.deleteAll();
            escrowLedgerRepository.deleteAll();
            escrowAccountRepository.deleteAll();
            paymentTransactionRepository.deleteAll();
            paymentInstructionRepository.deleteAll();
            settlementInstructionRepository.deleteAll();
            paymentRepository.deleteAll();
        });
    }

    private void seedBaseEntities() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            buyerOrg = new Organization("Buyer Corp " + suffix, "Buyer Corp Ltd", EntityType.PRIVATE_LIMITED,
                    "REG-B-" + suffix, "PAN-B-" + suffix, "29AAAAA0000A1Z5", "123 Business St", null);
            buyerOrg = organizationRepository.saveAndFlush(buyerOrg);

            sellerOrg = new Organization("Seller Corp " + suffix, "Seller Corp Ltd", EntityType.PRIVATE_LIMITED,
                    "REG-S-" + suffix, "PAN-S-" + suffix, "29AAAAA0000A1Z6", "456 Vendor St", null);
            sellerOrg = organizationRepository.saveAndFlush(sellerOrg);

            buyer = new User("Buyer " + suffix, "buyer-" + suffix + "@test.com", "hash", Role.BUYER, UserStatus.ACTIVE);
            buyer = userRepository.saveAndFlush(buyer);

            seller = new User("Seller " + suffix, "seller-" + suffix + "@test.com", "hash", Role.SELLER, UserStatus.ACTIVE);
            seller = userRepository.saveAndFlush(seller);

            deal = new Deal("Consistency Deal", "Desc", buyer.getId(), seller.getId(), BigDecimal.valueOf(100000.00), "INR", DealStatus.ACCEPTED);
            deal.setBuyerOrganizationId(buyerOrg.getId());
            deal.setSellerOrganizationId(sellerOrg.getId());
            deal = dealRepository.saveAndFlush(deal);

            milestone = new Milestone(deal.getId(), "Milestone 1", "Desc", 1, BigDecimal.valueOf(50000.00), "INR", Instant.now().plusSeconds(86400));
            milestone.setStatus(MilestoneStatus.UNDER_REVIEW);
            milestone = milestoneRepository.saveAndFlush(milestone);

            ledgerService.initSystemAccounts();
        });
    }

    private PaymentInstruction createInstruction(BigDecimal amount) {
        String ref = "FINX-TXN-2026-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        PaymentInstruction instruction = new PaymentInstruction(
                ref, deal.getId(), milestone.getId(), buyerOrg.getId(), sellerOrg.getId(),
                amount, "INR", "BANK", "IDEM-" + UUID.randomUUID(), buyer.getId()
        );
        instruction.setStatus(InstructionStatus.SUBMITTED);
        instruction.setProviderInstructionId("PROV-INS-" + UUID.randomUUID());
        return paymentInstructionRepository.saveAndFlush(instruction);
    }

    // =========================================================================
    // 1. BANK SOURCE FK (Requirement 19)
    // =========================================================================
    @Test
    @DisplayName("19: Bank FUND entry populates payment_instruction_id and leaves payment_id null")
    void test19_bankSourceFkSuccess() {
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));

        escrowService.fundEscrowForInstruction(deal.getId(), milestone.getId(), instruction.getId(),
                BigDecimal.valueOf(50000.00), "INR", buyer.getId());

        List<EscrowLedger> entries = escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(
                escrowAccountRepository.findByDealId(deal.getId()).orElseThrow().getId());

        assertThat(entries).hasSize(1);
        EscrowLedger entry = entries.get(0);
        assertThat(entry.getPaymentInstructionId()).isEqualTo(instruction.getId());
        assertThat(entry.getPaymentId()).isNull();
        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.FUND);
        assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(50000.00));
    }

    // =========================================================================
    // 2. WRONG SOURCE FK (Requirement 20)
    // =========================================================================
    @Test
    @DisplayName("20: Non-existent bank source or placing instruction ID into payment_id is rejected")
    void test20_wrongSourceFkRejected() {
        UUID nonExistentId = UUID.randomUUID();

        // 1. Non-existent bank instruction ID rejected by FK
        assertThatThrownBy(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt.executeWithoutResult(status -> {
                escrowService.fundEscrowForInstruction(deal.getId(), milestone.getId(), nonExistentId,
                        BigDecimal.valueOf(50000.00), "INR", buyer.getId());
            });
        }).isInstanceOf(DataIntegrityViolationException.class);

        // 2. Placing PaymentInstruction ID into legacy payment_id fails FK against payments table
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));
        assertThatThrownBy(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt.executeWithoutResult(status -> {
                escrowService.fundEscrow(deal.getId(), milestone.getId(), instruction.getId(),
                        BigDecimal.valueOf(50000.00), "INR", buyer.getId());
            });
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // 3. LEGACY PRESERVATION (Requirement 21)
    // =========================================================================
    @Test
    @DisplayName("21: Historical legacy Payment record uses payment_id and leaves payment_instruction_id null")
    void test21_legacyPreservationSuccess() {
        Payment legacyPayment = new Payment(deal.getId(), milestone.getId(), buyer.getId(),
                BigDecimal.valueOf(50000.00), "INR", "order_legacy_" + UUID.randomUUID(), "IDEM-LEG-" + UUID.randomUUID());
        legacyPayment.setStatus(PaymentStatus.SUCCESS);
        legacyPayment = paymentRepository.saveAndFlush(legacyPayment);

        escrowService.fundEscrow(deal.getId(), milestone.getId(), legacyPayment.getId(),
                BigDecimal.valueOf(50000.00), "INR", buyer.getId());

        List<EscrowLedger> entries = escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(
                escrowAccountRepository.findByDealId(deal.getId()).orElseThrow().getId());

        assertThat(entries).hasSize(1);
        EscrowLedger entry = entries.get(0);
        assertThat(entry.getPaymentId()).isEqualTo(legacyPayment.getId());
        assertThat(entry.getPaymentInstructionId()).isNull();
        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.FUND);
    }

    // =========================================================================
    // 4. SOURCE EXCLUSIVITY (Requirement 22)
    // =========================================================================
    @Test
    @DisplayName("22: Row cannot claim both sources, and FUND requires exactly one source")
    void test22_sourceExclusivityEnforced() throws Exception {
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));
        Payment legacyPayment = new Payment(deal.getId(), milestone.getId(), buyer.getId(),
                BigDecimal.valueOf(50000.00), "INR", "order_excl_" + UUID.randomUUID(), "IDEM-EXCL-" + UUID.randomUUID());
        legacyPayment.setStatus(PaymentStatus.SUCCESS);
        legacyPayment = paymentRepository.saveAndFlush(legacyPayment);

        EscrowAccount account = escrowService.getOrCreateEscrowAccount(deal.getId(), "INR");

        // 1. Attempting BOTH sources non-null violates chk_escrow_ledger_source_exclusivity
        try (Connection conn = dataSource.getConnection()) {
            String sql = "INSERT INTO escrow_ledger (id, escrow_account_id, payment_id, payment_instruction_id, milestone_id, transaction_type, amount, balance_after, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, 'RELEASE', 1000.00, 1000.00, NOW())";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, account.getId());
                ps.setObject(3, legacyPayment.getId());
                ps.setObject(4, instruction.getId());
                ps.setObject(5, milestone.getId());
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_escrow_ledger_source_exclusivity");
            }

            // 2. Attempting FUND with BOTH sources NULL violates chk_escrow_ledger_fund_source
            String sqlNoSource = "INSERT INTO escrow_ledger (id, escrow_account_id, payment_id, payment_instruction_id, milestone_id, transaction_type, amount, balance_after, created_at) " +
                    "VALUES (?, ?, NULL, NULL, ?, 'FUND', 1000.00, 1000.00, NOW())";
            try (PreparedStatement ps = conn.prepareStatement(sqlNoSource)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, account.getId());
                ps.setObject(3, milestone.getId());
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_escrow_ledger_fund_source");
            }
        }
    }

    // =========================================================================
    // 5. REPLAY ONCE / CONFIRMED EVENT UNIQUENESS (Requirement 23)
    // =========================================================================
    @Test
    @DisplayName("23: Replay of confirmed bank funding creates exactly 1 journal, 1 escrow ledger, 1 balance change")
    void test23_replayOnceConfirmedEventUniqueness() {
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));
        String utr = "UTR-CONFIRM-" + UUID.randomUUID().toString().substring(0, 8);

        // First finalization: succeeds
        PaymentInstruction result1 = paymentOrchestrationService.finalizeBankFunding(instruction.getId(), utr, buyer.getId());
        assertThat(result1.getStatus()).isEqualTo(InstructionStatus.SUCCESS);

        // Replay same confirmed finalization: idempotent no-op / safe replay
        PaymentInstruction result2 = paymentOrchestrationService.finalizeBankFunding(instruction.getId(), utr, buyer.getId());
        assertThat(result2.getStatus()).isEqualTo(InstructionStatus.SUCCESS);

        // Query database directly to assert effect counts
        List<JournalEntry> journals = journalEntryRepository.findByReferenceId(instruction.getId());
        assertThat(journals).hasSize(1);

        EscrowAccount account = escrowAccountRepository.findByDealId(deal.getId()).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(50000.00));
        assertThat(account.getTotalFunded()).isEqualByComparingTo(BigDecimal.valueOf(50000.00));

        List<EscrowLedger> ledgers = escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(account.getId());
        assertThat(ledgers).hasSize(1);
        assertThat(ledgers.get(0).getPaymentInstructionId()).isEqualTo(instruction.getId());
    }

    // =========================================================================
    // 6. DUPLICATE JOURNAL SOURCE INVARIANT (Requirement 25)
    // =========================================================================
    @Test
    @DisplayName("25: Duplicate journal posting for same financial source reference is rejected by DB invariant")
    void test25_duplicateJournalSourceBlocked() {
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));

        PostJournalEntryRequest req1 = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, instruction.getId(), deal.getId(), milestone.getId(),
                "INR", "First posting", buyer.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(50000.00), "Pool debit"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(50000.00), "Liability credit")
                )
        );
        JournalEntry entry1 = ledgerService.postJournalEntry(req1);
        assertThat(entry1).isNotNull();

        // 1. Service check-before-write rejects duplicate
        PostJournalEntryRequest req2 = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, instruction.getId(), deal.getId(), milestone.getId(),
                "INR", "Duplicate posting", buyer.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(50000.00), "Pool debit"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(50000.00), "Liability credit")
                )
        );
        assertThatThrownBy(() -> ledgerService.postJournalEntry(req2))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate journal entry");

        // 2. Direct insert violates uk_journal_entries_type_reference index
        assertThatThrownBy(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt.executeWithoutResult(status -> {
                JournalEntry directEntry = new JournalEntry(
                        "FINX-JRN-DIRECT-" + UUID.randomUUID(), LedgerTransactionType.ESCROW_DEPOSIT,
                        instruction.getId(), deal.getId(), milestone.getId(), BigDecimal.valueOf(50000.00),
                        "INR", "Direct duplicate", buyer.getId()
                );
                journalEntryRepository.saveAndFlush(directEntry);
            });
        }).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(journalEntryRepository.findByReferenceId(instruction.getId())).hasSize(1);
    }

    // =========================================================================
    // 7. ATOMIC ROLLBACK (JOURNAL THEN PROJECTION FAILURE) (Requirement 24)
    // =========================================================================
    @Test
    @DisplayName("24: Projection failure after journal posting atomically rolls back the journal entry")
    void test24_journalThenProjectionFailureRollsBack() {
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));

        long initialJournalCount = journalEntryRepository.count();
        long initialLineCount = journalLineRepository.count();
        long initialLedgerCount = escrowLedgerRepository.count();

        assertThatThrownBy(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt.executeWithoutResult(status -> {
                // Step 1: Journal posting succeeds
                PostJournalEntryRequest journalReq = new PostJournalEntryRequest(
                        LedgerTransactionType.ESCROW_DEPOSIT, instruction.getId(), deal.getId(), milestone.getId(),
                        "INR", "Rollback test", buyer.getId(),
                        List.of(
                                new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(50000.00), "Debit"),
                                new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(50000.00), "Credit")
                        )
                );
                ledgerService.postJournalEntry(journalReq);

                // Step 2: Controlled projection failure (e.g. invalid currency or constraint failure)
                throw new IllegalStateException("SIMULATED_PROJECTION_FAILURE_IN_TEST");
            });
        }).isInstanceOf(IllegalStateException.class).hasMessage("SIMULATED_PROJECTION_FAILURE_IN_TEST");

        // Verify full transactional rollback: zero delta
        assertThat(journalEntryRepository.count()).isEqualTo(initialJournalCount);
        assertThat(journalLineRepository.count()).isEqualTo(initialLineCount);
        assertThat(escrowLedgerRepository.count()).isEqualTo(initialLedgerCount);
        assertThat(journalEntryRepository.findByReferenceId(instruction.getId())).isEmpty();
    }

    // =========================================================================
    // 8. CURRENCY TESTS (Requirement 27)
    // =========================================================================
    @Test
    @DisplayName("27: Currency policy: valid INR passes, non-INR or mismatched currencies rejected")
    void test27_currencyPolicyEnforced() {
        // 1. Non-INR journal currency rejected
        PostJournalEntryRequest nonInrReq = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, UUID.randomUUID(), deal.getId(), milestone.getId(),
                "USD", "USD journal", buyer.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(100.00), "Debit"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(100.00), "Credit")
                )
        );
        assertThatThrownBy(() -> ledgerService.postJournalEntry(nonInrReq))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported currency");

        // 2. Non-INR account creation rejected
        assertThatThrownBy(() -> ledgerService.ensureAccount("9999-USD-ACC", "USD Account", AccountType.ASSET, "USD"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported account currency");

        // 3. Escrow funding currency mismatch with deal rejected
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));
        assertThatThrownBy(() -> escrowService.fundEscrowForInstruction(deal.getId(), milestone.getId(),
                instruction.getId(), BigDecimal.valueOf(50000.00), "EUR", buyer.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported currency");
    }

    // =========================================================================
    // 9. BALANCE TESTS (Requirement 28)
    // =========================================================================
    @Test
    @DisplayName("28: Double-entry line totals must balance; unbalance or non-positive lines rejected")
    void test28_balanceChecksEnforced() {
        // 1. Unbalanced lines: DEBIT != CREDIT rejected
        PostJournalEntryRequest unbalancedReq = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, UUID.randomUUID(), deal.getId(), milestone.getId(),
                "INR", "Unbalanced", buyer.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(100.00), "Debit 100"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(99.00), "Credit 99")
                )
        );
        assertThatThrownBy(() -> ledgerService.postJournalEntry(unbalancedReq))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Double-entry violation");

        // 2. Negative line amount rejected
        PostJournalEntryRequest negativeReq = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, UUID.randomUUID(), deal.getId(), milestone.getId(),
                "INR", "Negative amount", buyer.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, BigDecimal.valueOf(-50.00), "Debit negative"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, BigDecimal.valueOf(-50.00), "Credit negative")
                )
        );
        assertThatThrownBy(() -> ledgerService.postJournalEntry(negativeReq))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Journal line amount must be greater than zero");

        // 3. Exact BigDecimal calculation with 4 decimals
        PostJournalEntryRequest exactReq = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT, UUID.randomUUID(), deal.getId(), milestone.getId(),
                "INR", "Exact scale", buyer.getId(),
                List.of(
                        new JournalLineItem(LedgerService.ACC_ESCROW_POOL, EntryType.DEBIT, new BigDecimal("33.3333"), "Debit"),
                        new JournalLineItem(LedgerService.ACC_ESCROW_LIABILITY, EntryType.CREDIT, new BigDecimal("33.3333"), "Credit")
                )
        );
        JournalEntry exactEntry = ledgerService.postJournalEntry(exactReq);
        assertThat(exactEntry.getTotalDebit()).isEqualByComparingTo(new BigDecimal("33.3333"));
    }

    // =========================================================================
    // 10. ONE-FULL-FUNDING POLICY (Requirement 11)
    // =========================================================================
    @Test
    @DisplayName("11: Milestone one-full-funding invariant: second funding attempt on same milestone rejected")
    void test11_oneFullFundingPolicyEnforced() {
        PaymentInstruction instruction1 = createInstruction(BigDecimal.valueOf(50000.00));
        escrowService.fundEscrowForInstruction(deal.getId(), milestone.getId(), instruction1.getId(),
                BigDecimal.valueOf(50000.00), "INR", buyer.getId());

        // Attempting to fund the same milestone again with a second instruction is rejected
        PaymentInstruction instruction2 = createInstruction(BigDecimal.valueOf(50000.00));
        assertThatThrownBy(() -> escrowService.fundEscrowForInstruction(deal.getId(), milestone.getId(),
                instruction2.getId(), BigDecimal.valueOf(50000.00), "INR", buyer.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been funded");
    }

    // =========================================================================
    // 11. MULTI-CONNECTION CONCURRENCY RACE (Requirement 26)
    // =========================================================================
    @Test
    @DisplayName("26: Multi-connection concurrent race for same confirmed bank source resolves to exactly 1 effect")
    void test26_concurrentFinalizationRace() throws Exception {
        PaymentInstruction instruction = createInstruction(BigDecimal.valueOf(50000.00));
        String utr = "UTR-RACE-" + UUID.randomUUID().toString().substring(0, 8);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<Void>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    paymentOrchestrationService.finalizeBankFunding(instruction.getId(), utr, buyer.getId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly one concurrent attempt performed the state transition or both converged cleanly
        List<JournalEntry> journals = journalEntryRepository.findByReferenceId(instruction.getId());
        assertThat(journals).hasSize(1);

        EscrowAccount account = escrowAccountRepository.findByDealId(deal.getId()).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(50000.00));
        assertThat(account.getTotalFunded()).isEqualByComparingTo(BigDecimal.valueOf(50000.00));

        List<EscrowLedger> ledgers = escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(account.getId());
        assertThat(ledgers).hasSize(1);
        assertThat(ledgers.get(0).getPaymentInstructionId()).isEqualTo(instruction.getId());

        verify(bankingProvider, never()).initiateSettlement(any());
    }
}
