package com.finx.reconciliation.service;

import com.finx.common.enums.Role;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.dto.DeterministicReconciliationResult;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.util.*;

@Service
public class DeterministicReconciliationMatcher {

    private static final Logger log = LoggerFactory.getLogger(DeterministicReconciliationMatcher.class);
    public static final String MATCHER_VERSION_V1 = "FINX_RECONCILIATION_MATCHER_V1";
    public static final String MATCHER_VERSION_V2 = "FINX_RECONCILIATION_MATCHER_V2";
    public static final String MATCHER_VERSION = MATCHER_VERSION_V2;

    public static boolean isAuthoritativeFundingMatcherVersion(String version) {
        return MATCHER_VERSION_V1.equals(version) || MATCHER_VERSION_V2.equals(version);
    }

    private final BankStatementImportRepository importRepository;
    private final BankStatementLineRepository lineRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentInstructionRepository paymentInstructionRepository;
    private final SettlementInstructionRepository settlementInstructionRepository;
    private final ReleaseInstructionRepository releaseInstructionRepository;
    private final SettlementObservationRepository settlementObservationRepository;
    private final SettlementObservationEvidenceReader settlementObservationEvidenceReader;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationItemRepository itemRepository;

    @Autowired
    public DeterministicReconciliationMatcher(BankStatementImportRepository importRepository,
                                             BankStatementLineRepository lineRepository,
                                             PaymentTransactionRepository paymentTransactionRepository,
                                             PaymentInstructionRepository paymentInstructionRepository,
                                             SettlementInstructionRepository settlementInstructionRepository,
                                             ReleaseInstructionRepository releaseInstructionRepository,
                                             SettlementObservationRepository settlementObservationRepository,
                                             SettlementObservationEvidenceReader settlementObservationEvidenceReader,
                                             ReconciliationRunRepository runRepository,
                                             ReconciliationItemRepository itemRepository) {
        this.importRepository = importRepository;
        this.lineRepository = lineRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentInstructionRepository = paymentInstructionRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.releaseInstructionRepository = releaseInstructionRepository;
        this.settlementObservationRepository = settlementObservationRepository;
        this.settlementObservationEvidenceReader = settlementObservationEvidenceReader;
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
    }

    public DeterministicReconciliationMatcher(BankStatementImportRepository importRepository,
                                             BankStatementLineRepository lineRepository,
                                             PaymentTransactionRepository paymentTransactionRepository,
                                             PaymentInstructionRepository paymentInstructionRepository,
                                             SettlementInstructionRepository settlementInstructionRepository,
                                             ReconciliationRunRepository runRepository,
                                             ReconciliationItemRepository itemRepository) {
        this(importRepository, lineRepository, paymentTransactionRepository, paymentInstructionRepository,
                settlementInstructionRepository, null, null, null, runRepository, itemRepository);
    }

