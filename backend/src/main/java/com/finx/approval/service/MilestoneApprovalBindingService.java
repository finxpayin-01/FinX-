package com.finx.approval.service;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.approval.repository.MilestoneApprovalBindingRepository;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class MilestoneApprovalBindingService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneApprovalBindingService.class);

    public static final String REASON_BINDING_MISSING = "APPROVAL_BINDING_MISSING";
    public static final String REASON_MILESTONE_CHANGED = "APPROVAL_BINDING_MILESTONE_CHANGED";
    public static final String REASON_DELIVERABLES_CHANGED = "APPROVAL_BINDING_DELIVERABLES_CHANGED";
    public static final String REASON_AUTHORITY_CHANGED = "APPROVAL_BINDING_AUTHORITY_CHANGED";
    public static final String REASON_FINGERPRINT_MISMATCH = "APPROVAL_BINDING_FINGERPRINT_MISMATCH";
    public static final String REASON_CURRENT = "APPROVAL_BINDING_CURRENT";

    private final MilestoneApprovalBindingRepository bindingRepository;
    private final MilestoneApprovalFingerprintService fingerprintService;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DeliverableRepository deliverableRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public MilestoneApprovalBindingService(
            MilestoneApprovalBindingRepository bindingRepository,
            MilestoneApprovalFingerprintService fingerprintService,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            DeliverableRepository deliverableRepository
    ) {
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository must not be null");
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService must not be null");
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.deliverableRepository = Objects.requireNonNull(deliverableRepository, "deliverableRepository must not be null");
    }

    public MilestoneApprovalBindingService(
            MilestoneApprovalBindingRepository bindingRepository,
            MilestoneApprovalFingerprintService fingerprintService,
            MilestoneRepository milestoneRepository,
            DeliverableRepository deliverableRepository,
            DealRepository dealRepository
    ) {
        this(bindingRepository, fingerprintService, dealRepository, milestoneRepository, deliverableRepository);
    }

    /**
     * Creates an immutable, durable milestone approval binding within an active approval transaction.
     * Only modern deals with a bound buyerOrganizationId receive an authoritative financial approval binding.
     */
    @Transactional
    public Optional<MilestoneApprovalBinding> createBinding(
            Deal deal,
            Milestone milestone,
            List<Deliverable> deliverables,
            UserPrincipal currentUser,
            Instant approvedAt
    ) {
        Objects.requireNonNull(deal, "deal must not be null");
        Objects.requireNonNull(milestone, "milestone must not be null");
        Objects.requireNonNull(deliverables, "deliverables must not be null");
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");

        MilestoneApprovalAuthority authority;
        if (deal.getBuyerId().equals(currentUser.getId())) {
            authority = MilestoneApprovalAuthority.BUYER;
        } else if (currentUser.getRole() == Role.PROJECT_MANAGER
                && deal.getProjectManagerId() != null
                && deal.getProjectManagerId().equals(currentUser.getId())) {
            authority = MilestoneApprovalAuthority.PROJECT_MANAGER;
        } else if (currentUser.getRole() == Role.ADMIN) {
            authority = MilestoneApprovalAuthority.ADMIN;
        } else {
            throw new UnauthorizedException("User " + currentUser.getId() + " is unauthorized to approve milestone " + milestone.getId());
        }

        return createBinding(deal, milestone, deliverables, currentUser.getId(), authority, approvedAt);
    }

    @Transactional
    public Optional<MilestoneApprovalBinding> createBinding(
            Deal deal,
            Milestone milestone,
            List<Deliverable> deliverables,
            UUID approvedBy,
            MilestoneApprovalAuthority authority,
            Instant approvedAt
    ) {
        Objects.requireNonNull(deal, "deal must not be null");
        Objects.requireNonNull(milestone, "milestone must not be null");
        Objects.requireNonNull(deliverables, "deliverables must not be null");
        Objects.requireNonNull(approvedBy, "approvedBy must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");

        // Legacy compatibility check: Deal must be bound to a buyer organization for financial release
        if (deal.getBuyerOrganizationId() == null) {
            log.warn("Deal {} lacks buyerOrganizationId. Skipping durable financial approval binding creation.", deal.getId());
            return Optional.empty();
        }

        String milestoneFp = fingerprintService.computeMilestoneFingerprint(milestone);
        String deliverablesFp = fingerprintService.computeDeliverablesFingerprint(deliverables);
        String approvalFp = fingerprintService.computeApprovalFingerprint(
                deal.getBuyerOrganizationId(),
                deal.getId(),
                milestone.getId(),
                approvedBy,
                authority,
                deal.getProjectManagerId(),
                milestoneFp,
                deliverablesFp,
                approvedAt
        );

        MilestoneApprovalBinding binding = new MilestoneApprovalBinding(
                UUID.randomUUID(),
                deal.getBuyerOrganizationId(),
                deal.getId(),
                milestone.getId(),
                approvedBy,
                authority,
                milestoneFp,
                deliverablesFp,
                approvalFp,
                deal.getProjectManagerId(),
                approvedAt,
                approvedAt
        );

        MilestoneApprovalBinding saved = bindingRepository.saveAndFlush(binding);
        log.info("Created durable milestone approval binding: id={} milestoneId={} authority={}",
                saved.getId(), saved.getMilestoneId(), saved.getApprovalAuthority());
        return Optional.of(saved);
    }

    /**
     * Evaluates whether a durable approval binding is present and currently valid for the given deal milestone.
     */
    @Transactional(readOnly = true)
    public ApprovalBindingEvaluation evaluateCurrentBinding(
            Deal deal,
            Milestone milestone,
            List<Deliverable> deliverables
    ) {
        if (deal == null || milestone == null) {
            return new ApprovalBindingEvaluation(false, false, REASON_BINDING_MISSING, null);
        }

        Optional<MilestoneApprovalBinding> bindingOpt = bindingRepository.findByMilestoneId(milestone.getId());
        if (bindingOpt.isEmpty()) {
            return new ApprovalBindingEvaluation(false, false, REASON_BINDING_MISSING, null);
        }

        MilestoneApprovalBinding binding = bindingOpt.get();

        // 1. Verify organization and deal binding scope
        if (!deal.getId().equals(binding.getDealId())
                || deal.getBuyerOrganizationId() == null
                || !deal.getBuyerOrganizationId().equals(binding.getOrganizationId())) {
            return new ApprovalBindingEvaluation(true, false, REASON_AUTHORITY_CHANGED, binding);
        }

        // 2. Verify material milestone fields haven't changed since approval
        String currentMilestoneFp = fingerprintService.computeMilestoneFingerprint(milestone);
        if (!currentMilestoneFp.equals(binding.getMilestoneFingerprint())) {
            return new ApprovalBindingEvaluation(true, false, REASON_MILESTONE_CHANGED, binding);
        }

        // 3. Verify deliverables haven't changed since approval
        String currentDeliverablesFp = fingerprintService.computeDeliverablesFingerprint(deliverables);
        if (!currentDeliverablesFp.equals(binding.getDeliverablesFingerprint())) {
            return new ApprovalBindingEvaluation(true, false, REASON_DELIVERABLES_CHANGED, binding);
        }

        // 4. Verify authority semantics based on captured historical authority
        switch (binding.getApprovalAuthority()) {
            case BUYER -> {
                if (!deal.getBuyerId().equals(binding.getApprovedBy())) {
                    return new ApprovalBindingEvaluation(true, false, REASON_AUTHORITY_CHANGED, binding);
                }
            }
            case PROJECT_MANAGER -> {
                if (deal.getProjectManagerId() == null
                        || !deal.getProjectManagerId().equals(binding.getApprovedBy())
                        || !binding.getApprovedBy().equals(binding.getProjectManagerId())) {
                    return new ApprovalBindingEvaluation(true, false, REASON_AUTHORITY_CHANGED, binding);
                }
            }
            case ADMIN -> {
                // Historical platform admin authority was certified at approval time.
            }
        }

        // 5. Verify the approval fingerprint itself remains cryptographically coherent
        String expectedApprovalFp = fingerprintService.computeApprovalFingerprint(
                binding.getOrganizationId(),
                binding.getDealId(),
                binding.getMilestoneId(),
                binding.getApprovedBy(),
                binding.getApprovalAuthority(),
                binding.getProjectManagerId(),
                binding.getMilestoneFingerprint(),
                binding.getDeliverablesFingerprint(),
                binding.getApprovedAt()
        );
        if (!expectedApprovalFp.equals(binding.getApprovalFingerprint())) {
            return new ApprovalBindingEvaluation(true, false, REASON_FINGERPRINT_MISMATCH, binding);
        }

        return new ApprovalBindingEvaluation(true, true, REASON_CURRENT, binding);
    }

    /**
     * Convenience method to evaluate binding by IDs directly from persistence.
     */
    @Transactional(readOnly = true)
    public ApprovalBindingEvaluation evaluateCurrentBinding(UUID dealId, UUID milestoneId) {
        if (dealId == null || milestoneId == null) {
            return new ApprovalBindingEvaluation(false, false, REASON_BINDING_MISSING, null);
        }
        Deal deal = dealRepository.findById(dealId).orElse(null);
        Milestone milestone = milestoneRepository.findById(milestoneId).orElse(null);
        if (deal == null || milestone == null) {
            return new ApprovalBindingEvaluation(false, false, REASON_BINDING_MISSING, null);
        }
        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId);
        return evaluateCurrentBinding(deal, milestone, deliverables);
    }

    @Transactional(readOnly = true)
    public ApprovalBindingEvaluation evaluateCurrentBinding(UUID milestoneId) {
        if (milestoneId == null) {
            return new ApprovalBindingEvaluation(false, false, REASON_BINDING_MISSING, null);
        }
        Milestone milestone = milestoneRepository.findById(milestoneId).orElse(null);
        if (milestone == null) {
            return new ApprovalBindingEvaluation(false, false, REASON_BINDING_MISSING, null);
        }
        return evaluateCurrentBinding(milestone.getDealId(), milestoneId);
    }

    @Transactional(readOnly = true)
    public Optional<MilestoneApprovalBinding> findByMilestoneId(UUID milestoneId) {
        if (milestoneId == null) return Optional.empty();
        return bindingRepository.findByMilestoneId(milestoneId);
    }
}
