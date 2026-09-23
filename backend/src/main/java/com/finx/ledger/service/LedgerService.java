package com.finx.ledger.service;

import com.finx.audit.service.AuditService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.ledger.dto.LedgerDtos.*;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.entity.JournalLine;
import com.finx.ledger.entity.LedgerAccount;
import com.finx.ledger.enums.LedgerEnums.AccountType;
import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.JournalLineRepository;
import com.finx.ledger.repository.LedgerAccountRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final AtomicLong ENTRY_SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    public static final String ACC_ESCROW_POOL = "1010-ESCROW-POOL";
    public static final String ACC_BANK_NODAL = "1020-BANK-NODAL";
    public static final String ACC_ESCROW_LIABILITY = "2010-ESCROW-LIABILITY";
    public static final String ACC_VENDOR_PAYABLE = "2020-VENDOR-PAYABLE";
    public static final String ACC_FEE_REVENUE = "4010-FINX-FEE-REVENUE";

    private final LedgerAccountRepository accountRepository;
    private final JournalEntryRepository entryRepository;
    private final JournalLineRepository lineRepository;
    private final AuditService auditService;

    public LedgerService(LedgerAccountRepository accountRepository,
                         JournalEntryRepository entryRepository,
                         JournalLineRepository lineRepository,
                         AuditService auditService) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.lineRepository = lineRepository;
        this.auditService = auditService;
    }

    @PostConstruct
    @Transactional
    public void initSystemAccounts() {
        ensureAccount(ACC_ESCROW_POOL, "Escrow Pool Asset", AccountType.ASSET, "INR");
        ensureAccount(ACC_BANK_NODAL, "Bank Nodal Account", AccountType.ASSET, "INR");
        ensureAccount(ACC_ESCROW_LIABILITY, "Escrow Deal Liability", AccountType.LIABILITY, "INR");
        ensureAccount(ACC_VENDOR_PAYABLE, "Vendor Settlement Payable", AccountType.LIABILITY, "INR");
        ensureAccount(ACC_FEE_REVENUE, "FinX Platform Fee Revenue", AccountType.REVENUE, "INR");
    }

    @Transactional
    public LedgerAccount ensureAccount(String accountNumber, String name, AccountType type, String currency) {
        return accountRepository.findByAccountNumber(accountNumber).orElseGet(() -> {
            LedgerAccount acc = new LedgerAccount(accountNumber, name, type, currency);
            return accountRepository.save(acc);
        });
    }

    @Transactional
    public JournalEntry postJournalEntry(PostJournalEntryRequest request) {
        if (request.lines() == null || request.lines().size() < 2) {
            throw new BadRequestException("Journal entry must have at least 2 balanced lines");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (JournalLineItem line : request.lines()) {
            if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("Journal line amount must be greater than zero");
            }
            if (line.entryType() == EntryType.DEBIT) {
                totalDebit = totalDebit.add(line.amount());
            } else if (line.entryType() == EntryType.CREDIT) {
                totalCredit = totalCredit.add(line.amount());
            }
        }

        totalDebit = totalDebit.setScale(4);
        totalCredit = totalCredit.setScale(4);

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BadRequestException("Double-entry violation: Total DEBIT (" + totalDebit + ") must equal Total CREDIT (" + totalCredit + ")");
        }

        String entryReference = String.format("FINX-JRN-%d-%06d",
                java.time.Year.now().getValue(), ENTRY_SEQ.incrementAndGet());

        String currency = (request.currency() != null && !request.currency().isBlank()) ? request.currency() : "INR";

        JournalEntry entry = new JournalEntry(
                entryReference,
                request.transactionType(),
                request.referenceId(),
                request.dealId(),
                request.milestoneId(),
                totalDebit,
                currency,
                request.narration(),
                request.postedBy()
        );

        JournalEntry savedEntry = entryRepository.save(entry);

        int seq = 1;
        for (JournalLineItem item : request.lines()) {
            LedgerAccount account = accountRepository.findByAccountNumberForUpdate(item.accountNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount", "accountNumber", item.accountNumber()));

            // Update account balance:
            // For ASSET or EXPENSE: DEBIT increases balance, CREDIT decreases balance
            // For LIABILITY, EQUITY, REVENUE: CREDIT increases balance, DEBIT decreases balance
            BigDecimal currentBalance = account.getBalance();
            BigDecimal newBalance;

            if (account.getAccountType() == AccountType.ASSET || account.getAccountType() == AccountType.EXPENSE) {
                if (item.entryType() == EntryType.DEBIT) {
                    newBalance = currentBalance.add(item.amount());
                } else {
                    newBalance = currentBalance.subtract(item.amount());
                }
            } else {
                if (item.entryType() == EntryType.CREDIT) {
                    newBalance = currentBalance.add(item.amount());
                } else {
                    newBalance = currentBalance.subtract(item.amount());
                }
            }

            account.setBalance(newBalance);
            accountRepository.save(account);

            JournalLine line = new JournalLine(
                    savedEntry.getId(),
                    account.getId(),
                    item.entryType(),
                    item.amount(),
                    item.description(),
                    seq++
            );
            lineRepository.save(line);
        }

        if (request.postedBy() != null) {
            auditService.logEvent(
                    request.postedBy(),
                    "JOURNAL_ENTRY_POSTED",
                    "LEDGER",
                    savedEntry.getId().toString(),
                    "Posted balanced journal entry " + entryReference + " (" + totalDebit + " " + currency + ") for " + request.transactionType()
            );
        }

        log.info("Posted balanced journal entry: ref={} type={} amount={} currency={}",
                entryReference, request.transactionType(), totalDebit, currency);

        return savedEntry;
    }

    @Transactional(readOnly = true)
    public BigDecimal getAccountBalance(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(LedgerAccount::getBalance)
                .orElse(BigDecimal.ZERO.setScale(4));
    }

    @Transactional(readOnly = true)
    public List<JournalEntry> getJournalEntriesForDeal(UUID dealId) {
        return entryRepository.findByDealIdOrderByPostedAtDesc(dealId);
    }

    @Transactional(readOnly = true)
    public List<JournalEntry> getAllJournalEntries() {
        return entryRepository.findAll();
    }
}
