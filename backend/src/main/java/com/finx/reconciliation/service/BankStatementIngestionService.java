package com.finx.reconciliation.service;

import com.finx.banking.dto.*;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class BankStatementIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BankStatementIngestionService.class);
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final int MAX_PAGE_ITERATIONS = 1000;

    private final BankingProvider bankingProvider;
    private final BankStatementPersistenceService persistenceService;

    public BankStatementIngestionService(BankingProvider bankingProvider,
                                         BankStatementPersistenceService persistenceService) {
        this.bankingProvider = bankingProvider;
        this.persistenceService = persistenceService;
    }

    public BankStatementImport ingestStatement(BankStatementQuery initialQuery, UserPrincipal currentUser) {
        // 1. Authorization
        requirePlatformAdminOrFinance(currentUser);

        // 2. Query validation
        validateQuery(initialQuery);

        String providerName = bankingProvider.getProviderName();
        String expectedAccountId = initialQuery.getProviderAccountId();

        // 3. Un-transactional page fetching across cursors
        List<BankStatementEntry> collectedEntries = new ArrayList<>();
        Set<String> visitedCursors = new HashSet<>();
        String currentCursor = initialQuery.getCursor();

        String expectedCurrency = null;
        String expectedImportReference = null;
        BigDecimal openingBalance = null;
        BigDecimal closingBalance = null;

        int pageCount = 0;
        while (true) {
            pageCount++;
            if (pageCount > MAX_PAGE_ITERATIONS) {
                throw new IllegalStateException("Exceeded maximum pagination iterations (" + MAX_PAGE_ITERATIONS + ")");
            }

            if (currentCursor != null && !currentCursor.isBlank()) {
                if (visitedCursors.contains(currentCursor)) {
                    throw new IllegalStateException("Cursor cycle or repeat detected: " + currentCursor);
                }
                visitedCursors.add(currentCursor);
            }

            BankStatementQuery queryForPage = BankStatementQuery.builder()
                    .providerAccountId(expectedAccountId)
                    .windowStart(initialQuery.getWindowStart())
                    .windowEnd(initialQuery.getWindowEnd())
                    .cursor(currentCursor)
                    .pageSize(initialQuery.getPageSize())
                    .build();

            BankStatementPage page;
            try {
                page = bankingProvider.fetchStatementPage(queryForPage);
            } catch (Exception e) {
                log.error("Failed to fetch statement page from banking provider: {}", e.getMessage());
                throw new IllegalStateException("Banking provider statement fetch failed: " + e.getMessage(), e);
            }

            if (page == null) {
                throw new IllegalStateException("Banking provider returned null statement page");
            }

            // Validate page metadata consistency
            if (!expectedAccountId.equals(page.getProviderAccountId())) {
                throw new IllegalStateException("Provider returned mismatched providerAccountId: expected "
                        + expectedAccountId + " but got " + page.getProviderAccountId());
            }

            if (page.getCurrency() != null && !page.getCurrency().isBlank()) {
                String pageCurrency = page.getCurrency().toUpperCase().trim();
                if (expectedCurrency == null) {
                    expectedCurrency = pageCurrency;
                } else if (!expectedCurrency.equals(pageCurrency)) {
                    throw new IllegalStateException("Inconsistent currency across pages: expected "
                            + expectedCurrency + " but got " + pageCurrency);
                }
            }

            if (page.getProviderImportReference() != null && !page.getProviderImportReference().isBlank()) {
                String pageRef = page.getProviderImportReference().trim();
                if (expectedImportReference == null) {
                    expectedImportReference = pageRef;
                } else if (!expectedImportReference.equals(pageRef)) {
                    throw new IllegalStateException("Inconsistent providerImportReference across pages: expected "
                            + expectedImportReference + " but got " + pageRef);
                }
            }

            if (openingBalance == null) {
                openingBalance = page.getOpeningBalance();
            }
            if (page.getClosingBalance() != null) {
                closingBalance = page.getClosingBalance();
            }

            if (page.getEntries() != null) {
                collectedEntries.addAll(page.getEntries());
            }

            String nextCursor = page.getNextCursor();
            if (nextCursor == null || nextCursor.isBlank()) {
                break;
            }
            currentCursor = nextCursor;
        }

        if (expectedCurrency == null) {
            expectedCurrency = "INR";
        }

        // 4. Validate snapshot entries & detect duplicates within the snapshot
        Set<String> seenProviderLineIds = new HashSet<>();
        Set<String> seenFingerprints = new HashSet<>();
        List<String> lineFingerprints = new ArrayList<>(collectedEntries.size());

        for (BankStatementEntry entry : collectedEntries) {
            validateEntry(entry);

            if (entry.getProviderLineId() != null && !entry.getProviderLineId().isBlank()) {
                String lineId = entry.getProviderLineId().trim();
                if (!seenProviderLineIds.add(lineId)) {
                    throw new IllegalStateException("Duplicate providerLineId detected in provider evidence: " + lineId);
                }
            }

            String fingerprint = BankStatementEvidenceFingerprint.computeLineFingerprint(
                    providerName, expectedAccountId, entry);

            if (!seenFingerprints.add(fingerprint)) {
                throw new IllegalStateException("Duplicate statement line fingerprint detected in provider evidence: " + fingerprint);
            }

            lineFingerprints.add(fingerprint);
        }

        // 5. Compute source hash
        String sourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                providerName,
                expectedAccountId,
                expectedCurrency,
                openingBalance,
                closingBalance,
                expectedImportReference,
                lineFingerprints,
                initialQuery.getWindowStart(),
                initialQuery.getWindowEnd()
        );

        // 6. Fast-path check for existing completed import
        Optional<BankStatementImport> existing = persistenceService.findExistingCompletedImport(
                providerName, expectedAccountId, sourceHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 7. Atomic persistence
        BankStatementSnapshot snapshot = new BankStatementSnapshot(
                providerName,
                expectedAccountId,
                initialQuery.getWindowStart(),
                initialQuery.getWindowEnd(),
                sourceHash,
                openingBalance,
                closingBalance,
                expectedCurrency,
                expectedImportReference,
                collectedEntries,
                currentUser != null ? currentUser.getId() : null
        );

        try {
            return persistenceService.persistStatementImport(snapshot);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert race condition check
            Optional<BankStatementImport> rechecked = persistenceService.findExistingCompletedImport(
                    providerName, expectedAccountId, sourceHash);
            if (rechecked.isPresent()) {
                return rechecked.get();
            }
            throw e;
        }
    }

    private void requirePlatformAdminOrFinance(UserPrincipal currentUser) {
        if (currentUser == null || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE)) {
            throw new AccessDeniedException("Platform ADMIN or FINANCE role required for bank statement ingestion operations");
        }
    }

    private void validateQuery(BankStatementQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("BankStatementQuery cannot be null");
        }
        if (query.getProviderAccountId() == null || query.getProviderAccountId().isBlank()) {
            throw new IllegalArgumentException("providerAccountId must not be blank");
        }
        if (query.getProviderAccountId().length() > 128) {
            throw new IllegalArgumentException("providerAccountId length cannot exceed 128 chars");
        }
        if (query.getWindowStart() == null || query.getWindowEnd() == null) {
            throw new IllegalArgumentException("windowStart and windowEnd must not be null");
        }
        if (query.getWindowEnd().isBefore(query.getWindowStart())) {
            throw new IllegalArgumentException("windowEnd cannot be before windowStart");
        }
        if (query.getPageSize() <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
    }

    private void validateEntry(BankStatementEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("BankStatementEntry cannot be null");
        }
        if (entry.getAmount() == null || entry.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Entry amount must be positive");
        }
        if (entry.getCurrency() == null || !CURRENCY_PATTERN.matcher(entry.getCurrency().trim().toUpperCase()).matches()) {
            throw new IllegalArgumentException("Entry currency must be a valid 3-letter ISO code: " + entry.getCurrency());
        }
        if (entry.getDirection() == null) {
            throw new IllegalArgumentException("Entry direction cannot be null");
        }
        if (entry.getFinality() == null) {
            throw new IllegalArgumentException("Entry finality cannot be null");
        }
        if (entry.getTransactionTime() == null) {
            throw new IllegalArgumentException("Entry transactionTime cannot be null");
        }

        boolean hasProviderLineId = entry.getProviderLineId() != null && !entry.getProviderLineId().isBlank();
        boolean hasTxnRef = entry.getTransactionReference() != null && !entry.getTransactionReference().isBlank();
        boolean hasUtr = entry.getUtrNumber() != null && !entry.getUtrNumber().isBlank();

        if (!hasProviderLineId && !hasTxnRef && !hasUtr) {
            throw new IllegalArgumentException("Entry must have at least one external reference (providerLineId, transactionReference, or utrNumber)");
        }

        if (hasProviderLineId && entry.getProviderLineId().length() > 128) {
            throw new IllegalArgumentException("providerLineId exceeds 128 characters: " + entry.getProviderLineId());
        }
        if (hasTxnRef && entry.getTransactionReference().length() > 128) {
            throw new IllegalArgumentException("transactionReference exceeds 128 characters: " + entry.getTransactionReference());
        }
        if (hasUtr && entry.getUtrNumber().length() > 128) {
            throw new IllegalArgumentException("utrNumber exceeds 128 characters: " + entry.getUtrNumber());
        }
        if (entry.getProviderStatus() != null && entry.getProviderStatus().length() > 64) {
            throw new IllegalArgumentException("providerStatus exceeds 64 characters: " + entry.getProviderStatus());
        }
    }
}
