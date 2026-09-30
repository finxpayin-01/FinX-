package com.finx.reconciliation;

import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.BankTransactionRecord;
import com.finx.banking.provider.BankingProvider;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private ReconciliationRunRepository runRepository;
    @Mock
    private ReconciliationItemRepository itemRepository;
    @Mock
    private PaymentTransactionRepository transactionRepository;
    @Mock
    private SettlementInstructionRepository settlementRepository;
    @Mock
    private BankingProvider bankingProvider;
    @Mock
    private AuditService auditService;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(
                runRepository, itemRepository, transactionRepository,
                settlementRepository, bankingProvider, auditService
        );
        when(bankingProvider.getProviderName()).thenReturn("MOCK");
    }

    private final com.finx.security.service.UserPrincipal adminPrincipal = new com.finx.security.service.UserPrincipal(
            UUID.randomUUID(), "Admin", "admin@finx.com", "hash", com.finx.common.enums.Role.ADMIN, com.finx.common.enums.UserStatus.ACTIVE,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
    );

    @Test
    @DisplayName("Should detect MATCHED items when internal amount and bank amount agree")
    void shouldReconcileMatchedTransactions() {
        when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> {
            ReconciliationRun r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        PaymentTransaction tx = new PaymentTransaction(
                "FINX-TXN-101", UUID.randomUUID(), "MOCK-101",
                new BigDecimal("25000.00"), "INR", "SUCCESS", "UTR-101", null
        );
        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(settlementRepository.findAll()).thenReturn(Collections.emptyList());

        BankTransactionRecord bankRecord = new BankTransactionRecord(
                "FINX-TXN-101", "UTR-101", "FINX12345", new BigDecimal("25000.00"), "INR",
                "CREDIT", Instant.now(), "999888777", "Success transfer"
        );
        when(bankingProvider.fetchTransactions(any(), any())).thenReturn(List.of(bankRecord));

        ReconciliationRun run = reconciliationService.triggerReconciliation(adminPrincipal);

        assertThat(run).isNotNull();
        ArgumentCaptor<ReconciliationItem> itemCaptor = ArgumentCaptor.forClass(ReconciliationItem.class);
        verify(itemRepository).save(itemCaptor.capture());

        ReconciliationItem captured = itemCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(ReconciliationItemStatus.MATCHED);
        assertThat(captured.getFinxTransactionReference()).isEqualTo("FINX-TXN-101");
        assertThat(captured.getBankTransactionReference()).isEqualTo("FINX-TXN-101");
    }

    @Test
    @DisplayName("Should flag AMOUNT_MISMATCH when internal amount differs from bank statement")
    void shouldFlagAmountMismatch() {
        when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> {
            ReconciliationRun r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        PaymentTransaction tx = new PaymentTransaction(
                "FINX-TXN-102", UUID.randomUUID(), "MOCK-102",
                new BigDecimal("50000.00"), "INR", "SUCCESS", "UTR-102", null
        );
        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(settlementRepository.findAll()).thenReturn(Collections.emptyList());

        // Bank record has different amount (e.g. 49000 instead of 50000)
        BankTransactionRecord bankRecord = new BankTransactionRecord(
                "FINX-TXN-102", "UTR-102", "FINX12345", new BigDecimal("49000.00"), "INR",
                "CREDIT", Instant.now(), "999888777", "Different amount"
        );
        when(bankingProvider.fetchTransactions(any(), any())).thenReturn(List.of(bankRecord));

        reconciliationService.triggerReconciliation(adminPrincipal);

        ArgumentCaptor<ReconciliationItem> itemCaptor = ArgumentCaptor.forClass(ReconciliationItem.class);
        verify(itemRepository).save(itemCaptor.capture());

        ReconciliationItem captured = itemCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(ReconciliationItemStatus.AMOUNT_MISMATCH);
        assertThat(captured.getDiscrepancyDetails()).contains("Amount mismatch");
    }

    @Test
    @DisplayName("Should flag MISSING_EXTERNAL when internal txn has no corresponding bank feed item")
    void shouldFlagMissingExternal() {
        when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> {
            ReconciliationRun r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        PaymentTransaction tx = new PaymentTransaction(
                "FINX-TXN-103", UUID.randomUUID(), "MOCK-103",
                new BigDecimal("10000.00"), "INR", "SUCCESS", "UTR-103", null
        );
        when(transactionRepository.findAll()).thenReturn(List.of(tx));
        when(settlementRepository.findAll()).thenReturn(Collections.emptyList());
        when(bankingProvider.fetchTransactions(any(), any())).thenReturn(Collections.emptyList());

        reconciliationService.triggerReconciliation(adminPrincipal);

        ArgumentCaptor<ReconciliationItem> itemCaptor = ArgumentCaptor.forClass(ReconciliationItem.class);
        verify(itemRepository).save(itemCaptor.capture());

        ReconciliationItem captured = itemCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(ReconciliationItemStatus.MISSING_EXTERNAL);
    }
}
