package com.finx.reconciliation.service;

import com.finx.banking.dto.BankStatementEntry;
import com.finx.banking.dto.BankStatementFinality;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BankStatementPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(BankStatementPersistenceService.class);

    private final BankStatementImportRepository importRepository;
    private final BankStatementLineRepository lineRepository;

    public BankStatementPersistenceService(BankStatementImportRepository importRepository,
                                           BankStatementLineRepository lineRepository) {
        this.importRepository = importRepository;
        this.lineRepository = lineRepository;
    }

    @Transactional(readOnly = true)
    public Optional<BankStatementImport> findExistingCompletedImport(String provider, String providerAccountId, String sourceHash) {
        return importRepository.findByProviderAndProviderAccountIdAndSourceHash(provider, providerAccountId, sourceHash)
                .filter(imp -> imp.getStatus() == BankStatementImportStatus.COMPLETED);
    }

    @Transactional
    public BankStatementImport persistStatementImport(BankStatementSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("BankStatementSnapshot cannot be null");
        }

        // 1. Check if an import with same provider, providerAccountId, and sourceHash already exists
        Optional<BankStatementImport> existingOpt = importRepository.findByProviderAndProviderAccountIdAndSourceHash(
                snapshot.provider(), snapshot.providerAccountId(), snapshot.sourceHash());

        if (existingOpt.isPresent()) {
            BankStatementImport existing = existingOpt.get();
            if (existing.getStatus() == BankStatementImportStatus.COMPLETED) {
                log.info("Idempotent replay detected for import provider={}, account={}, sourceHash={}. Returning existing import id={}",
                        snapshot.provider(), snapshot.providerAccountId(), snapshot.sourceHash(), existing.getId());
                return existing;
            } else {
                throw new IllegalStateException("Existing bank statement import with source hash is in non-completed state: " + existing.getStatus());
            }
        }

        // 2. Create and persist BankStatementImport
        BankStatementImport importEntity = new BankStatementImport(
                snapshot.provider(),
                snapshot.providerAccountId(),
                snapshot.windowStart(),
                snapshot.windowEnd(),
                snapshot.sourceHash(),
                snapshot.openingBalance(),
                snapshot.closingBalance(),
                snapshot.currency() != null ? snapshot.currency().toUpperCase().trim() : "INR",
                snapshot.importedBy()
        );
        importEntity.setProviderImportReference(snapshot.providerImportReference());
        importEntity.setTotalLines(snapshot.entries() != null ? snapshot.entries().size() : 0);
        importEntity.setStatus(BankStatementImportStatus.COMPLETED);
        importEntity.setCompletedAt(Instant.now());

        BankStatementImport savedImport = importRepository.save(importEntity);

        // 3. Create and persist BankStatementLine rows
        if (snapshot.entries() != null && !snapshot.entries().isEmpty()) {
            List<BankStatementLine> linesToSave = new ArrayList<>(snapshot.entries().size());
            for (BankStatementEntry entry : snapshot.entries()) {
                String lineFingerprint = BankStatementEvidenceFingerprint.computeLineFingerprint(
                        snapshot.provider(), snapshot.providerAccountId(), entry);

                FinancialDirection direction = entry.getDirection() != null
                        ? FinancialDirection.valueOf(entry.getDirection().name())
                        : FinancialDirection.CREDIT;

                BankStatementLineStatus normalizedStatus = mapFinality(entry.getFinality());

                BankStatementLine line = new BankStatementLine(
                        savedImport.getId(),
                        snapshot.provider(),
                        snapshot.providerAccountId(),
                        entry.getProviderLineId(),
                        entry.getTransactionReference(),
                        entry.getUtrNumber(),
                        entry.getAmount(),
                        entry.getCurrency() != null ? entry.getCurrency().toUpperCase().trim() : savedImport.getCurrency(),
                        direction,
                        entry.getProviderStatus(),
                        normalizedStatus,
                        entry.getTransactionTime(),
                        entry.getValueDate(),
                        lineFingerprint
                );
                linesToSave.add(line);
            }
            lineRepository.saveAll(linesToSave);
        }

        log.info("Successfully persisted durable bank statement import id={}, totalLines={}",
                savedImport.getId(), savedImport.getTotalLines());

        return savedImport;
    }

    private BankStatementLineStatus mapFinality(BankStatementFinality finality) {
        if (finality == null) {
            return BankStatementLineStatus.UNKNOWN;
        }
        return switch (finality) {
            case FINAL_SUCCESS -> BankStatementLineStatus.FINAL_SUCCESS;
            case PENDING -> BankStatementLineStatus.PENDING;
            case FAILED -> BankStatementLineStatus.FAILED;
            case REVERSED -> BankStatementLineStatus.REVERSED;
            case UNKNOWN -> BankStatementLineStatus.UNKNOWN;
        };
    }
}
