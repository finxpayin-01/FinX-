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
import java.util.Optional;
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

    public static final String SUPPORTED_CURRENCY = "INR";

    @Transactional
    public LedgerAccount ensureAccount(String accountNumber, String name, AccountType type, String currency) {
        String normalizedAccCurrency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase(java.util.Locale.ROOT) : SUPPORTED_CURRENCY;
        if (!SUPPORTED_CURRENCY.equals(normalizedAccCurrency)) {
            throw new BadRequestException("Unsupported account currency: '" + currency + "'. FinX currently supports " + SUPPORTED_CURRENCY + " only.");
        }
        return accountRepository.findByAccountNumber(accountNumber).orElseGet(() -> {
            LedgerAccount acc = new LedgerAccount(accountNumber, name, type, normalizedAccCurrency);
            return accountRepository.save(acc);
        });
    }

    @Transactional
    public JournalEntry postJournalEntry(PostJournalEntryRequest request) {
        if (request == null) {
            throw new BadRequestException("Journal entry request cannot be null");
        }
        if (request.lines() == null || request.lines().size() < 2) {
            throw new BadRequestException("Journal entry must have at least 2 balanced lines");
        }

        // B06: Currency consistency - FinX currently supports INR only
        if (request.currency() == null || request.currency().isBlank()) {
            throw new BadRequestException("Journal entry currency is required");
        }
        String normalizedCurrency = request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED_CURRENCY.equals(normalizedCurrency)) {
            throw new BadRequestException("Unsupported currency: '" + request.currency() + "'. FinX currently supports " + SUPPORTED_CURRENCY + " only.");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (JournalLineItem line : request.lines()) {
            if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("Journal line amount must be greater than zero");
            }
            if (line.entryType() == null) {
                throw new BadRequestException("Journal line entry type is required");
            }
            if (line.accountNumber() == null || line.accountNumber().isBlank()) {
                throw new BadRequestException("Journal line account number is required");
            }
            if (line.entryType() == EntryType.DEBIT) {
                totalDebit = totalDebit.add(line.amount());
            } else if (line.entryType() == EntryType.CREDIT) {
                totalCredit = totalCredit.add(line.amount());
            }
        }

        totalDebit = totalDebit.setScale(4, java.math.RoundingMode.HALF_UP);
        totalCredit = totalCredit.setScale(4, java.math.RoundingMode.HALF_UP);

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BadRequestException("Double-entry violation: Total DEBIT (" + totalDebit + ") must equal Total CREDIT (" + totalCredit + ")");
        }

        // B06: Journal source effect uniqueness check-before-write (backed by DB partial unique index)
        if (request.referenceId() != null) {
            Optional<JournalEntry> existing = entryRepository.findByTransactionTypeAndReferenceId(request.transactionType(), request.referenceId());
            if (existing.isPresent()) {
                throw new BadRequestException("Duplicate journal entry: Entry already exists for transaction type " +
                        request.transactionType() + " and reference " + request.referenceId());
            }
        }

        String entryReference = String.format("FINX-JRN-%d-%s",
                java.time.Year.now().getValue(),
                UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());

        JournalEntry entry = new JournalEntry(
                entryReference,
                request.transactionType(),
                request.referenceId(),
                request.dealId(),
                request.milestoneId(),
                totalDebit,
                normalizedCurrency,
                request.narration(),
                request.postedBy()
        );

        JournalEntry savedEntry = entryRepository.save(entry);

        // B11: Deterministic account lock ordering to eliminate lock inversion deadlocks.
        // Collect distinct account numbers and lock them in sorted order.
        List<String> sortedAccountNumbers = request.lines().stream()
                .map(JournalLineItem::accountNumber)
                .distinct()
                .sorted()
                .toList();

        java.util.Map<String, LedgerAccount> lockedAccounts = new java.util.HashMap<>();
        for (String accNum : sortedAccountNumbers) {
            LedgerAccount account = accountRepository.findByAccountNumberForUpdate(accNum)
                    .orElseThrow(() -> new ResourceNotFoundException("LedgerAccount", "accountNumber", accNum));

            // B06: Account currency validation
            if (!SUPPORTED_CURRENCY.equalsIgnoreCase(account.getCurrency())) {
                throw new BadRequestException("Currency mismatch: Account " + accNum + " has unsupported currency '" + account.getCurrency() + "'");
            }
            if (!normalizedCurrency.equalsIgnoreCase(account.getCurrency())) {
                throw new BadRequestException("Currency mismatch: Account " + accNum + " currency '" + account.getCurrency() + "' does not match journal currency '" + normalizedCurrency + "'");
            }

            lockedAccounts.put(accNum, account);
        }

        int seq = 1;
        for (JournalLineItem item : request.lines()) {
            LedgerAccount account = lockedAccounts.get(item.accountNumber());

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
                    "Posted balanced journal entry " + entryReference + " (" + totalDebit + " " + normalizedCurrency + ") for " + request.transactionType()
            );
        }

        log.info("Posted balanced journal entry: ref={} type={} amount={} currency={}",
                entryReference, request.transactionType(), totalDebit, normalizedCurrency);

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
