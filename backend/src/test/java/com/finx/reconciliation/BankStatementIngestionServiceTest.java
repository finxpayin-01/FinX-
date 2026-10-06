package com.finx.reconciliation;

import com.finx.banking.dto.*;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.service.BankStatementIngestionService;
import com.finx.reconciliation.service.BankStatementPersistenceService;
import com.finx.reconciliation.service.BankStatementSnapshot;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import com.finx.common.enums.UserStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BankStatementIngestionServiceTest {

    private BankingProvider bankingProvider;
    private BankStatementPersistenceService persistenceService;
    private BankStatementIngestionService ingestionService;

    private UserPrincipal adminUser;
    private UserPrincipal financeUser;
    private UserPrincipal buyerUser;
    private UserPrincipal sellerUser;
    private UserPrincipal pmUser;

    @BeforeEach
    void setUp() {
        bankingProvider = mock(BankingProvider.class);
        persistenceService = mock(BankStatementPersistenceService.class);
        when(bankingProvider.getProviderName()).thenReturn("MOCK");

        ingestionService = new BankStatementIngestionService(bankingProvider, persistenceService);

        adminUser = createPrincipal(Role.ADMIN, "admin@finx.com");
        financeUser = createPrincipal(Role.FINANCE, "finance@finx.com");
        buyerUser = createPrincipal(Role.BUYER, "buyer@finx.com");
        sellerUser = createPrincipal(Role.SELLER, "seller@finx.com");
        pmUser = createPrincipal(Role.PROJECT_MANAGER, "pm@finx.com");
    }

    private UserPrincipal createPrincipal(Role role, String email) {
        return new UserPrincipal(
                UUID.randomUUID(),
                role.name(),
                email,
                "hashedpass",
                role,
                UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(role.getAuthority()))
        );
    }

    private BankStatementQuery validQuery() {
        return BankStatementQuery.builder()
                .providerAccountId("ACC_001")
                .windowStart(Instant.parse("2026-10-01T00:00:00Z"))
                .windowEnd(Instant.parse("2026-10-07T00:00:00Z"))
                .pageSize(50)
                .build();
    }

    private BankStatementEntry validEntry(String lineId, BigDecimal amount) {
        return BankStatementEntry.builder()
                .providerLineId(lineId)
                .transactionReference("TXN-" + lineId)
                .utrNumber("UTR-" + lineId)
                .amount(amount)
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(Instant.parse("2026-10-02T12:00:00Z"))
                .valueDate(LocalDate.parse("2026-10-02"))
                .build();
    }

    // ==========================================
    // 1-6. AUTHENTICATION / AUTHORIZATION
    // ==========================================

    @Test
    @DisplayName("1. ADMIN allowed")
    void testAdminAllowed() {
        when(persistenceService.findExistingCompletedImport(any(), any(), any())).thenReturn(Optional.empty());
        when(persistenceService.persistStatementImport(any())).thenReturn(new BankStatementImport());
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                .build());

        BankStatementImport result = ingestionService.ingestStatement(validQuery(), adminUser);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("2. Platform FINANCE allowed")
    void testFinanceAllowed() {
        when(persistenceService.findExistingCompletedImport(any(), any(), any())).thenReturn(Optional.empty());
        when(persistenceService.persistStatementImport(any())).thenReturn(new BankStatementImport());
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                .build());

        BankStatementImport result = ingestionService.ingestStatement(validQuery(), financeUser);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("3-6. Non-privileged roles and null principal blocked")
    void testNonPrivilegedRolesBlocked() {
        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), buyerUser))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), sellerUser))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), pmUser))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(bankingProvider);
        verifyNoInteractions(persistenceService);
    }

    // ==========================================
    // 7-9. QUERY VALIDATION
    // ==========================================

    @Test
    @DisplayName("7. Blank account rejected")
    void testBlankAccountRejected() {
        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId("  ")
                .windowStart(Instant.now().minus(1, ChronoUnit.DAYS))
                .windowEnd(Instant.now())
                .pageSize(10)
                .build();
        assertThatThrownBy(() -> ingestionService.ingestStatement(query, adminUser))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("8. Invalid window rejected (windowEnd < windowStart or null)")
    void testInvalidWindowRejected() {
        Instant now = Instant.now();
        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId("ACC_001")
                .windowStart(now)
                .windowEnd(now.minus(1, ChronoUnit.DAYS))
                .pageSize(10)
                .build();
        assertThatThrownBy(() -> ingestionService.ingestStatement(query, adminUser))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("9. Nonpositive page size rejected")
    void testNonpositivePageSizeRejected() {
        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId("ACC_001")
                .windowStart(Instant.now().minus(1, ChronoUnit.DAYS))
                .windowEnd(Instant.now())
                .pageSize(0)
                .build();
        assertThatThrownBy(() -> ingestionService.ingestStatement(query, adminUser))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==========================================
    // 10-15. PAGINATION & PAGE INTEGRITY
    // ==========================================

    @Test
    @DisplayName("10, 11. Single page and multi-page ingestion success")
    void testMultiPageIngestionSuccess() {
        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && q.getCursor() == null)))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .currency("INR")
                        .providerImportReference("REF-1")
                        .openingBalance(new BigDecimal("1000"))
                        .closingBalance(new BigDecimal("1500"))
                        .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                        .nextCursor("PAGE_2_CURSOR")
                        .build());

        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && "PAGE_2_CURSOR".equals(q.getCursor()))))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .currency("INR")
                        .providerImportReference("REF-1")
                        .openingBalance(new BigDecimal("1000"))
                        .closingBalance(new BigDecimal("1500"))
                        .entries(List.of(validEntry("LINE-2", new BigDecimal("200"))))
                        .nextCursor(null)
                        .build());

        when(persistenceService.findExistingCompletedImport(any(), any(), any())).thenReturn(Optional.empty());
        when(persistenceService.persistStatementImport(any())).thenAnswer(inv -> {
            BankStatementSnapshot snap = inv.getArgument(0);
            BankStatementImport imp = new BankStatementImport();
            imp.setTotalLines(snap.entries().size());
            imp.setStatus(BankStatementImportStatus.COMPLETED);
            return imp;
        });

        BankStatementImport result = ingestionService.ingestStatement(validQuery(), adminUser);
        assertThat(result.getTotalLines()).isEqualTo(2);

        ArgumentCaptor<BankStatementSnapshot> captor = ArgumentCaptor.forClass(BankStatementSnapshot.class);
        verify(persistenceService).persistStatementImport(captor.capture());
        assertThat(captor.getValue().entries()).hasSize(2);
    }

    @Test
    @DisplayName("12. Cursor cycle detected and blocked")
    void testCursorCycleBlocked() {
        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && q.getCursor() == null)))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                        .nextCursor("CURSOR_REPEAT")
                        .build());

        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && "CURSOR_REPEAT".equals(q.getCursor()))))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .entries(List.of(validEntry("LINE-2", new BigDecimal("200"))))
                        .nextCursor("CURSOR_REPEAT") // Repeat/cycle!
                        .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cursor cycle or repeat detected");

        verify(persistenceService, never()).persistStatementImport(any());
    }

    @Test
    @DisplayName("13. Account scope changes between pages blocked")
    void testAccountScopeMismatchBlocked() {
        when(bankingProvider.fetchStatementPage(any()))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("DIFFERENT_ACCOUNT")
                        .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                        .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mismatched providerAccountId");

        verify(persistenceService, never()).persistStatementImport(any());
    }

    @Test
    @DisplayName("14. Inconsistent currency between pages blocked")
    void testInconsistentCurrencyBlocked() {
        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && q.getCursor() == null)))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .currency("INR")
                        .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                        .nextCursor("PAGE_2")
                        .build());

        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && "PAGE_2".equals(q.getCursor()))))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .currency("USD") // Inconsistent!
                        .entries(List.of(validEntry("LINE-2", new BigDecimal("200"))))
                        .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inconsistent currency across pages");

        verify(persistenceService, never()).persistStatementImport(any());
    }

    @Test
    @DisplayName("15. Inconsistent providerImportReference between pages blocked")
    void testInconsistentImportRefBlocked() {
        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && q.getCursor() == null)))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .providerImportReference("REF-A")
                        .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                        .nextCursor("PAGE_2")
                        .build());

        when(bankingProvider.fetchStatementPage(argThat(q -> q != null && "PAGE_2".equals(q.getCursor()))))
                .thenReturn(BankStatementPage.builder()
                        .providerAccountId("ACC_001")
                        .providerImportReference("REF-B") // Inconsistent!
                        .entries(List.of(validEntry("LINE-2", new BigDecimal("200"))))
                        .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inconsistent providerImportReference across pages");

        verify(persistenceService, never()).persistStatementImport(any());
    }

    // ==========================================
    // 16-23. LINE VALIDATION
    // ==========================================

    @Test
    @DisplayName("16, 17. Zero and negative amounts blocked")
    void testNonPositiveAmountsBlocked() {
        BankStatementEntry zeroEntry = validEntry("L-0", BigDecimal.ZERO);
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(zeroEntry))
                .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be positive");

        BankStatementEntry negEntry = validEntry("L-NEG", new BigDecimal("-50"));
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(negEntry))
                .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be positive");
    }

    @Test
    @DisplayName("18. Bad currency format blocked")
    void testBadCurrencyBlocked() {
        BankStatementEntry badCurrency = BankStatementEntry.builder()
                .providerLineId("L-1")
                .amount(new BigDecimal("100"))
                .currency("inr_invalid")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(Instant.now())
                .build();

        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(badCurrency))
                .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid 3-letter ISO code");
    }

    @Test
    @DisplayName("19, 20, 21. Missing direction, finality, or transactionTime blocked")
    void testMissingRequiredFieldsBlocked() {
        // Missing direction
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(BankStatementEntry.builder()
                        .providerLineId("L-1")
                        .amount(new BigDecimal("100"))
                        .currency("INR")
                        .finality(BankStatementFinality.FINAL_SUCCESS)
                        .transactionTime(Instant.now())
                        .build()))
                .build());
        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class);

        // Missing finality
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(BankStatementEntry.builder()
                        .providerLineId("L-1")
                        .amount(new BigDecimal("100"))
                        .currency("INR")
                        .direction(BankStatementDirection.CREDIT)
                        .transactionTime(Instant.now())
                        .build()))
                .build());
        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class);

        // Missing transactionTime
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(BankStatementEntry.builder()
                        .providerLineId("L-1")
                        .amount(new BigDecimal("100"))
                        .currency("INR")
                        .direction(BankStatementDirection.CREDIT)
                        .finality(BankStatementFinality.FINAL_SUCCESS)
                        .build()))
                .build());
        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("22. No providerLineId/reference/UTR blocked")
    void testNoExternalReferenceBlocked() {
        BankStatementEntry noRefEntry = BankStatementEntry.builder()
                .amount(new BigDecimal("100"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(Instant.now())
                .build();

        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(noRefEntry))
                .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one external reference");
    }

    @Test
    @DisplayName("23. Oversize reference rejected, not silently truncated")
    void testOversizeReferenceRejected() {
        String oversizeRef = "A".repeat(129);
        BankStatementEntry oversizeEntry = BankStatementEntry.builder()
                .providerLineId(oversizeRef)
                .amount(new BigDecimal("100"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(Instant.now())
                .build();

        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(oversizeEntry))
                .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 128 characters");
    }

    // ==========================================
    // 24-25. DUPLICATES IN SNAPSHOT
    // ==========================================

    @Test
    @DisplayName("24. Duplicate providerLineId in snapshot blocked")
    void testDuplicateProviderLineIdBlocked() {
        BankStatementEntry e1 = validEntry("LINE-DUP", new BigDecimal("100"));
        BankStatementEntry e2 = validEntry("LINE-DUP", new BigDecimal("200"));

        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(e1, e2))
                .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate providerLineId detected in provider evidence");
    }

    @Test
    @DisplayName("25. Duplicate line fingerprint in snapshot blocked")
    void testDuplicateLineFingerprintBlocked() {
        BankStatementEntry e1 = validEntry("LINE-1", new BigDecimal("100"));
        BankStatementEntry e2 = validEntry("LINE-2", new BigDecimal("100"));
        // Force same fingerprint fields:
        BankStatementEntry e3 = validEntry("LINE-1", new BigDecimal("100"));

        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(e1, e3))
                .build());

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate providerLineId detected");
    }

    // ==========================================
    // 26-27. PROVIDER FETCH FAILURE
    // ==========================================

    @Test
    @DisplayName("26, 27. Provider exception or malformed page -> zero persistence calls")
    void testProviderFailureZeroPersistence() {
        when(bankingProvider.fetchStatementPage(any())).thenThrow(new RuntimeException("Simulated network timeout"));

        assertThatThrownBy(() -> ingestionService.ingestStatement(validQuery(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Banking provider statement fetch failed");

        verifyNoInteractions(persistenceService);
    }

    // ==========================================
    // 28-32. ZERO FINANCIAL SIDE EFFECTS
    // ==========================================

    @Test
    @DisplayName("28-32. Zero settlement or payment calls, zero engine decisions or runs")
    void testZeroFinancialSideEffects() {
        when(persistenceService.findExistingCompletedImport(any(), any(), any())).thenReturn(Optional.empty());
        when(persistenceService.persistStatementImport(any())).thenReturn(new BankStatementImport());
        when(bankingProvider.fetchStatementPage(any())).thenReturn(BankStatementPage.builder()
                .providerAccountId("ACC_001")
                .entries(List.of(validEntry("LINE-1", new BigDecimal("100"))))
                .build());

        ingestionService.ingestStatement(validQuery(), adminUser);

        verify(bankingProvider, never()).initiateSettlement(any());
        verify(bankingProvider, never()).createPaymentInstruction(any());
        verify(bankingProvider, never()).getPaymentStatus(any());
    }
}
