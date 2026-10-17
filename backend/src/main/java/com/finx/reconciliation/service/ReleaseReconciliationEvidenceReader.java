package com.finx.reconciliation.service;

import com.finx.reconciliation.dto.ReleaseReconciliationResult;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.model.ReleaseReconciliationStatus;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeObservationResult;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Pure read-only evidence reader for post-payout closed-loop release reconciliation.
 * Answers: "Is this SETTLED ReleaseInstruction now authoritatively matched to durable bank statement evidence?"
 * Strictly read-only: zero mutations, zero ledger/escrow updates, zero bank calls.
 */
@Service
@Transactional(readOnly = true)
public class ReleaseReconciliationEvidenceReader {

    private final ReleaseInstructionRepository releaseInstructionRepository;
    private final SettlementObservationRepository settlementObservationRepository;
    private final SettlementObservationEvidenceReader settlementObservationEvidenceReader;
    private final ReconciliationItemRepository itemRepository;
    private final ReconciliationRunRepository runRepository;
    private final BankStatementImportRepository importRepository;
    private final BankStatementLineRepository lineRepository;

    public ReleaseReconciliationEvidenceReader(
            ReleaseInstructionRepository releaseInstructionRepository,
            SettlementObservationRepository settlementObservationRepository,
            SettlementObservationEvidenceReader settlementObservationEvidenceReader,
            ReconciliationItemRepository itemRepository,
            ReconciliationRunRepository runRepository,
            BankStatementImportRepository importRepository,
            BankStatementLineRepository lineRepository
    ) {
        this.releaseInstructionRepository = Objects.requireNonNull(releaseInstructionRepository, "releaseInstructionRepository must not be null");
        this.settlementObservationRepository = Objects.requireNonNull(settlementObservationRepository, "settlementObservationRepository must not be null");
        this.settlementObservationEvidenceReader = Objects.requireNonNull(settlementObservationEvidenceReader, "settlementObservationEvidenceReader must not be null");
        this.itemRepository = Objects.requireNonNull(itemRepository, "itemRepository must not be null");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.importRepository = Objects.requireNonNull(importRepository, "importRepository must not be null");
        this.lineRepository = Objects.requireNonNull(lineRepository, "lineRepository must not be null");
    }

    public ReleaseReconciliationResult getReleaseReconciliationEvidence(UUID releaseInstructionId) {
        return evaluateReleaseReconciliation(releaseInstructionId);
    }

