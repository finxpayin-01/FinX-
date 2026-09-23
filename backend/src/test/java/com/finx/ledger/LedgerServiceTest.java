package com.finx.ledger;

import com.finx.audit.service.AuditService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerAccountRepository accountRepository;

    @Mock
    private JournalEntryRepository entryRepository;

    @Mock
    private JournalLineRepository lineRepository;

    @Mock
    private AuditService auditService;

    private LedgerService ledgerService;

    private LedgerAccount escrowAccount;
    private LedgerAccount liabilityAccount;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(accountRepository, entryRepository, lineRepository, auditService);

        escrowAccount = new LedgerAccount("1010-ESCROW-POOL", "Escrow Pool Asset", AccountType.ASSET, "INR");
        escrowAccount.setId(UUID.randomUUID());

        liabilityAccount = new LedgerAccount("2010-ESCROW-LIABILITY", "Escrow Deal Liability", AccountType.LIABILITY, "INR");
        liabilityAccount.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Should post balanced journal entry successfully when Debit equals Credit")
    void shouldPostBalancedJournalEntry() {
        when(accountRepository.findByAccountNumberForUpdate("1010-ESCROW-POOL")).thenReturn(Optional.of(escrowAccount));
        when(accountRepository.findByAccountNumberForUpdate("2010-ESCROW-LIABILITY")).thenReturn(Optional.of(liabilityAccount));
        when(entryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry je = invocation.getArgument(0);
            je.setId(UUID.randomUUID());
            return je;
        });

        BigDecimal amount = new BigDecimal("100000.0000");
        PostJournalEntryRequest request = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "INR",
                "Buyer milestone funding deposit",
                UUID.randomUUID(),
                List.of(
                        new JournalLineItem("1010-ESCROW-POOL", EntryType.DEBIT, amount, "Debit Escrow Pool"),
                        new JournalLineItem("2010-ESCROW-LIABILITY", EntryType.CREDIT, amount, "Credit Escrow Liability")
                )
        );

        JournalEntry result = ledgerService.postJournalEntry(request);

        assertThat(result).isNotNull();
        assertThat(result.getTotalDebit()).isEqualByComparingTo(amount);
        assertThat(result.getTotalCredit()).isEqualByComparingTo(amount);
        assertThat(result.getEntryReference()).startsWith("FINX-JRN-");
        verify(entryRepository).save(any(JournalEntry.class));
        verify(lineRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Should throw BadRequestException when total Debit does not equal total Credit")
    void shouldRejectUnbalancedJournalEntry() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "INR",
                "Unbalanced deposit attempt",
                UUID.randomUUID(),
                List.of(
                        new JournalLineItem("1010-ESCROW-POOL", EntryType.DEBIT, new BigDecimal("100000.00"), "Debit 1L"),
                        new JournalLineItem("2010-ESCROW-LIABILITY", EntryType.CREDIT, new BigDecimal("99000.00"), "Credit 99k")
                )
        );

        assertThatThrownBy(() -> ledgerService.postJournalEntry(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Double-entry violation: Total DEBIT");

        verifyNoInteractions(entryRepository);
        verifyNoInteractions(lineRepository);
    }

    @Test
    @DisplayName("Should throw BadRequestException if journal entry has fewer than 2 lines")
    void shouldRejectSingleLineJournalEntry() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "INR",
                "Single line attempt",
                UUID.randomUUID(),
                List.of(
                        new JournalLineItem("1010-ESCROW-POOL", EntryType.DEBIT, new BigDecimal("100000.00"), "Single Debit")
                )
        );

        assertThatThrownBy(() -> ledgerService.postJournalEntry(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least 2 balanced lines");
    }

    @Test
    @DisplayName("Should throw BadRequestException when line amount is zero or negative")
    void shouldRejectNegativeLineAmount() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
                LedgerTransactionType.ESCROW_DEPOSIT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "INR",
                "Negative amount attempt",
                UUID.randomUUID(),
                List.of(
                        new JournalLineItem("1010-ESCROW-POOL", EntryType.DEBIT, new BigDecimal("-500.00"), "Negative Debit"),
                        new JournalLineItem("2010-ESCROW-LIABILITY", EntryType.CREDIT, new BigDecimal("-500.00"), "Negative Credit")
                )
        );

        assertThatThrownBy(() -> ledgerService.postJournalEntry(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("amount must be greater than zero");
    }
}
