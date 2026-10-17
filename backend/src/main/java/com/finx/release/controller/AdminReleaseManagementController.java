package com.finx.release.controller;

import com.finx.banking.readiness.BankingProviderReadiness;
import com.finx.banking.readiness.BankingProviderReadinessService;
import com.finx.exception.ResourceNotFoundException;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.release.control.ReleaseOperationalControlService;
import com.finx.release.controller.dto.OperationalActionRequest;
import com.finx.release.controller.dto.ReleaseOperationalSummaryDto;
import com.finx.release.controller.dto.SafeReleaseOperationDto;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.gate.ProductionSettlementDispatchGate;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observability.ReleaseExecutionMetrics;
import com.finx.release.observability.ReleaseObservabilityProperties;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.security.service.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Controller exposing operational visibility and safe pre-dispatch controls for FinX release execution.
 * Accessible strictly by users with ADMIN or FINANCE role.
 * <p>
 * STRICT RESTRICTIONS:
 * - NO direct money movement or payout retry endpoints.
 * - NO force settle, override observation, or override reconciliation endpoints.
 * - ZERO exposure of bank account numbers, credentials, or PII.
 */
@RestController
@RequestMapping("/api/v1/finance/release-operations")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class AdminReleaseManagementController {

    private final ReleaseInstructionRepository instructionRepository;
    private final FinancialOutboxRepository outboxRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final SettlementObservationRepository observationRepository;
    private final ReconciliationItemRepository reconciliationItemRepository;
    private final BankingProviderReadinessService readinessService;
    private final ProductionSettlementDispatchGate dispatchGate;
    private final ReleaseOperationalControlService operationalControlService;
    private final ReleaseExecutionMetrics metrics;
    private final ReleaseObservabilityProperties observabilityProperties;

    public AdminReleaseManagementController(
            ReleaseInstructionRepository instructionRepository,
            FinancialOutboxRepository outboxRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            SettlementObservationRepository observationRepository,
            ReconciliationItemRepository reconciliationItemRepository,
            BankingProviderReadinessService readinessService,
            ProductionSettlementDispatchGate dispatchGate,
            ReleaseOperationalControlService operationalControlService,
            ReleaseExecutionMetrics metrics,
            ReleaseObservabilityProperties observabilityProperties
    ) {
        this.instructionRepository = instructionRepository;
        this.outboxRepository = outboxRepository;
        this.attemptRepository = attemptRepository;
        this.observationRepository = observationRepository;
        this.reconciliationItemRepository = reconciliationItemRepository;
        this.readinessService = readinessService;
        this.dispatchGate = dispatchGate;
        this.operationalControlService = operationalControlService;
        this.metrics = metrics;
        this.observabilityProperties = observabilityProperties;
    }

    @GetMapping("/summary")
    public ResponseEntity<ReleaseOperationalSummaryDto> getSummary() {
        BankingProviderReadiness readiness = readinessService.evaluateReadiness();
        ReleaseOperationalSummaryDto summary = new ReleaseOperationalSummaryDto(
                outboxRepository.countByStatus(FinancialOutboxStatus.PENDING),
                outboxRepository.countByStatus(FinancialOutboxStatus.RETRY),
                outboxRepository.countByStatus(FinancialOutboxStatus.LEASED),
                outboxRepository.countByStatus(FinancialOutboxStatus.HELD),
                outboxRepository.countByStatus(FinancialOutboxStatus.DEAD_LETTER),
                metrics.getOldestPendingAgeSeconds(),
                attemptRepository.countByStatus(SettlementExecutionAttemptStatus.PREPARED),
                attemptRepository.countByStatus(SettlementExecutionAttemptStatus.SENDING),
                attemptRepository.countByStatus(SettlementExecutionAttemptStatus.UNKNOWN),
                attemptRepository.countByStatus(SettlementExecutionAttemptStatus.ACKNOWLEDGED),
                instructionRepository.countByStatus(ReleaseInstructionStatus.PENDING_SETTLEMENT),
                metrics.getOldestPendingSettlementAgeSeconds(),
                instructionRepository.countByStatus(ReleaseInstructionStatus.SETTLED),
                observationRepository.countConflictingObservations(),
                observationRepository.countByFinality(SettlementObservationFinality.REVERSED),
                reconciliationItemRepository.countByStatus(ReconciliationItemStatus.REQUIRES_REVIEW),
                reconciliationItemRepository.countBlockedReconciliationItems(),
                reconciliationItemRepository.countByReasonCode(ReconciliationReasonCode.BANK_FINALITY_REVERSED),
                readiness.readyForSandbox(),
                readiness.readyForProduction(),
                dispatchGate.isProductionDispatchAllowed(),
                Instant.now()
        );
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/releases/{releaseInstructionId}")
    public ResponseEntity<SafeReleaseOperationDto> getRelease(@PathVariable UUID releaseInstructionId) {
        ReleaseInstruction instruction = instructionRepository.findById(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));
        return ResponseEntity.ok(buildSafeDto(instruction));
    }

    @GetMapping("/unknown-outcomes")
    public ResponseEntity<List<SafeReleaseOperationDto>> getUnknownOutcomes() {
        List<SettlementExecutionAttempt> unknowns = attemptRepository.findByStatusOrderByCreatedAtDesc(SettlementExecutionAttemptStatus.UNKNOWN);
        List<SafeReleaseOperationDto> results = new ArrayList<>();
        Set<UUID> seenReleaseIds = new HashSet<>();

        for (SettlementExecutionAttempt attempt : unknowns) {
            UUID relId = attempt.getReleaseInstructionId();
            if (seenReleaseIds.add(relId)) {
                instructionRepository.findById(relId).ifPresent(ri -> results.add(buildSafeDto(ri)));
            }
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/dead-letter")
    public ResponseEntity<List<SafeReleaseOperationDto>> getDeadLetterReleases() {
        List<FinancialOutboxMessage> deadLetters = outboxRepository.findByStatusOrderByCreatedAtDesc(FinancialOutboxStatus.DEAD_LETTER);
        List<SafeReleaseOperationDto> results = new ArrayList<>();

        for (FinancialOutboxMessage msg : deadLetters) {
            instructionRepository.findById(msg.getReleaseInstructionId()).ifPresent(ri -> results.add(buildSafeDto(ri)));
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/stale-settlements")
    public ResponseEntity<List<SafeReleaseOperationDto>> getStaleSettlements() {
        Instant threshold = Instant.now().minusSeconds(observabilityProperties.getPendingSettlementStaleSeconds());
        List<ReleaseInstruction> stales = instructionRepository.findStalePendingSettlement(threshold);
        return ResponseEntity.ok(stales.stream().map(this::buildSafeDto).toList());
    }

    @GetMapping("/reconciliation-exceptions")
    public ResponseEntity<List<Map<String, Object>>> getReconciliationExceptions() {
        List<ReconciliationItem> reviewItems = reconciliationItemRepository.findByStatusAndResolutionStatus(
                ReconciliationItemStatus.REQUIRES_REVIEW, ResolutionStatus.UNRESOLVED);
        List<ReconciliationItem> reversals = reconciliationItemRepository.findByReasonCode(ReconciliationReasonCode.BANK_FINALITY_REVERSED);

        Set<UUID> seen = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();

        for (ReconciliationItem item : reviewItems) {
            if (seen.add(item.getId())) {
                results.add(mapReconciliationItem(item));
            }
        }
        for (ReconciliationItem item : reversals) {
            if (seen.add(item.getId())) {
                results.add(mapReconciliationItem(item));
            }
        }

        return ResponseEntity.ok(results);
    }

    @GetMapping("/provider-readiness")
    public ResponseEntity<BankingProviderReadiness> getProviderReadiness() {
        return ResponseEntity.ok(readinessService.evaluateReadiness());
    }

    @GetMapping("/production-gate")
    public ResponseEntity<Map<String, Object>> getProductionGate() {
        Map<String, Object> gateStatus = new LinkedHashMap<>();
        gateStatus.put("masterProductionEnabled", dispatchGate.isMasterProductionEnabled());
        gateStatus.put("e12Certified", dispatchGate.isE12Certified());
        gateStatus.put("gateOpen", dispatchGate.isProductionDispatchAllowed());
        gateStatus.put("evaluatedAt", Instant.now());
        return ResponseEntity.ok(gateStatus);
    }

    @PostMapping("/releases/{id}/hold")
    public ResponseEntity<Map<String, Object>> holdRelease(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody(required = false) @Valid OperationalActionRequest request
    ) {
        String reason = request != null ? request.reasonCode() : "ADMIN_HOLD";
        operationalControlService.holdPreDispatchRelease(id, actor, reason);
        return ResponseEntity.ok(Map.of(
                "releaseInstructionId", id,
                "action", "HOLD",
                "status", "MANUAL_REVIEW",
                "appliedAt", Instant.now()
        ));
    }

    @PostMapping("/releases/{id}/resume")
    public ResponseEntity<Map<String, Object>> resumeRelease(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody(required = false) @Valid OperationalActionRequest request
    ) {
        String reason = request != null ? request.reasonCode() : "ADMIN_RESUME";
        operationalControlService.resumeHeldRelease(id, actor, reason);
        return ResponseEntity.ok(Map.of(
                "releaseInstructionId", id,
                "action", "RESUME",
                "status", "QUEUED",
                "appliedAt", Instant.now()
        ));
    }

    @PostMapping("/releases/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelRelease(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody(required = false) @Valid OperationalActionRequest request
    ) {
        String reason = request != null ? request.reasonCode() : "ADMIN_CANCELLED_PRE_DISPATCH";
        operationalControlService.cancelPreDispatchRelease(id, actor, reason);
        return ResponseEntity.ok(Map.of(
                "releaseInstructionId", id,
                "action", "CANCEL",
                "status", "CANCELLED",
                "appliedAt", Instant.now()
        ));
    }

    private SafeReleaseOperationDto buildSafeDto(ReleaseInstruction ri) {
        Optional<FinancialOutboxMessage> outboxOpt = outboxRepository.findByReleaseInstructionId(ri.getId());
        Optional<SettlementExecutionAttempt> attemptOpt = attemptRepository.findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(ri.getId());
        Optional<SettlementObservation> obsOpt = observationRepository.findFirstByReleaseInstructionIdOrderByObservedAtDesc(ri.getId());
        List<ReconciliationItem> reconItems = reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.RELEASE_INSTRUCTION, ri.getId());

        String outboxStatus = outboxOpt.map(m -> m.getStatus().name()).orElse("NONE");
        String attemptStatus = attemptOpt.map(a -> a.getStatus().name()).orElse("NONE");
        String obsFinality = obsOpt.map(o -> o.getFinality().name()).orElse("NONE");
        String reconStatus = reconItems.isEmpty() ? "UNRECONCILED" : reconItems.get(0).getStatus().name();
        String safeReason = attemptOpt.map(SettlementExecutionAttempt::getErrorCode)
                .orElseGet(() -> outboxOpt.map(FinancialOutboxMessage::getLastErrorCode).orElse(null));

        return new SafeReleaseOperationDto(
                ri.getId(),
                ri.getDealId(),
                ri.getMilestoneId(),
                ri.getStatus().name(),
                ri.getAmount(),
                ri.getCurrency(),
                ri.getProvider(),
                ri.getCreatedAt(),
                ri.getUpdatedAt(),
                ri.getAuthorizedAt(),
                ri.getSettledAt(),
                outboxStatus,
                attemptStatus,
                obsFinality,
                reconStatus,
                safeReason
        );
    }

    private Map<String, Object> mapReconciliationItem(ReconciliationItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reconciliationItemId", item.getId());
        map.put("reconciliationRunId", item.getReconciliationRunId());
        map.put("internalEffectId", item.getInternalEffectId());
        map.put("internalEffectType", item.getInternalEffectType());
        map.put("status", item.getStatus());
        map.put("resolutionStatus", item.getResolutionStatus());
        map.put("reasonCode", item.getReasonCode());
        map.put("finxAmount", item.getFinxAmount());
        map.put("bankAmount", item.getBankAmount());
        map.put("discrepancyDetails", item.getDiscrepancyDetails());
        map.put("createdAt", item.getCreatedAt());
        return map;
    }
}
