package com.finx.reconciliation.service;

import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.BankTransactionRecord;
import com.finx.banking.provider.BankingProvider;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final AtomicLong RECON_SEQ = new AtomicLong(System.currentTimeMillis() % 1000000);

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationItemRepository itemRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final SettlementInstructionRepository settlementRepository;
    private final BankingProvider bankingProvider;
    private final AuditService auditService;

    public ReconciliationService(ReconciliationRunRepository runRepository,
                                 ReconciliationItemRepository itemRepository,
                                 PaymentTransactionRepository transactionRepository,
                                 SettlementInstructionRepository settlementRepository,
                                 BankingProvider bankingProvider,
                                 AuditService auditService) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.transactionRepository = transactionRepository;
        this.settlementRepository = settlementRepository;
        this.bankingProvider = bankingProvider;
        this.auditService = auditService;
    }

    @Transactional
    public ReconciliationRun triggerReconciliation(UserPrincipal currentUser) {
        String runRef = String.format("FINX-REC-%d-%06d",
                java.time.Year.now().getValue(), RECON_SEQ.incrementAndGet());

        ReconciliationRun run = new ReconciliationRun(runRef, bankingProvider.getProviderName(),
                currentUser != null ? currentUser.getId() : null);

        ReconciliationRun savedRun = runRepository.save(run);

        LocalDate today = LocalDate.now();
        List<BankTransactionRecord> bankRecords = bankingProvider.fetchTransactions(today.minusDays(30), today.plusDays(1));
        List<PaymentTransaction> internalTxns = transactionRepository.findAll();
        List<SettlementInstruction> settlements = settlementRepository.findAll();

        int matched = 0;
        int mismatch = 0;

        Set<String> matchedBankRefs = new HashSet<>();

        // Match internal transactions against bank records
        for (PaymentTransaction internalTx : internalTxns) {
            Optional<BankTransactionRecord> bankMatch = bankRecords.stream()
                    .filter(b -> (internalTx.getUtrNumber() != null && internalTx.getUtrNumber().equalsIgnoreCase(b.utrNumber())) ||
                            internalTx.getTransactionReference().equalsIgnoreCase(b.transactionReference()))
                    .findFirst();

            if (bankMatch.isPresent()) {
                BankTransactionRecord bankRecord = bankMatch.get();
                matchedBankRefs.add(bankRecord.transactionReference());

                if (internalTx.getAmount().compareTo(bankRecord.amount()) == 0) {
                    matched++;
                    createItem(savedRun.getId(), internalTx.getTransactionReference(), bankRecord.transactionReference(),
                            internalTx.getAmount(), bankRecord.amount(), ReconciliationItemStatus.MATCHED, "Matched successfully");
                } else {
                    mismatch++;
                    createItem(savedRun.getId(), internalTx.getTransactionReference(), bankRecord.transactionReference(),
                            internalTx.getAmount(), bankRecord.amount(), ReconciliationItemStatus.AMOUNT_MISMATCH,
                            "Amount mismatch: FinX=" + internalTx.getAmount() + " vs Bank=" + bankRecord.amount());
                }
            } else {
                mismatch++;
                createItem(savedRun.getId(), internalTx.getTransactionReference(), null,
                        internalTx.getAmount(), null, ReconciliationItemStatus.MISSING_EXTERNAL, "Internal transaction missing in bank feed");
            }
        }

        // Match bank records missing in internal records
        for (BankTransactionRecord b : bankRecords) {
            if (!matchedBankRefs.contains(b.transactionReference())) {
                boolean inSettlement = settlements.stream().anyMatch(s -> s.getSettlementReference().equalsIgnoreCase(b.transactionReference()) ||
                        (s.getUtrNumber() != null && s.getUtrNumber().equalsIgnoreCase(b.utrNumber())));
                if (inSettlement) {
                    matched++;
                    createItem(savedRun.getId(), b.transactionReference(), b.transactionReference(),
                            b.amount(), b.amount(), ReconciliationItemStatus.MATCHED, "Matched settlement record");
                } else {
                    mismatch++;
                    createItem(savedRun.getId(), null, b.transactionReference(),
                            null, b.amount(), ReconciliationItemStatus.MISSING_INTERNAL, "Bank feed transaction not found in FinX internal records");
                }
            }
        }

        savedRun.setTotalInternalTransactions(internalTxns.size());
        savedRun.setTotalBankTransactions(bankRecords.size());
        savedRun.setMatchedCount(matched);
        savedRun.setMismatchCount(mismatch);
        savedRun.setStatus(ReconciliationRunStatus.COMPLETED);
        savedRun.setEndTime(Instant.now());
        savedRun.setSummaryNotes("Reconciliation completed: " + matched + " matched, " + mismatch + " discrepancies detected.");

        ReconciliationRun finalRun = runRepository.save(savedRun);

        if (currentUser != null) {
            auditService.logEvent(
                    currentUser.getId(),
                    "RECONCILIATION_RUN_COMPLETED",
                    "RECONCILIATION",
                    finalRun.getId().toString(),
                    "Reconciliation run " + runRef + " completed: " + matched + " matched, " + mismatch + " mismatches"
            );
        }

        log.info("Reconciliation run completed: ref={} matched={} mismatch={}", runRef, matched, mismatch);
        return finalRun;
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRun> getRuns() {
        return runRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ReconciliationItem> getItemsForRun(UUID runId) {
        return itemRepository.findByReconciliationRunId(runId);
    }

    private void createItem(UUID runId, String finxRef, String bankRef, java.math.BigDecimal finxAmt,
                            java.math.BigDecimal bankAmt, ReconciliationItemStatus status, String details) {
        ReconciliationItem item = new ReconciliationItem(runId, finxRef, bankRef, finxAmt, bankAmt, status, details);
        itemRepository.save(item);
    }
}