    @Transactional
    public DeterministicReconciliationResult reconcileImport(UUID bankStatementImportId, UserPrincipal currentUser) {
        // 1. Authorization check
        requirePlatformAdminOrFinance(currentUser);

        if (bankStatementImportId == null) {
            throw new IllegalArgumentException("bankStatementImportId cannot be null");
        }

        // 2. Load and validate import eligibility
        BankStatementImport statementImport = importRepository.findById(bankStatementImportId)
                .orElseThrow(() -> new IllegalArgumentException("Bank statement import not found: " + bankStatementImportId));

        validateImportEligibility(statementImport);

        // 3. Load and validate statement lines
        List<BankStatementLine> lines = lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(statementImport.getId());
        validateStatementLines(statementImport, lines);

        // 4. Verify line fingerprints and import source hash
        verifyLineFingerprintsAndSourceHash(statementImport, lines);

        // 5. Create initial ReconciliationRun
        String runRef = String.format("FINX-DET-REC-%d-%s",
                Year.now().getValue(), UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());

        ReconciliationRun run = new ReconciliationRun();
        run.setRunReference(runRef);
        run.setProvider(statementImport.getProvider());
        run.setProviderAccountId(statementImport.getProviderAccountId());
        run.setWindowStart(statementImport.getWindowStart());
        run.setWindowEnd(statementImport.getWindowEnd());
        run.setBankStatementImportId(statementImport.getId());
        run.setOpeningBalance(statementImport.getOpeningBalance());
        run.setClosingBalance(statementImport.getClosingBalance());
        run.setCurrency(statementImport.getCurrency());
        run.setMatcherVersion(MATCHER_VERSION);
        run.setStatementSourceHash(statementImport.getSourceHash());
        run.setTriggeredBy(currentUser != null ? currentUser.getId() : null);
        run.setStatus(ReconciliationRunStatus.IN_PROGRESS);
        run.setStartTime(Instant.now());

        ReconciliationRun savedRun = runRepository.save(run);
        UUID runId = savedRun.getId();

        // 6. Query bounded internal candidates
        List<PaymentTransaction> inboundCandidates = paymentTransactionRepository.findInboundReconciliationCandidates(
                statementImport.getProvider(),
                statementImport.getProviderAccountId(),
                statementImport.getWindowStart(),
                statementImport.getWindowEnd()
        );

        List<SettlementInstruction> outboundCandidates = settlementInstructionRepository.findOutboundReconciliationCandidates(
                statementImport.getProvider(),
                statementImport.getProviderAccountId(),
                statementImport.getWindowStart(),
                statementImport.getWindowEnd()
        );

        List<ReleaseInstruction> releaseCandidates = releaseInstructionRepository != null
                ? releaseInstructionRepository.findOutboundReconciliationCandidates(
                        statementImport.getProvider(),
                        statementImport.getProviderAccountId(),
                        statementImport.getWindowStart(),
                        statementImport.getWindowEnd()
                )
                : Collections.emptyList();

        int totalInternalCount = inboundCandidates.size() + outboundCandidates.size() + releaseCandidates.size();
        List<ReconciliationItem> items = new ArrayList<>();

        // Maps to hold candidate matches for global one-to-one conflict resolution
        // key: bankStatementLineId, value: list of candidates that matched this line
        Map<UUID, List<InternalCandidate>> lineToCandidateMap = new LinkedHashMap<>();

        // 7. Evaluate Inbound candidates
        for (PaymentTransaction pt : inboundCandidates) {
            PaymentInstruction pi = pt.getPaymentInstructionId() != null
                    ? paymentInstructionRepository.findById(pt.getPaymentInstructionId()).orElse(null)
                    : null;

            if (pi == null) {
                ReconciliationItem item = createItem(runId, pt.getTransactionReference(), null, pt.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH,
                        "Payment instruction not found for transaction", ReconciliationEffectType.PAYMENT_TRANSACTION,
                        pt.getId(), pt.getCurrency(), FinancialDirection.CREDIT, null);
                items.add(item);
                continue;
            }

            if (pi.getProviderAccountId() == null) {
                ReconciliationItem item = createItem(runId, pt.getTransactionReference(), null, pt.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_ACCOUNT_SCOPE_UNBOUND,
                        "Payment instruction has unbound provider account scope (NULL)", ReconciliationEffectType.PAYMENT_TRANSACTION,
                        pt.getId(), pt.getCurrency(), FinancialDirection.CREDIT, null);
                items.add(item);
                continue;
            }

            // Verify internal lineage consistency
            boolean lineageConsistent = statementImport.getProvider().equals(pi.getProvider())
                    && pi.getProvider().equals(pt.getProvider())
                    && pt.getAmount().compareTo(pi.getAmount()) == 0
                    && pt.getCurrency().equalsIgnoreCase(pi.getCurrency())
                    && pi.getStatus() == InstructionStatus.SUCCESS;

            if (!lineageConsistent) {
                ReconciliationItem item = createItem(runId, pt.getTransactionReference(), null, pt.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH,
                        String.format("Internal lineage mismatch between transaction and payment instruction: provider=%s/%s, amount=%s/%s, currency=%s/%s, instructionStatus=%s",
                                pt.getProvider(), pi.getProvider(), pt.getAmount(), pi.getAmount(), pt.getCurrency(), pi.getCurrency(), pi.getStatus()),
                        ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId(), pt.getCurrency(), FinancialDirection.CREDIT, null);
                items.add(item);
                continue;
            }

            // Inbound reference resolution priority:
            // 1. UTR
            // 2. Provider reference (pt.providerReference or pi.providerInstructionId)
            // 3. FinX reference (pt.transactionReference or pi.instructionReference)
            ReferenceMatchResult refResult = resolveInboundReference(pt, pi, lines);
            if (refResult.isAmbiguous()) {
                ReconciliationItem item = createItem(runId, pt.getTransactionReference(), null, pt.getAmount(), null,
                        ReconciliationItemStatus.DUPLICATE, ReconciliationReasonCode.REFERENCE_AMBIGUOUS,
                        "Ambiguous reference matches multiple bank statement lines: count=" + refResult.getMatchCount(),
                        ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId(), pt.getCurrency(), FinancialDirection.CREDIT, null);
                items.add(item);
            } else if (refResult.hasMatch()) {
                BankStatementLine matchedLine = refResult.getMatchedLine();
                InternalCandidate candidate = new InternalCandidate(
                        ReconciliationEffectType.PAYMENT_TRANSACTION,
                        pt.getId(),
                        pt.getTransactionReference(),
                        pt.getProvider(),
                        pi.getProviderAccountId(),
                        pt.getAmount(),
                        pt.getCurrency(),
                        FinancialDirection.CREDIT,
                        matchedLine
                );
                lineToCandidateMap.computeIfAbsent(matchedLine.getId(), k -> new ArrayList<>()).add(candidate);
            } else {
                ReconciliationItem item = createItem(runId, pt.getTransactionReference(), null, pt.getAmount(), null,
                        ReconciliationItemStatus.MISSING_EXTERNAL, ReconciliationReasonCode.MISSING_EXTERNAL,
                        "Internal transaction missing in bank statement evidence", ReconciliationEffectType.PAYMENT_TRANSACTION,
                        pt.getId(), pt.getCurrency(), FinancialDirection.CREDIT, null);
                items.add(item);
            }
        }

        // 8. Evaluate Outbound candidates
        for (SettlementInstruction si : outboundCandidates) {
            if (si.getProviderAccountId() == null) {
                ReconciliationItem item = createItem(runId, si.getSettlementReference(), null, si.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_ACCOUNT_SCOPE_UNBOUND,
                        "Settlement instruction has unbound provider account scope (NULL)", ReconciliationEffectType.SETTLEMENT_INSTRUCTION,
                        si.getId(), si.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
                continue;
            }

            if (si.getSettledAt() == null) {
                ReconciliationItem item = createItem(runId, si.getSettlementReference(), null, si.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH,
                        "Settlement instruction marked SETTLED but settledAt timestamp is null", ReconciliationEffectType.SETTLEMENT_INSTRUCTION,
                        si.getId(), si.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
                continue;
            }

            // Outbound reference resolution priority:
            // 1. UTR
            // 2. Provider settlement ID (si.providerSettlementId)
            // 3. FinX settlement reference (si.settlementReference)
            ReferenceMatchResult refResult = resolveOutboundReference(si, lines);
            if (refResult.isAmbiguous()) {
                ReconciliationItem item = createItem(runId, si.getSettlementReference(), null, si.getAmount(), null,
                        ReconciliationItemStatus.DUPLICATE, ReconciliationReasonCode.REFERENCE_AMBIGUOUS,
                        "Ambiguous reference matches multiple bank statement lines: count=" + refResult.getMatchCount(),
                        ReconciliationEffectType.SETTLEMENT_INSTRUCTION, si.getId(), si.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
            } else if (refResult.hasMatch()) {
                BankStatementLine matchedLine = refResult.getMatchedLine();
                InternalCandidate candidate = new InternalCandidate(
                        ReconciliationEffectType.SETTLEMENT_INSTRUCTION,
                        si.getId(),
                        si.getSettlementReference(),
                        si.getProvider(),
                        si.getProviderAccountId(),
                        si.getAmount(),
                        si.getCurrency(),
                        FinancialDirection.DEBIT,
                        matchedLine
                );
                lineToCandidateMap.computeIfAbsent(matchedLine.getId(), k -> new ArrayList<>()).add(candidate);
            } else {
                ReconciliationItem item = createItem(runId, si.getSettlementReference(), null, si.getAmount(), null,
                        ReconciliationItemStatus.MISSING_EXTERNAL, ReconciliationReasonCode.MISSING_EXTERNAL,
                        "Internal settlement missing in bank statement evidence", ReconciliationEffectType.SETTLEMENT_INSTRUCTION,
                        si.getId(), si.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
            }
        }

        // 8.5. Evaluate Modern Outbound (ReleaseInstruction) candidates
        for (ReleaseInstruction release : releaseCandidates) {
            UUID finalObsId = release.getFinalSettlementObservationId();
            if (finalObsId == null) {
                ReconciliationItem item = createItem(runId, ProviderSettlementOperationKey.release(release.getId()), null, release.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH,
                        "Settled release instruction has null finalSettlementObservationId",
                        ReconciliationEffectType.RELEASE_INSTRUCTION, release.getId(), release.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
                continue;
            }

            SettlementObservation observation = settlementObservationRepository != null
                    ? settlementObservationRepository.findById(finalObsId).orElse(null)
                    : null;
            if (observation == null) {
                ReconciliationItem item = createItem(runId, ProviderSettlementOperationKey.release(release.getId()), null, release.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH,
                        "Final settlement observation not found: id=" + finalObsId,
                        ReconciliationEffectType.RELEASE_INSTRUCTION, release.getId(), release.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
                continue;
            }

            // Internal Lineage Check (Part I)
            boolean lineageValid = release.getStatus() == ReleaseInstructionStatus.SETTLED
                    && release.getSettledAt() != null
                    && release.getProvider() != null && !release.getProvider().isBlank()
                    && release.getProviderAccountId() != null && !release.getProviderAccountId().isBlank()
                    && release.getAmount() != null && release.getAmount().compareTo(BigDecimal.ZERO) > 0
                    && release.getCurrency() != null && !release.getCurrency().isBlank()
                    && release.getId().equals(observation.getReleaseInstructionId())
                    && observation.getFinality() == SettlementObservationFinality.FINAL_SUCCESS
                    && observation.getProviderSettlementId() != null && !observation.getProviderSettlementId().isBlank()
                    && observation.getUtrNumber() != null && !observation.getUtrNumber().isBlank()
                    && release.getProvider().equals(observation.getProvider())
                    && observation.getProviderOperationKey() != null
                    && observation.getProviderOperationKey().equals(ProviderSettlementOperationKey.release(release.getId()))
                    && (observation.getProviderAccountId() == null || release.getProviderAccountId().equals(observation.getProviderAccountId()))
                    && (observation.getObservedAmount() == null || release.getAmount().compareTo(observation.getObservedAmount()) == 0)
                    && (observation.getObservedCurrency() == null || release.getCurrency().equalsIgnoreCase(observation.getObservedCurrency()))
                    && (observation.getObservedDirection() == null || "DEBIT".equalsIgnoreCase(observation.getObservedDirection()));

            if (!lineageValid) {
                ReconciliationItem item = createItem(runId, ProviderSettlementOperationKey.release(release.getId()), null, release.getAmount(), null,
                        ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH,
                        "Internal lineage mismatch between release instruction and final settlement observation",
                        ReconciliationEffectType.RELEASE_INSTRUCTION, release.getId(), release.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
                continue;
            }

            // Current Observation Conflict Check (Part H)
            if (settlementObservationEvidenceReader != null) {
                SettlementObservationEvidenceReader.AuthoritativeObservationResult authObs =
                        settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(release.getId());
                if (authObs.status() == SettlementObservationEvidenceReader.AuthoritativeStatus.REVERSED
                        || authObs.status() == SettlementObservationEvidenceReader.AuthoritativeStatus.CONFLICT) {
                    ReconciliationItem item = createItem(runId, observation.getProviderOperationKey(), null, release.getAmount(), null,
                            ReconciliationItemStatus.STATUS_MISMATCH, ReconciliationReasonCode.BANK_FINALITY_REVERSED,
                            "Settlement observation evidence indicates finality reversal or conflict: status=" + authObs.status(),
                            ReconciliationEffectType.RELEASE_INSTRUCTION, release.getId(), release.getCurrency(), FinancialDirection.DEBIT, null);
                    items.add(item);
                    continue;
                }
            }

            // Reference resolution priority: 1. UTR, 2. Provider Settlement ID, 3. Provider Operation Key (Part J)
            ReferenceMatchResult refResult = resolveReleaseOutboundReference(release, observation, lines);
            if (refResult.isAmbiguous()) {
                ReconciliationItem item = createItem(runId, observation.getProviderOperationKey(), null, release.getAmount(), null,
                        ReconciliationItemStatus.DUPLICATE, ReconciliationReasonCode.REFERENCE_AMBIGUOUS,
                        "Ambiguous reference matches multiple bank statement lines: count=" + refResult.getMatchCount(),
                        ReconciliationEffectType.RELEASE_INSTRUCTION, release.getId(), release.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
            } else if (refResult.hasMatch()) {
                BankStatementLine matchedLine = refResult.getMatchedLine();
                InternalCandidate candidate = new InternalCandidate(
                        ReconciliationEffectType.RELEASE_INSTRUCTION,
                        release.getId(),
                        observation.getProviderOperationKey(),
                        release.getProvider(),
                        release.getProviderAccountId(),
                        release.getAmount(),
                        release.getCurrency(),
                        FinancialDirection.DEBIT,
                        matchedLine,
                        observation.getUtrNumber()
                );
                lineToCandidateMap.computeIfAbsent(matchedLine.getId(), k -> new ArrayList<>()).add(candidate);
            } else {
                ReconciliationItem item = createItem(runId, observation.getProviderOperationKey(), null, release.getAmount(), null,
                        ReconciliationItemStatus.MISSING_EXTERNAL, ReconciliationReasonCode.MISSING_EXTERNAL,
                        "Internal release settlement missing in bank statement evidence",
                        ReconciliationEffectType.RELEASE_INSTRUCTION, release.getId(), release.getCurrency(), FinancialDirection.DEBIT, null);
                items.add(item);
            }
        }

        // 9. Global One-To-One Conflict Resolution & Dimension Validation
        Set<UUID> consumedLineIds = new HashSet<>();

        for (Map.Entry<UUID, List<InternalCandidate>> entry : lineToCandidateMap.entrySet()) {
            List<InternalCandidate> candidatesForLine = entry.getValue();
            if (candidatesForLine.size() > 1) {
                // Line claimed by multiple internal effects -> all marked DUPLICATE / REFERENCE_AMBIGUOUS
                for (InternalCandidate c : candidatesForLine) {
                    ReconciliationItem item = createItem(runId, c.finxReference, null, c.amount, null,
                            ReconciliationItemStatus.DUPLICATE, ReconciliationReasonCode.REFERENCE_AMBIGUOUS,
                            "Bank statement line is claimed by multiple internal financial effects: candidateCount=" + candidatesForLine.size(),
                            c.effectType, c.effectId, c.currency, c.expectedDirection, null);
                    items.add(item);
                }
                // Line remains unconsumed
            } else {
                // Exactly 1-to-1 association: validate all financial dimensions
                InternalCandidate c = candidatesForLine.get(0);
                BankStatementLine line = c.matchedLine;

                ReconciliationItem item = validateAndCreateAssociatedItem(runId, c, line, statementImport.getProvider());
                items.add(item);
                consumedLineIds.add(line.getId());
            }
        }

        // 10. Process Unclaimed Bank Lines
        for (BankStatementLine line : lines) {
            if (!consumedLineIds.contains(line.getId())) {
                if (line.getNormalizedStatus() == BankStatementLineStatus.FINAL_SUCCESS) {
                    ReconciliationItem item = createItem(runId, null, line.getTransactionReference(), null, line.getAmount(),
                            ReconciliationItemStatus.MISSING_INTERNAL, ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE,
                            "Unclaimed final bank line with no matching internal financial effect",
                            null, null, line.getCurrency(), line.getDirection(), line.getNormalizedStatus().name());
                    item.setBankStatementLineId(line.getId());
                    items.add(item);
                } else {
                    ReconciliationItem item = createItem(runId, null, line.getTransactionReference(), null, line.getAmount(),
                            ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.UNCLAIMED_NONFINAL_BANK_LINE,
                            "Unclaimed non-final bank line with status: " + line.getNormalizedStatus(),
                            null, null, line.getCurrency(), line.getDirection(), line.getNormalizedStatus().name());
                    item.setBankStatementLineId(line.getId());
                    items.add(item);
                }
            }
        }

        // 11. Statement Balance Control
        evaluateStatementBalanceControl(runId, statementImport, lines, items);

        // 12. Save all items
        itemRepository.saveAll(items);

        // 13. Complete run and calculate aggregates
        int matchedCount = (int) items.stream().filter(i -> i.getStatus() == ReconciliationItemStatus.MATCHED).count();
        int mismatchCount = (int) items.stream().filter(i -> i.getStatus() != ReconciliationItemStatus.MATCHED).count();

        savedRun.setTotalInternalTransactions(totalInternalCount);
        savedRun.setTotalBankTransactions(lines.size());
        savedRun.setMatchedCount(matchedCount);
        savedRun.setMismatchCount(mismatchCount);
        savedRun.setStatus(ReconciliationRunStatus.COMPLETED);
        savedRun.setEndTime(Instant.now());
        savedRun.setSummaryNotes(String.format("Deterministic reconciliation completed: totalInternal=%d, totalBank=%d, matched=%d, discrepancies=%d",
                totalInternalCount, lines.size(), matchedCount, mismatchCount));

        ReconciliationRun completedRun = runRepository.save(savedRun);

        boolean clean = completedRun.getStatus() == ReconciliationRunStatus.COMPLETED
                && completedRun.getMismatchCount() == 0
                && MATCHER_VERSION.equals(completedRun.getMatcherVersion());

        log.info("Deterministic reconciliation completed: runRef={} clean={} matched={} mismatch={}",
                runRef, clean, matchedCount, mismatchCount);

        return new DeterministicReconciliationResult(completedRun, clean, items);
    }

    private void requirePlatformAdminOrFinance(UserPrincipal currentUser) {
        if (currentUser == null || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE)) {
            throw new AccessDeniedException("Platform ADMIN or FINANCE role required for reconciliation operations");
        }
    }

    private void validateImportEligibility(BankStatementImport statementImport) {
        if (statementImport.getStatus() != BankStatementImportStatus.COMPLETED) {
            throw new IllegalStateException("Bank statement import is not in COMPLETED status: " + statementImport.getStatus());
        }
        if (statementImport.getProvider() == null || statementImport.getProvider().isBlank()) {
            throw new IllegalStateException("Bank statement import provider cannot be blank");
        }
        if (statementImport.getProviderAccountId() == null || statementImport.getProviderAccountId().isBlank()) {
            throw new IllegalStateException("Bank statement import providerAccountId cannot be blank");
        }
        if (statementImport.getWindowStart() == null || statementImport.getWindowEnd() == null) {
            throw new IllegalStateException("Bank statement import window boundaries cannot be null");
        }
        if (statementImport.getWindowEnd().isBefore(statementImport.getWindowStart())) {
            throw new IllegalStateException("Bank statement import windowEnd cannot be before windowStart");
        }
        if (statementImport.getSourceHash() == null || !statementImport.getSourceHash().matches("^[0-9a-f]{64}$")) {
            throw new IllegalStateException("Bank statement import sourceHash is invalid: " + statementImport.getSourceHash());
        }
        if (statementImport.getCurrency() == null || statementImport.getCurrency().trim().length() != 3) {
            throw new IllegalStateException("Bank statement import currency is invalid: " + statementImport.getCurrency());
        }
    }

    private void validateStatementLines(BankStatementImport statementImport, List<BankStatementLine> lines) {
        if (lines.size() != statementImport.getTotalLines()) {
            throw new IllegalStateException(String.format("Line count mismatch: expected %d from import but found %d in database",
                    statementImport.getTotalLines(), lines.size()));
        }

        for (BankStatementLine line : lines) {
            if (!statementImport.getId().equals(line.getImportId())) {
                throw new IllegalStateException("Line importId mismatch: " + line.getImportId());
            }
            if (!statementImport.getProvider().equals(line.getProvider())) {
                throw new IllegalStateException("Line provider mismatch: expected " + statementImport.getProvider() + " but got " + line.getProvider());
            }
            if (!statementImport.getProviderAccountId().equals(line.getProviderAccountId())) {
                throw new IllegalStateException("Line providerAccountId mismatch: expected " + statementImport.getProviderAccountId() + " but got " + line.getProviderAccountId());
            }
            if (!statementImport.getCurrency().equalsIgnoreCase(line.getCurrency())) {
                throw new IllegalStateException("Line currency mismatch: expected " + statementImport.getCurrency() + " but got " + line.getCurrency());
            }
            if (line.getTransactionTime().isBefore(statementImport.getWindowStart()) || line.getTransactionTime().isAfter(statementImport.getWindowEnd())) {
                throw new IllegalStateException("Line transactionTime outside import window: " + line.getTransactionTime());
            }
            if (line.getLineFingerprint() == null || !line.getLineFingerprint().matches("^[0-9a-f]{64}$")) {
                throw new IllegalStateException("Line fingerprint format invalid for line: " + line.getId());
            }
        }
    }

    private void verifyLineFingerprintsAndSourceHash(BankStatementImport statementImport, List<BankStatementLine> lines) {
        List<String> lineFingerprints = new ArrayList<>(lines.size());
        for (BankStatementLine line : lines) {
            String recomputedFp = BankStatementEvidenceFingerprint.computeLineFingerprint(line);
            if (!recomputedFp.equals(line.getLineFingerprint())) {
                throw new IllegalStateException("Line fingerprint mismatch / evidence tampering detected for line " + line.getId());
            }
            lineFingerprints.add(line.getLineFingerprint());
        }

        String recomputedSourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                statementImport.getProvider(),
                statementImport.getProviderAccountId(),
                statementImport.getCurrency(),
                statementImport.getOpeningBalance(),
                statementImport.getClosingBalance(),
                statementImport.getProviderImportReference(),
                lineFingerprints,
                statementImport.getWindowStart(),
                statementImport.getWindowEnd()
        );

        if (!recomputedSourceHash.equals(statementImport.getSourceHash())) {
            throw new IllegalStateException(String.format("Import sourceHash mismatch / evidence tampering detected: expected %s but recomputed %s",
                    statementImport.getSourceHash(), recomputedSourceHash));
        }
    }

    private ReferenceMatchResult resolveInboundReference(PaymentTransaction pt, PaymentInstruction pi, List<BankStatementLine> lines) {
        // Priority 1: UTR
        if (pt.getUtrNumber() != null && !pt.getUtrNumber().isBlank()) {
            String utr = pt.getUtrNumber().trim();
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getUtrNumber() != null && l.getUtrNumber().trim().equalsIgnoreCase(utr))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        // Priority 2: Provider Reference
        Set<String> providerRefs = new HashSet<>();
        if (pt.getProviderReference() != null && !pt.getProviderReference().isBlank()) {
            providerRefs.add(pt.getProviderReference().trim().toUpperCase());
        }
        if (pi.getProviderInstructionId() != null && !pi.getProviderInstructionId().isBlank()) {
            providerRefs.add(pi.getProviderInstructionId().trim().toUpperCase());
        }

        if (!providerRefs.isEmpty()) {
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getTransactionReference() != null && providerRefs.contains(l.getTransactionReference().trim().toUpperCase()))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        // Priority 3: FinX Reference
        Set<String> finxRefs = new HashSet<>();
        if (pt.getTransactionReference() != null && !pt.getTransactionReference().isBlank()) {
            finxRefs.add(pt.getTransactionReference().trim().toUpperCase());
        }
        if (pi.getInstructionReference() != null && !pi.getInstructionReference().isBlank()) {
            finxRefs.add(pi.getInstructionReference().trim().toUpperCase());
        }

        if (!finxRefs.isEmpty()) {
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getTransactionReference() != null && finxRefs.contains(l.getTransactionReference().trim().toUpperCase()))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        return ReferenceMatchResult.noMatch();
    }

    private ReferenceMatchResult resolveOutboundReference(SettlementInstruction si, List<BankStatementLine> lines) {
        // Priority 1: UTR
        if (si.getUtrNumber() != null && !si.getUtrNumber().isBlank()) {
            String utr = si.getUtrNumber().trim();
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getUtrNumber() != null && l.getUtrNumber().trim().equalsIgnoreCase(utr))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        // Priority 2: Provider Settlement ID
        if (si.getProviderSettlementId() != null && !si.getProviderSettlementId().isBlank()) {
            String provSettlementId = si.getProviderSettlementId().trim().toUpperCase();
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getTransactionReference() != null && l.getTransactionReference().trim().equalsIgnoreCase(provSettlementId))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        // Priority 3: Settlement Reference
        if (si.getSettlementReference() != null && !si.getSettlementReference().isBlank()) {
            String settlementRef = si.getSettlementReference().trim().toUpperCase();
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getTransactionReference() != null && l.getTransactionReference().trim().equalsIgnoreCase(settlementRef))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        return ReferenceMatchResult.noMatch();
    }

    private ReferenceMatchResult resolveReleaseOutboundReference(
            ReleaseInstruction release,
            SettlementObservation observation,
            List<BankStatementLine> lines
    ) {
        // Priority 1: UTR
        if (observation.getUtrNumber() != null && !observation.getUtrNumber().isBlank()) {
            String utr = observation.getUtrNumber().trim();
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getUtrNumber() != null && l.getUtrNumber().trim().equalsIgnoreCase(utr))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        // Priority 2: Provider Settlement ID
        if (observation.getProviderSettlementId() != null && !observation.getProviderSettlementId().isBlank()) {
            String provSettlementId = observation.getProviderSettlementId().trim().toUpperCase();
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getTransactionReference() != null && l.getTransactionReference().trim().equalsIgnoreCase(provSettlementId))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        // Priority 3: Provider Operation Key
        if (observation.getProviderOperationKey() != null && !observation.getProviderOperationKey().isBlank()) {
            String opKey = observation.getProviderOperationKey().trim().toUpperCase();
            List<BankStatementLine> matches = lines.stream()
                    .filter(l -> l.getTransactionReference() != null && l.getTransactionReference().trim().equalsIgnoreCase(opKey))
                    .toList();
            if (matches.size() == 1) {
                return ReferenceMatchResult.matched(matches.get(0));
            } else if (matches.size() > 1) {
                return ReferenceMatchResult.ambiguous(matches.size());
            }
        }

        return ReferenceMatchResult.noMatch();
    }

    private ReconciliationItem validateAndCreateAssociatedItem(UUID runId, InternalCandidate c, BankStatementLine line, String importProvider) {
        List<String> discrepancies = new ArrayList<>();
        List<ReconciliationReasonCode> reasons = new ArrayList<>();

        // 1. Provider check
        if (!c.provider.equals(line.getProvider()) || !line.getProvider().equals(importProvider)) {
            discrepancies.add(String.format("Provider mismatch: internal=%s vs bank=%s", c.provider, line.getProvider()));
            reasons.add(ReconciliationReasonCode.INTERNAL_PROVIDER_MISMATCH);
        }

        // 2. Account scope check
        if (!c.providerAccountId.equals(line.getProviderAccountId())) {
            discrepancies.add(String.format("Account scope mismatch: internal=%s vs bank=%s", c.providerAccountId, line.getProviderAccountId()));
            reasons.add(ReconciliationReasonCode.ACCOUNT_SCOPE_MISMATCH);
        }

        // 3. Direction check
        if (c.expectedDirection != line.getDirection()) {
            discrepancies.add(String.format("Direction mismatch: expected=%s vs bank=%s", c.expectedDirection, line.getDirection()));
            reasons.add(ReconciliationReasonCode.DIRECTION_MISMATCH);
        }

        // 4. Currency check
        if (!c.currency.trim().equalsIgnoreCase(line.getCurrency().trim())) {
            discrepancies.add(String.format("Currency mismatch: internal=%s vs bank=%s", c.currency, line.getCurrency()));
            reasons.add(ReconciliationReasonCode.CURRENCY_MISMATCH);
        }

        // 5. Finality check
        BankStatementLineStatus bankStatus = line.getNormalizedStatus();
        if (bankStatus == BankStatementLineStatus.FAILED) {
            discrepancies.add("Bank finality failed: status=FAILED");
            reasons.add(ReconciliationReasonCode.BANK_FINALITY_FAILED);
        } else if (bankStatus == BankStatementLineStatus.REVERSED) {
            discrepancies.add("Bank finality reversed: status=REVERSED");
            reasons.add(ReconciliationReasonCode.BANK_FINALITY_REVERSED);
        } else if (bankStatus == BankStatementLineStatus.PENDING) {
            discrepancies.add("Bank finality pending: status=PENDING");
            reasons.add(ReconciliationReasonCode.BANK_FINALITY_PENDING);
        } else if (bankStatus == BankStatementLineStatus.UNKNOWN) {
            discrepancies.add("Bank finality unknown: status=UNKNOWN");
            reasons.add(ReconciliationReasonCode.BANK_FINALITY_UNKNOWN);
        }

        // 6. Amount check
        if (c.amount.compareTo(line.getAmount()) != 0) {
            discrepancies.add(String.format("Amount mismatch: internal=%s vs bank=%s", c.amount, line.getAmount()));
            reasons.add(ReconciliationReasonCode.AMOUNT_MISMATCH);
        }

        // 7. UTR check for ReleaseInstruction
        if (c.effectType == ReconciliationEffectType.RELEASE_INSTRUCTION) {
            if (c.expectedUtr != null && !c.expectedUtr.isBlank()) {
                if (line.getUtrNumber() != null && !line.getUtrNumber().isBlank()) {
                    if (!line.getUtrNumber().trim().equalsIgnoreCase(c.expectedUtr.trim())) {
                        discrepancies.add(String.format("UTR mismatch: internal=%s vs bank=%s", c.expectedUtr, line.getUtrNumber()));
                        reasons.add(ReconciliationReasonCode.UTR_MISMATCH);
                    }
                } else {
                    discrepancies.add("Bank statement line lacks UTR evidence required for confirmed release payout matching");
                    reasons.add(ReconciliationReasonCode.UTR_MISMATCH);
                }
            }
        }

        // Determine primary status and reason based on fixed precedence
        ReconciliationItemStatus itemStatus;
        ReconciliationReasonCode primaryReason;

        if (reasons.isEmpty()) {
            itemStatus = ReconciliationItemStatus.MATCHED;
            primaryReason = ReconciliationReasonCode.MATCHED_CONFIRMED;
            discrepancies.add("Matched successfully");
        } else {
            primaryReason = selectPrimaryReason(reasons);
            if (primaryReason == ReconciliationReasonCode.AMOUNT_MISMATCH && reasons.size() == 1) {
                itemStatus = ReconciliationItemStatus.AMOUNT_MISMATCH;
            } else if (primaryReason == ReconciliationReasonCode.BANK_FINALITY_PENDING
                    || primaryReason == ReconciliationReasonCode.BANK_FINALITY_UNKNOWN
                    || primaryReason == ReconciliationReasonCode.UTR_MISMATCH) {
                itemStatus = ReconciliationItemStatus.REQUIRES_REVIEW;
            } else {
                itemStatus = ReconciliationItemStatus.STATUS_MISMATCH;
            }
        }

        String details = String.join("; ", discrepancies);

        ReconciliationItem item = createItem(runId, c.finxReference, line.getTransactionReference(), c.amount, line.getAmount(),
                itemStatus, primaryReason, details, c.effectType, c.effectId, line.getCurrency(), line.getDirection(),
                line.getNormalizedStatus() != null ? line.getNormalizedStatus().name() : null);
        item.setBankStatementLineId(line.getId());
        return item;
    }

    private ReconciliationReasonCode selectPrimaryReason(List<ReconciliationReasonCode> reasons) {
        // Priority order:
        // 1. INTERNAL_PROVIDER_MISMATCH
        // 2. ACCOUNT_SCOPE_MISMATCH
        // 3. INTERNAL_LINEAGE_MISMATCH
        // 4. DIRECTION_MISMATCH
        // 5. CURRENCY_MISMATCH
        // 6. UTR_MISMATCH
        // 7. REFERENCE_MISMATCH
        // 8. BANK_FINALITY_FAILED
        // 9. BANK_FINALITY_REVERSED
        // 10. AMOUNT_MISMATCH
        // 11. BANK_FINALITY_PENDING
        // 12. BANK_FINALITY_UNKNOWN
        List<ReconciliationReasonCode> priorityList = List.of(
                ReconciliationReasonCode.INTERNAL_PROVIDER_MISMATCH,
                ReconciliationReasonCode.ACCOUNT_SCOPE_MISMATCH,
                ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH,
                ReconciliationReasonCode.DIRECTION_MISMATCH,
                ReconciliationReasonCode.CURRENCY_MISMATCH,
                ReconciliationReasonCode.UTR_MISMATCH,
                ReconciliationReasonCode.REFERENCE_MISMATCH,
                ReconciliationReasonCode.BANK_FINALITY_FAILED,
                ReconciliationReasonCode.BANK_FINALITY_REVERSED,
                ReconciliationReasonCode.AMOUNT_MISMATCH,
                ReconciliationReasonCode.BANK_FINALITY_PENDING,
                ReconciliationReasonCode.BANK_FINALITY_UNKNOWN
        );

        for (ReconciliationReasonCode priority : priorityList) {
            if (reasons.contains(priority)) {
                return priority;
            }
        }
        return reasons.get(0);
    }

    private void evaluateStatementBalanceControl(UUID runId, BankStatementImport statementImport,
                                                List<BankStatementLine> lines, List<ReconciliationItem> items) {
        BigDecimal opening = statementImport.getOpeningBalance();
        BigDecimal closing = statementImport.getClosingBalance();

        if (opening == null || closing == null) {
            ReconciliationItem item = createItem(runId, null, null, null, null,
                    ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.STATEMENT_BALANCE_MISSING,
                    "Statement balance control missing: openingBalance or closingBalance is null",
                    null, null, statementImport.getCurrency(), null, null);
            items.add(item);
            return;
        }

        boolean hasNonFinalLines = lines.stream().anyMatch(l -> l.getNormalizedStatus() != BankStatementLineStatus.FINAL_SUCCESS);
        if (hasNonFinalLines) {
            ReconciliationItem item = createItem(runId, null, null, null, null,
                    ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.STATEMENT_BALANCE_NONFINAL_EVIDENCE,
                    "Statement balance verification skipped due to non-final bank statement line evidence",
                    null, null, statementImport.getCurrency(), null, null);
            items.add(item);
            return;
        }

        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;

        for (BankStatementLine line : lines) {
            if (line.getDirection() == FinancialDirection.CREDIT) {
                totalCredits = totalCredits.add(line.getAmount());
            } else if (line.getDirection() == FinancialDirection.DEBIT) {
                totalDebits = totalDebits.add(line.getAmount());
            }
        }

        BigDecimal expectedClosing = opening.add(totalCredits).subtract(totalDebits);
        if (expectedClosing.compareTo(closing) != 0) {
            ReconciliationItem item = createItem(runId, null, null, expectedClosing, closing,
                    ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.STATEMENT_BALANCE_MISMATCH,
                    String.format("Statement balance arithmetic mismatch: opening=%s + credit=%s - debit=%s = expected %s vs closing=%s",
                            opening, totalCredits, totalDebits, expectedClosing, closing),
                    null, null, statementImport.getCurrency(), null, null);
            items.add(item);
        }
    }

    private ReconciliationItem createItem(UUID runId, String finxRef, String bankRef, BigDecimal finxAmt, BigDecimal bankAmt,
                                         ReconciliationItemStatus status, ReconciliationReasonCode reasonCode, String details,
                                         ReconciliationEffectType effectType, UUID effectId, String currency,
                                         FinancialDirection direction, String bankStatus) {
        ReconciliationItem item = new ReconciliationItem();
        item.setReconciliationRunId(runId);
        item.setFinxTransactionReference(finxRef);
        item.setBankTransactionReference(bankRef);
        item.setFinxAmount(finxAmt);
        item.setBankAmount(bankAmt);
        item.setStatus(status);
        item.setReasonCode(reasonCode);
        item.setDiscrepancyDetails(details);
        item.setInternalEffectType(effectType);
        item.setInternalEffectId(effectId);
        item.setCurrency(currency);
        item.setDirection(direction);
        item.setBankStatus(bankStatus);
        return item;
    }

    // Helper data structures for internal matching
    private static class InternalCandidate {
        final ReconciliationEffectType effectType;
        final UUID effectId;
        final String finxReference;
        final String provider;
        final String providerAccountId;
        final BigDecimal amount;
        final String currency;
        final FinancialDirection expectedDirection;
        final BankStatementLine matchedLine;
        final String expectedUtr;

        InternalCandidate(ReconciliationEffectType effectType, UUID effectId, String finxReference, String provider,
                          String providerAccountId, BigDecimal amount, String currency,
                          FinancialDirection expectedDirection, BankStatementLine matchedLine) {
            this(effectType, effectId, finxReference, provider, providerAccountId, amount, currency, expectedDirection, matchedLine, null);
        }

        InternalCandidate(ReconciliationEffectType effectType, UUID effectId, String finxReference, String provider,
                          String providerAccountId, BigDecimal amount, String currency,
                          FinancialDirection expectedDirection, BankStatementLine matchedLine, String expectedUtr) {
            this.effectType = effectType;
            this.effectId = effectId;
            this.finxReference = finxReference;
            this.provider = provider;
            this.providerAccountId = providerAccountId;
            this.amount = amount;
            this.currency = currency;
            this.expectedDirection = expectedDirection;
            this.matchedLine = matchedLine;
            this.expectedUtr = expectedUtr;
        }
    }

    private static class ReferenceMatchResult {
        private final BankStatementLine matchedLine;
        private final boolean ambiguous;
        private final int matchCount;

        private ReferenceMatchResult(BankStatementLine matchedLine, boolean ambiguous, int matchCount) {
            this.matchedLine = matchedLine;
            this.ambiguous = ambiguous;
            this.matchCount = matchCount;
        }

        static ReferenceMatchResult matched(BankStatementLine line) {
            return new ReferenceMatchResult(line, false, 1);
        }

        static ReferenceMatchResult ambiguous(int count) {
            return new ReferenceMatchResult(null, true, count);
        }

        static ReferenceMatchResult noMatch() {
            return new ReferenceMatchResult(null, false, 0);
        }

        boolean hasMatch() { return matchedLine != null; }
        boolean isAmbiguous() { return ambiguous; }
        BankStatementLine getMatchedLine() { return matchedLine; }
        int getMatchCount() { return matchCount; }
    }
}
