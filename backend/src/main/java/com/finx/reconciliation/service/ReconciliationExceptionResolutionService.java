package com.finx.reconciliation.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Human reconciliation exception lifecycle service (Phase R4 / R16).
 *
 * Allows authorized platform finance and administration operators to acknowledge,
 * investigate, and resolve reconciliation discrepancies without altering
 * underlying deterministic financial truth or matcher classifications.
 */
@Service
public class ReconciliationExceptionResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationExceptionResolutionService.class);

    private final ReconciliationItemRepository itemRepository;
    private final ReconciliationRunRepository runRepository;
    private final AuditService auditService;

    public ReconciliationExceptionResolutionService(ReconciliationItemRepository itemRepository,
                                                   ReconciliationRunRepository runRepository,
                                                   AuditService auditService) {
        this.itemRepository = itemRepository;
        this.runRepository = runRepository;
        this.auditService = auditService;
    }

    /**
     * Resolve a reconciliation discrepancy exception.
     *
     * @param itemId reconciliation item ID to resolve
     * @param targetStatus target resolution status (RESOLVED or IGNORED)
     * @param resolutionNotes human operator justification notes (mandatory)
     * @param currentUser authenticated platform principal (ADMIN or FINANCE)
     * @return updated ReconciliationItem with updated resolution fields
     */
    @Transactional
    public ReconciliationItem resolveException(UUID itemId, ResolutionStatus targetStatus, String resolutionNotes, UserPrincipal currentUser) {
        // 1. Authorization: Platform ADMIN or FINANCE only
        requirePlatformAdminOrFinance(currentUser);

        if (itemId == null) {
            throw new IllegalArgumentException("Reconciliation item ID cannot be null");
        }
        if (targetStatus == null || targetStatus == ResolutionStatus.UNRESOLVED) {
            throw new IllegalArgumentException("Target resolution status must be explicitly RESOLVED or IGNORED");
        }
        if (resolutionNotes == null || resolutionNotes.trim().isEmpty()) {
            throw new IllegalArgumentException("Resolution notes/justification cannot be blank");
        }

        // 2. Load reconciliation item
        ReconciliationItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Reconciliation item not found: " + itemId));

        // 3. Prohibit resolving MATCHED items
        if (item.getStatus() == ReconciliationItemStatus.MATCHED) {
            throw new IllegalStateException("MATCHED reconciliation items cannot be processed through exception resolution");
        }

        // 4. Load associated run and verify authoritative matcher version
        ReconciliationRun run = runRepository.findById(item.getReconciliationRunId())
                .orElseThrow(() -> new IllegalStateException("Associated reconciliation run not found: " + item.getReconciliationRunId()));

        if (!DeterministicReconciliationMatcher.MATCHER_VERSION.equals(run.getMatcherVersion())) {
            throw new IllegalStateException("Only authoritative deterministic reconciliation runs (" +
                    DeterministicReconciliationMatcher.MATCHER_VERSION + ") may be resolved via this workflow. Legacy run rejected: " + run.getMatcherVersion());
        }

        // 5. Enforce one-way state transition: UNRESOLVED -> (RESOLVED | IGNORED)
        ResolutionStatus currentResolutionStatus = item.getResolutionStatus();
        if (currentResolutionStatus != ResolutionStatus.UNRESOLVED) {
            throw new IllegalStateException("Reconciliation item is already in terminal resolution status: " +
                    currentResolutionStatus + ". Further status changes are forbidden.");
        }

        // 6. Update resolution fields ONLY (preserve matcher facts, amounts, reason codes)
        item.setResolutionStatus(targetStatus);
        item.setResolvedBy(currentUser.getId());
        item.setResolvedAt(Instant.now());
        item.setResolutionNotes(resolutionNotes.trim());

        ReconciliationItem savedItem = itemRepository.save(item);

        // 7. Audit log
        String auditMetadata = String.format("runId=%s;previousResolutionStatus=%s;newResolutionStatus=%s;reasonCode=%s;itemStatus=%s",
                run.getId(), currentResolutionStatus, targetStatus, item.getReasonCode(), item.getStatus());
        auditService.logEvent(currentUser.getId(), "RECONCILIATION_EXCEPTION_RESOLVED", "RECONCILIATION_ITEM", itemId.toString(), auditMetadata);

        log.info("Reconciliation exception resolved: itemId={} runId={} resolutionStatus={} reasonCode={} actor={}",
                itemId, run.getId(), targetStatus, item.getReasonCode(), currentUser.getId());

        return savedItem;
    }

    @Transactional(readOnly = true)
    public ReconciliationItem getExceptionById(UUID itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("Reconciliation item ID cannot be null");
        }
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Reconciliation item not found: " + itemId));
    }

    @Transactional(readOnly = true)
    public List<ReconciliationItem> getExceptionsByRunId(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("Run ID cannot be null");
        }
        return itemRepository.findByReconciliationRunId(runId);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationItem> getUnresolvedExceptionsByRunId(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("Run ID cannot be null");
        }
        return itemRepository.findByReconciliationRunIdAndResolutionStatus(runId, ResolutionStatus.UNRESOLVED);
    }

    private void requirePlatformAdminOrFinance(UserPrincipal currentUser) {
        if (currentUser == null || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE)) {
            throw new AccessDeniedException("Platform ADMIN or FINANCE role required for reconciliation exception resolution");
        }
    }
}