    public ReleaseReconciliationResult evaluateReleaseReconciliation(UUID releaseInstructionId) {
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");

        ReleaseInstruction release = releaseInstructionRepository.findById(releaseInstructionId).orElse(null);
        if (release == null) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.NO_EVIDENCE,
                    null, null, null, null,
                    "Release instruction not found: " + releaseInstructionId
            );
        }

        List<ReconciliationItem> items = itemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.RELEASE_INSTRUCTION, releaseInstructionId
        );

        if (items.isEmpty()) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.NO_EVIDENCE,
                    null, null, null, null,
                    "No reconciliation items found for release instruction"
            );
        }

        // Map runs for V2 evaluation
        Map<ReconciliationRun, ReconciliationItem> runItemMap = new HashMap<>();
        List<ReconciliationRun> v2Runs = new ArrayList<>();

        for (ReconciliationItem item : items) {
            if (item.getReconciliationRunId() != null) {
                ReconciliationRun run = runRepository.findById(item.getReconciliationRunId()).orElse(null);
                if (run != null && DeterministicReconciliationMatcher.MATCHER_VERSION_V2.equals(run.getMatcherVersion())) {
                    runItemMap.put(run, item);
                    v2Runs.add(run);
                }
            }
        }

        if (v2Runs.isEmpty()) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.NO_EVIDENCE,
                    null, null, null, null,
                    "No authoritative V2 reconciliation runs found for release instruction"
            );
        }

        // Latest-run rule: sort by endTime desc, then createdAt desc, then id desc
        Comparator<ReconciliationRun> runComparator = Comparator.comparing(
                ReconciliationRun::getEndTime, Comparator.nullsLast(Comparator.naturalOrder())
        ).thenComparing(
                ReconciliationRun::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())
        ).thenComparing(
                ReconciliationRun::getId, Comparator.nullsLast(Comparator.naturalOrder())
        );

        v2Runs.sort(runComparator.reversed());
        ReconciliationRun latestRun = v2Runs.get(0);
        ReconciliationItem releaseItem = runItemMap.get(latestRun);

        // Check E6 SettlementObservation conflict/reversal first (Part Y)
        AuthoritativeObservationResult obsResult =
                settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId);
        if (obsResult != null && (obsResult.status() == AuthoritativeStatus.REVERSED || obsResult.status() == AuthoritativeStatus.CONFLICT)) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVERSAL_RECOVERY_REQUIRED,
                    latestRun.getId(),
                    releaseItem != null ? releaseItem.getId() : null,
                    releaseItem != null ? releaseItem.getBankStatementLineId() : null,
                    latestRun.getBankStatementImportId(),
                    "Settlement observation evidence indicates finality reversal or conflict: " + obsResult.status()
            );
        }

        BankStatementLine line = releaseItem != null && releaseItem.getBankStatementLineId() != null
                ? lineRepository.findById(releaseItem.getBankStatementLineId()).orElse(null)
                : null;

        // Check for reversal in statement evidence
        if (line != null && line.getNormalizedStatus() == BankStatementLineStatus.REVERSED) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVERSAL_RECOVERY_REQUIRED,
                    latestRun.getId(),
                    releaseItem.getId(),
                    line.getId(),
                    latestRun.getBankStatementImportId(),
                    "Bank statement line indicates transaction was REVERSED"
            );
        }
        if (releaseItem != null && releaseItem.getReasonCode() == ReconciliationReasonCode.BANK_FINALITY_REVERSED) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVERSAL_RECOVERY_REQUIRED,
                    latestRun.getId(),
                    releaseItem.getId(),
                    releaseItem.getBankStatementLineId(),
                    latestRun.getBankStatementImportId(),
                    "Reconciliation item indicates bank finality reversed"
            );
        }

        // Hard blocks: material contradictions
        if (releaseItem != null) {
            if (releaseItem.getReasonCode() == ReconciliationReasonCode.INTERNAL_PROVIDER_MISMATCH
                    || (line != null && !release.getProvider().equals(line.getProvider()))
                    || (latestRun.getProvider() != null && !release.getProvider().equals(latestRun.getProvider()))) {
                return new ReleaseReconciliationResult(
                        releaseInstructionId,
                        ReleaseReconciliationStatus.BLOCKED,
                        latestRun.getId(),
                        releaseItem.getId(),
                        releaseItem.getBankStatementLineId(),
                        latestRun.getBankStatementImportId(),
                        "Provider mismatch detected between release and bank evidence"
                );
            }

            if (releaseItem.getReasonCode() == ReconciliationReasonCode.ACCOUNT_SCOPE_MISMATCH
                    || (line != null && !release.getProviderAccountId().equals(line.getProviderAccountId()))
                    || (latestRun.getProviderAccountId() != null && !release.getProviderAccountId().equals(latestRun.getProviderAccountId()))) {
                return new ReleaseReconciliationResult(
                        releaseInstructionId,
                        ReleaseReconciliationStatus.BLOCKED,
                        latestRun.getId(),
                        releaseItem.getId(),
                        releaseItem.getBankStatementLineId(),
                        latestRun.getBankStatementImportId(),
                        "Account scope mismatch detected between release and bank evidence"
                );
            }

            if (releaseItem.getReasonCode() == ReconciliationReasonCode.DIRECTION_MISMATCH
                    || (line != null && line.getDirection() != FinancialDirection.DEBIT)) {
                return new ReleaseReconciliationResult(
                        releaseInstructionId,
                        ReleaseReconciliationStatus.BLOCKED,
                        latestRun.getId(),
                        releaseItem.getId(),
                        releaseItem.getBankStatementLineId(),
                        latestRun.getBankStatementImportId(),
                        "Direction mismatch: DEBIT required for release settlement"
                );
            }

            if (releaseItem.getReasonCode() == ReconciliationReasonCode.AMOUNT_MISMATCH
                    || releaseItem.getStatus() == ReconciliationItemStatus.AMOUNT_MISMATCH
                    || (line != null && line.getAmount().compareTo(release.getAmount()) != 0)) {
                return new ReleaseReconciliationResult(
                        releaseInstructionId,
                        ReleaseReconciliationStatus.BLOCKED,
                        latestRun.getId(),
                        releaseItem.getId(),
                        releaseItem.getBankStatementLineId(),
                        latestRun.getBankStatementImportId(),
                        "Amount mismatch detected between release and bank evidence"
                );
            }

            if (releaseItem.getReasonCode() == ReconciliationReasonCode.CURRENCY_MISMATCH
                    || (line != null && !line.getCurrency().trim().equalsIgnoreCase(release.getCurrency().trim()))
                    || (latestRun.getCurrency() != null && !latestRun.getCurrency().trim().equalsIgnoreCase(release.getCurrency().trim()))) {
                return new ReleaseReconciliationResult(
                        releaseInstructionId,
                        ReleaseReconciliationStatus.BLOCKED,
                        latestRun.getId(),
                        releaseItem.getId(),
                        releaseItem.getBankStatementLineId(),
                        latestRun.getBankStatementImportId(),
                        "Currency mismatch detected between release and bank evidence"
                );
            }

            // UTR mismatch check
            SettlementObservation finalObs = release.getFinalSettlementObservationId() != null
                    ? settlementObservationRepository.findById(release.getFinalSettlementObservationId()).orElse(null)
                    : null;

            if (releaseItem.getReasonCode() == ReconciliationReasonCode.UTR_MISMATCH
                    || (finalObs != null && finalObs.getUtrNumber() != null && !finalObs.getUtrNumber().isBlank()
                    && line != null && line.getUtrNumber() != null && !line.getUtrNumber().isBlank()
                    && !line.getUtrNumber().trim().equalsIgnoreCase(finalObs.getUtrNumber().trim()))) {
                return new ReleaseReconciliationResult(
                        releaseInstructionId,
                        ReleaseReconciliationStatus.BLOCKED,
                        latestRun.getId(),
                        releaseItem.getId(),
                        line != null ? line.getId() : releaseItem.getBankStatementLineId(),
                        latestRun.getBankStatementImportId(),
                        "UTR mismatch detected between final observation and bank evidence"
                );
            }
        }

        // Validate Run Integrity
        if (latestRun.getStatus() != ReconciliationRunStatus.COMPLETED
                || latestRun.getBankStatementImportId() == null
                || latestRun.getStatementSourceHash() == null) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVIEW,
                    latestRun.getId(),
                    releaseItem != null ? releaseItem.getId() : null,
                    releaseItem != null ? releaseItem.getBankStatementLineId() : null,
                    latestRun.getBankStatementImportId(),
                    "Reconciliation run is incomplete or lacks statement provenance"
            );
        }

        BankStatementImport imp = importRepository.findById(latestRun.getBankStatementImportId()).orElse(null);
        if (imp == null || !latestRun.getStatementSourceHash().equals(imp.getSourceHash())) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVIEW,
                    latestRun.getId(),
                    releaseItem != null ? releaseItem.getId() : null,
                    releaseItem != null ? releaseItem.getBankStatementLineId() : null,
                    latestRun.getBankStatementImportId(),
                    "Statement source hash mismatch or import record missing"
            );
        }

        if (latestRun.getMismatchCount() > 0) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVIEW,
                    latestRun.getId(),
                    releaseItem != null ? releaseItem.getId() : null,
                    releaseItem != null ? releaseItem.getBankStatementLineId() : null,
                    latestRun.getBankStatementImportId(),
                    "Reconciliation run has " + latestRun.getMismatchCount() + " discrepancies"
            );
        }

        List<ReconciliationItem> allRunItems = itemRepository.findByReconciliationRunId(latestRun.getId());
        boolean allMatched = allRunItems.stream().allMatch(ri -> ri.getStatus() == ReconciliationItemStatus.MATCHED);
        if (!allMatched) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVIEW,
                    latestRun.getId(),
                    releaseItem != null ? releaseItem.getId() : null,
                    releaseItem != null ? releaseItem.getBankStatementLineId() : null,
                    latestRun.getBankStatementImportId(),
                    "Reconciliation run contains non-matched items"
            );
        }

        // Validate Release Item & Line Evidence
        if (releaseItem == null
                || releaseItem.getStatus() != ReconciliationItemStatus.MATCHED
                || releaseItem.getReasonCode() != ReconciliationReasonCode.MATCHED_CONFIRMED) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVIEW,
                    latestRun.getId(),
                    releaseItem != null ? releaseItem.getId() : null,
                    releaseItem != null ? releaseItem.getBankStatementLineId() : null,
                    latestRun.getBankStatementImportId(),
                    "Release reconciliation item is not in clean MATCHED status: "
                            + (releaseItem != null ? releaseItem.getStatus() + " (" + releaseItem.getReasonCode() + ")" : "null")
            );
        }

        if (line == null) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVIEW,
                    latestRun.getId(),
                    releaseItem.getId(),
                    null,
                    latestRun.getBankStatementImportId(),
                    "Bank statement line evidence not found"
            );
        }

        if (line.getNormalizedStatus() != BankStatementLineStatus.FINAL_SUCCESS) {
            return new ReleaseReconciliationResult(
                    releaseInstructionId,
                    ReleaseReconciliationStatus.REVIEW,
                    latestRun.getId(),
                    releaseItem.getId(),
                    line.getId(),
                    latestRun.getBankStatementImportId(),
                    "Bank statement line finality is not FINAL_SUCCESS: " + line.getNormalizedStatus()
            );
        }

        // UTR presence validation on line
        SettlementObservation finalObs = release.getFinalSettlementObservationId() != null
                ? settlementObservationRepository.findById(release.getFinalSettlementObservationId()).orElse(null)
                : null;
        if (finalObs != null && finalObs.getUtrNumber() != null && !finalObs.getUtrNumber().isBlank()) {
            if (line.getUtrNumber() == null || line.getUtrNumber().isBlank()) {
                return new ReleaseReconciliationResult(
                        releaseInstructionId,
                        ReleaseReconciliationStatus.REVIEW,
                        latestRun.getId(),
                        releaseItem.getId(),
                        line.getId(),
                        latestRun.getBankStatementImportId(),
                        "Bank statement line lacks UTR evidence required for confirmed release payout matching"
                );
            }
        }

        // All checks passed cleanly -> CONFIRMED
        return new ReleaseReconciliationResult(
                releaseInstructionId,
                ReleaseReconciliationStatus.CONFIRMED,
                latestRun.getId(),
                releaseItem.getId(),
                line.getId(),
                latestRun.getBankStatementImportId(),
                "Release settlement authoritatively reconciled against bank statement evidence"
        );
    }
}
