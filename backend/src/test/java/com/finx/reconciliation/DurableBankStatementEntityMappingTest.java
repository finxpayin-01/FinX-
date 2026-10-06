package com.finx.reconciliation;

import com.finx.banking.provider.BankingProvider;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
@org.junit.jupiter.api.condition.EnabledIf("isPostgresAvailable")
public class DurableBankStatementEntityMappingTest {

    static boolean isPostgresAvailable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private BankStatementImportRepository importRepository;

    @Autowired
    private BankStatementLineRepository lineRepository;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationItemRepository itemRepository;

    @MockBean
    private BankingProvider bankingProvider;

    @Test
    @Transactional
    @DisplayName("BankStatementImport JPA save, read, precision, timestamp, and enum mappings")
    void testBankStatementImportPersistence() {
        Instant windowStart = Instant.parse("2026-10-01T00:00:00Z");
        Instant windowEnd = Instant.parse("2026-10-01T23:59:59Z");
        BigDecimal openingBal = new BigDecimal("1000000.5000");
        BigDecimal closingBal = new BigDecimal("1050000.7500");
        String hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

        BankStatementImport imp = new BankStatementImport(
                "YES_BANK",
                "ESCROW_VIRTUAL_001",
                windowStart,
                windowEnd,
                hash,
                openingBal,
                closingBal,
                "INR",
                null
        );
        imp.setTotalLines(5);
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        imp.setCompletedAt(Instant.now());

        BankStatementImport saved = importRepository.saveAndFlush(imp);
        assertThat(saved.getId()).isNotNull();

        Optional<BankStatementImport> found = importRepository.findById(saved.getId());
        assertThat(found).isPresent();
        BankStatementImport retrieved = found.get();

        assertThat(retrieved.getProvider()).isEqualTo("YES_BANK");
        assertThat(retrieved.getProviderAccountId()).isEqualTo("ESCROW_VIRTUAL_001");
        assertThat(retrieved.getWindowStart()).isEqualTo(windowStart);
        assertThat(retrieved.getWindowEnd()).isEqualTo(windowEnd);
        assertThat(retrieved.getSourceHash()).isEqualTo(hash);
        assertThat(retrieved.getOpeningBalance()).isEqualByComparingTo(openingBal);
        assertThat(retrieved.getClosingBalance()).isEqualByComparingTo(closingBal);
        assertThat(retrieved.getCurrency()).isEqualTo("INR");
        assertThat(retrieved.getTotalLines()).isEqualTo(5);
        assertThat(retrieved.getStatus()).isEqualTo(BankStatementImportStatus.COMPLETED);
        assertThat(retrieved.getCreatedAt()).isNotNull();
        assertThat(retrieved.getCompletedAt()).isNotNull();

        // Repository custom finder
        Optional<BankStatementImport> byHash = importRepository.findByProviderAndProviderAccountIdAndSourceHash(
                "YES_BANK", "ESCROW_VIRTUAL_001", hash);
        assertThat(byHash).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("BankStatementLine JPA save, read, precision, timestamp, and enum mappings")
    void testBankStatementLinePersistence() {
        BankStatementImport imp = new BankStatementImport(
                "YES_BANK",
                "ESCROW_VIRTUAL_002",
                Instant.now().minusSeconds(86400),
                Instant.now(),
                "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "INR",
                null
        );
        BankStatementImport savedImport = importRepository.saveAndFlush(imp);

        Instant txTime = Instant.parse("2026-10-01T14:30:00Z");
        LocalDate valDate = LocalDate.parse("2026-10-01");
        BigDecimal amt = new BigDecimal("49999.9900");
        String fp = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

        BankStatementLine line = new BankStatementLine(
                savedImport.getId(),
                "YES_BANK",
                "ESCROW_VIRTUAL_002",
                "PLINE_999",
                "TXREF_888",
                "UTR_777",
                amt,
                "INR",
                FinancialDirection.CREDIT,
                "SETTLED",
                BankStatementLineStatus.FINAL_SUCCESS,
                txTime,
                valDate,
                fp
        );

        BankStatementLine savedLine = lineRepository.saveAndFlush(line);
        assertThat(savedLine.getId()).isNotNull();

        Optional<BankStatementLine> found = lineRepository.findById(savedLine.getId());
        assertThat(found).isPresent();
        BankStatementLine retrieved = found.get();

        assertThat(retrieved.getImportId()).isEqualTo(savedImport.getId());
        assertThat(retrieved.getProvider()).isEqualTo("YES_BANK");
        assertThat(retrieved.getProviderAccountId()).isEqualTo("ESCROW_VIRTUAL_002");
        assertThat(retrieved.getProviderLineId()).isEqualTo("PLINE_999");
        assertThat(retrieved.getTransactionReference()).isEqualTo("TXREF_888");
        assertThat(retrieved.getUtrNumber()).isEqualTo("UTR_777");
        assertThat(retrieved.getAmount()).isEqualByComparingTo(amt);
        assertThat(retrieved.getCurrency()).isEqualTo("INR");
        assertThat(retrieved.getDirection()).isEqualTo(FinancialDirection.CREDIT);
        assertThat(retrieved.getProviderStatus()).isEqualTo("SETTLED");
        assertThat(retrieved.getNormalizedStatus()).isEqualTo(BankStatementLineStatus.FINAL_SUCCESS);
        assertThat(retrieved.getTransactionTime()).isEqualTo(txTime);
        assertThat(retrieved.getValueDate()).isEqualTo(valDate);
        assertThat(retrieved.getLineFingerprint()).isEqualTo(fp);
        assertThat(retrieved.getCreatedAt()).isNotNull();

        // Repository custom finders
        List<BankStatementLine> linesByImport = lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(savedImport.getId());
        assertThat(linesByImport).hasSize(1);

        Optional<BankStatementLine> byLineId = lineRepository.findByProviderAndProviderAccountIdAndProviderLineId(
                "YES_BANK", "ESCROW_VIRTUAL_002", "PLINE_999");
        assertThat(byLineId).isPresent();

        Optional<BankStatementLine> byFp = lineRepository.findByProviderAndProviderAccountIdAndLineFingerprint(
                "YES_BANK", "ESCROW_VIRTUAL_002", fp);
        assertThat(byFp).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("ReconciliationRun and ReconciliationItem additive V26 field mappings")
    void testReconciliationAdditiveFieldsPersistence() {
        BankStatementImport imp = new BankStatementImport(
                "YES_BANK",
                "ESCROW_VIRTUAL_003",
                Instant.now().minusSeconds(86400),
                Instant.now(),
                "222233334444555566667777888899990000aaaabbbbccccddddeeeeffff1111",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "INR",
                null
        );
        BankStatementImport savedImport = importRepository.saveAndFlush(imp);

        BankStatementLine line = new BankStatementLine(
                savedImport.getId(),
                "YES_BANK",
                "ESCROW_VIRTUAL_003",
                "PLINE_100",
                "TXREF_100",
                "UTR_100",
                new BigDecimal("25000.0000"),
                "INR",
                FinancialDirection.CREDIT,
                "SETTLED",
                BankStatementLineStatus.FINAL_SUCCESS,
                Instant.now(),
                LocalDate.now(),
                "33334444555566667777888899990000aaaabbbbccccddddeeeeffff11112222"
        );
        BankStatementLine savedLine = lineRepository.saveAndFlush(line);

        // Save ReconciliationRun with V26 fields
        ReconciliationRun run = new ReconciliationRun("RUN_TEST_V26_01", "YES_BANK", null);
        run.setProviderAccountId("ESCROW_VIRTUAL_003");
        run.setBankStatementImportId(savedImport.getId());
        run.setOpeningBalance(new BigDecimal("10000.0000"));
        run.setClosingBalance(new BigDecimal("35000.0000"));
        run.setCurrency("INR");
        ReconciliationRun savedRun = runRepository.saveAndFlush(run);

        assertThat(savedRun.getBankStatementImportId()).isEqualTo(savedImport.getId());
        assertThat(savedRun.getProviderAccountId()).isEqualTo("ESCROW_VIRTUAL_003");

        // Save ReconciliationItem with V26 fields
        ReconciliationItem item = new ReconciliationItem(
                savedRun.getId(),
                "TXREF_100",
                "TXREF_100",
                new BigDecimal("25000.0000"),
                new BigDecimal("25000.0000"),
                ReconciliationItemStatus.MATCHED,
                "Matched via V26 durable schema"
        );
        item.setBankStatementLineId(savedLine.getId());
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        UUID effectId = UUID.randomUUID();
        item.setInternalEffectId(effectId);
        item.setCurrency("INR");
        item.setDirection(FinancialDirection.CREDIT);
        item.setBankStatus("SETTLED");
        item.setResolutionNotes("Clean automated durable match");

        ReconciliationItem savedItem = itemRepository.saveAndFlush(item);
        assertThat(savedItem.getId()).isNotNull();

        Optional<ReconciliationItem> byLine = itemRepository.findByReconciliationRunIdAndBankStatementLineId(
                savedRun.getId(), savedLine.getId());
        assertThat(byLine).isPresent();

        Optional<ReconciliationItem> byEffect = itemRepository.findByReconciliationRunIdAndInternalEffectTypeAndInternalEffectId(
                savedRun.getId(), ReconciliationEffectType.PAYMENT_TRANSACTION, effectId);
        assertThat(byEffect).isPresent();
    }

    @Test
    @DisplayName("Verify entity classes do not expose raw payload or sensitive counterparty account fields")
    void testEntityFieldSanity() {
        Field[] importFields = BankStatementImport.class.getDeclaredFields();
        for (Field f : importFields) {
            String name = f.getName().toLowerCase();
            assertThat(name).doesNotContain("rawpayload");
            assertThat(name).doesNotContain("rawresponse");
            assertThat(name).doesNotContain("counterparty");
        }

        Field[] lineFields = BankStatementLine.class.getDeclaredFields();
        for (Field f : lineFields) {
            String name = f.getName().toLowerCase();
            assertThat(name).doesNotContain("rawpayload");
            assertThat(name).doesNotContain("rawresponse");
            assertThat(name).doesNotContain("counterpartyaccount");
        }
    }
}
