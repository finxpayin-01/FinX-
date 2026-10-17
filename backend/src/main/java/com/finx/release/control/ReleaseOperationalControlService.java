package com.finx.release.control;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.exception.ResourceNotFoundException;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service governing pre-dispatch operational controls: HOLD, RESUME, and CANCEL.
 * <p>
 * STRICT SAFETY BOUNDARIES:
 * - Allowed ONLY for ADMIN and FINANCE roles.
 * - Allowed ONLY prior to the external network-start boundary (requestStartedAt == null).
 * - Lock order is strictly compatible with the worker:
 *     1. FinancialOutboxMessage
 *     2. SettlementExecutionAttempt (if any)
 *     3. ReleaseInstruction
 *     4. ReleaseReservation
 * - Zero bank calls, zero ledger mutations, zero escrow balance deduction.
 */
@Service
public class ReleaseOperationalControlService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOperationalControlService.class);

    private final FinancialOutboxRepository outboxRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final ReleaseInstructionRepository instructionRepository;
    private final ReleaseReservationRepository reservationRepository;
    private final AuditService auditService;

    public ReleaseOperationalControlService(
            FinancialOutboxRepository outboxRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            ReleaseInstructionRepository instructionRepository,
            ReleaseReservationRepository reservationRepository,
            AuditService auditService
    ) {
        this.outboxRepository = outboxRepository;
        this.attemptRepository = attemptRepository;
        this.instructionRepository = instructionRepository;
        this.reservationRepository = reservationRepository;
        this.auditService = auditService;
    }

    /**
     * Places a QUEUED release on administrative hold prior to worker dispatch.
     */
    @Transactional
    public void holdPreDispatchRelease(UUID releaseInstructionId, UserPrincipal actor, String reasonCode) {
        validateAdminOrFinance(actor);
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        String safeReason = sanitizeReason(reasonCode, "ADMIN_HOLD");

        Instant now = Instant.now();

        // 1. Lock Outbox message
        FinancialOutboxMessage outbox = outboxRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new IllegalStateException("Outbox message not found for release instruction: " + releaseInstructionId));

        if (outbox.getStatus() != FinancialOutboxStatus.PENDING && outbox.getStatus() != FinancialOutboxStatus.RETRY) {
            throw new IllegalStateException("Cannot hold release: outbox status is " + outbox.getStatus());
        }

        // 2. Lock latest Attempt if exists and verify network start boundary
        assertNoNetworkStartedAttempt(releaseInstructionId);

        // 3. Lock ReleaseInstruction
        ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

        if (instruction.getStatus() != ReleaseInstructionStatus.QUEUED) {
            throw new IllegalStateException("Cannot hold release: instruction status is " + instruction.getStatus());
        }

        // 4. Lock Reservation
        ReleaseReservation reservation = reservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new IllegalStateException("Active reservation required for release: " + releaseInstructionId));

        if (reservation.getStatus() != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("Cannot hold release: reservation status is " + reservation.getStatus());
        }

        // Atomic mutation: QUEUED -> MANUAL_REVIEW, outbox -> HELD, reservation remains ACTIVE
        instruction.markPreDispatchManualReview();
        outbox.markAdministrativelyHeld(now, safeReason);

        instructionRepository.save(instruction);
        outboxRepository.save(outbox);

        auditService.logEvent(actor.getId(), "RELEASE_ADMIN_HOLD", "RELEASE_INSTRUCTION", releaseInstructionId.toString(),
                "reason=" + safeReason + ";actor=" + actor.getName());

        log.info("RELEASE_ADMIN_HOLD_APPLIED: releaseId=[{}] actor=[{}] reason=[{}]",
                releaseInstructionId, actor.getId(), safeReason);
    }

    /**
     * Resumes an administratively held release from MANUAL_REVIEW back to QUEUED.
     */
    @Transactional
    public void resumeHeldRelease(UUID releaseInstructionId, UserPrincipal actor, String reasonCode) {
        validateAdminOrFinance(actor);
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        String safeReason = sanitizeReason(reasonCode, "ADMIN_RESUME");

        Instant now = Instant.now();

        // 1. Lock Outbox message
        FinancialOutboxMessage outbox = outboxRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new IllegalStateException("Outbox message not found for release instruction: " + releaseInstructionId));

        if (outbox.getStatus() != FinancialOutboxStatus.HELD) {
            throw new IllegalStateException("Cannot resume release: outbox status is " + outbox.getStatus());
        }

        // 2. Lock latest Attempt if exists and verify network start boundary
        assertNoNetworkStartedAttempt(releaseInstructionId);

        // 3. Lock ReleaseInstruction
        ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

        if (instruction.getStatus() != ReleaseInstructionStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("Cannot resume release: instruction status is " + instruction.getStatus());
        }

        // 4. Lock Reservation
        ReleaseReservation reservation = reservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new IllegalStateException("Active reservation required for release: " + releaseInstructionId));

        if (reservation.getStatus() != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("Cannot resume release: reservation status is " + reservation.getStatus());
        }

        // Atomic mutation: MANUAL_REVIEW -> QUEUED, HELD -> PENDING, reservation remains ACTIVE
        instruction.resumeFromPreDispatchManualReview();
        outbox.resumeFromAdministrativeHold(now, now);

        instructionRepository.save(instruction);
        outboxRepository.save(outbox);

        auditService.logEvent(actor.getId(), "RELEASE_ADMIN_RESUME", "RELEASE_INSTRUCTION", releaseInstructionId.toString(),
                "reason=" + safeReason + ";actor=" + actor.getName());

        log.info("RELEASE_ADMIN_RESUME_APPLIED: releaseId=[{}] actor=[{}] reason=[{}]",
                releaseInstructionId, actor.getId(), safeReason);
    }

    /**
     * Administratively cancels a release prior to network dispatch.
     */
    @Transactional
    public void cancelPreDispatchRelease(UUID releaseInstructionId, UserPrincipal actor, String reasonCode) {
        validateAdminOrFinance(actor);
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        String safeReason = sanitizeReason(reasonCode, "ADMIN_CANCELLED_PRE_DISPATCH");

        Instant now = Instant.now();

        // Check if outbox exists yet
        Optional<FinancialOutboxMessage> outboxOpt = outboxRepository.findByReleaseInstructionId(releaseInstructionId);

        if (outboxOpt.isEmpty()) {
            // Early pre-authorization cancel (CREATED status, no outbox/reservation yet)
            ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(releaseInstructionId)
                    .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

            if (instruction.getStatus() != ReleaseInstructionStatus.CREATED) {
                throw new IllegalStateException("Cannot cancel release without outbox in status " + instruction.getStatus());
            }

            instruction.markCancelled();
            instructionRepository.save(instruction);

            auditService.logEvent(actor.getId(), "RELEASE_ADMIN_CANCELLED", "RELEASE_INSTRUCTION", releaseInstructionId.toString(),
                    "reason=" + safeReason + ";actor=" + actor.getName());

            log.info("RELEASE_ADMIN_CANCELLED_CREATED: releaseId=[{}] actor=[{}]", releaseInstructionId, actor.getId());
            return;
        }

        // 1. Lock Outbox message
        FinancialOutboxMessage outbox = outboxRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new IllegalStateException("Outbox message not found for release: " + releaseInstructionId));

        if (outbox.getStatus() != FinancialOutboxStatus.PENDING 
                && outbox.getStatus() != FinancialOutboxStatus.RETRY 
                && outbox.getStatus() != FinancialOutboxStatus.HELD) {
            throw new IllegalStateException("Cannot cancel release: outbox status is " + outbox.getStatus());
        }

        // 2. Lock latest Attempt if exists and verify network start boundary
        assertNoNetworkStartedAttempt(releaseInstructionId);

        // 3. Lock ReleaseInstruction
        ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

        if (instruction.getStatus() != ReleaseInstructionStatus.QUEUED 
                && instruction.getStatus() != ReleaseInstructionStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("Cannot cancel release: instruction status is " + instruction.getStatus());
        }

        // 4. Lock Reservation
        ReleaseReservation reservation = reservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new IllegalStateException("Active reservation required for release: " + releaseInstructionId));

        if (reservation.getStatus() != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("Cannot cancel release: reservation status is " + reservation.getStatus());
        }

        // Atomic cancel: outbox -> DEAD_LETTER, reservation -> RELEASED, instruction -> CANCELLED
        outbox.markAdministrativelyCancelled(now, "ADMIN_CANCELLED_PRE_DISPATCH: " + safeReason);
        reservation.markReleased(now);
        instruction.markCancelled();

        outboxRepository.save(outbox);
        reservationRepository.save(reservation);
        instructionRepository.save(instruction);

        auditService.logEvent(actor.getId(), "RELEASE_ADMIN_CANCELLED", "RELEASE_INSTRUCTION", releaseInstructionId.toString(),
                "reason=" + safeReason + ";actor=" + actor.getName());

        log.info("RELEASE_ADMIN_CANCELLED_APPLIED: releaseId=[{}] actor=[{}] reason=[{}]",
                releaseInstructionId, actor.getId(), safeReason);
    }

    private void assertNoNetworkStartedAttempt(UUID releaseInstructionId) {
        Optional<SettlementExecutionAttempt> attemptOpt = attemptRepository
                .findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(releaseInstructionId);

        if (attemptOpt.isPresent()) {
            SettlementExecutionAttempt attempt = attemptRepository.findByIdForUpdate(attemptOpt.get().getId())
                    .orElse(attemptOpt.get());

            if (attempt.getRequestStartedAt() != null) {
                throw new IllegalStateException("CRITICAL_SAFETY_VIOLATION: Cannot modify release because network dispatch has already started at "
                        + attempt.getRequestStartedAt());
            }

            SettlementExecutionAttemptStatus status = attempt.getStatus();
            if (status == SettlementExecutionAttemptStatus.SENDING 
                    || status == SettlementExecutionAttemptStatus.ACKNOWLEDGED 
                    || status == SettlementExecutionAttemptStatus.UNKNOWN) {
                throw new IllegalStateException("CRITICAL_SAFETY_VIOLATION: Cannot modify release when execution attempt is in status: " + status);
            }
        }
    }

    private void validateAdminOrFinance(UserPrincipal actor) {
        if (actor == null) {
            throw new AccessDeniedException("Authentication required for operational control");
        }
        Role role = actor.getRole();
        if (role != Role.ADMIN && role != Role.FINANCE) {
            throw new AccessDeniedException("Operational release controls require ADMIN or FINANCE role. Current: " + role);
        }
    }

    private String sanitizeReason(String reasonCode, String defaultReason) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return defaultReason;
        }
        String trimmed = reasonCode.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }
}
