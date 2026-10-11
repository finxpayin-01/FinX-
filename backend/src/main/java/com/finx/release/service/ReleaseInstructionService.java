package com.finx.release.service;

import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.release.dto.CreateReleaseInstructionCommand;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Service governing ReleaseInstruction creation (TX1).
 * <p>
 * Strictly requires an existing, unexpired EngineDecision(RELEASE, ALLOW) and platform
 * ADMIN or FINANCE authorization. Derives financial amounts, beneficiary accounts, and source
 * funding scopes authoritatively without accepting caller financial overrides.
 * Zero reservation writes, zero escrow balance deductions, zero bank calls.
 */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ReleaseInstructionService {

    private final ReleaseInstructionRepository releaseInstructionRepository;
    private final EngineDecisionRepository engineDecisionRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final VerificationEligibilityService verificationEligibilityService;
    private final ReleaseFundingScopeResolver fundingScopeResolver;
    private final ReleaseDecisionExecutionValidator executionValidator;

    public ReleaseInstructionService(
            ReleaseInstructionRepository releaseInstructionRepository,
            EngineDecisionRepository engineDecisionRepository,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            VerificationEligibilityService verificationEligibilityService,
            ReleaseFundingScopeResolver fundingScopeResolver,
            ReleaseDecisionExecutionValidator executionValidator
    ) {
        this.releaseInstructionRepository = Objects.requireNonNull(releaseInstructionRepository, "releaseInstructionRepository must not be null");
        this.engineDecisionRepository = Objects.requireNonNull(engineDecisionRepository, "engineDecisionRepository must not be null");
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.verificationEligibilityService = Objects.requireNonNull(verificationEligibilityService, "verificationEligibilityService must not be null");
        this.fundingScopeResolver = Objects.requireNonNull(fundingScopeResolver, "fundingScopeResolver must not be null");
        this.executionValidator = Objects.requireNonNull(executionValidator, "executionValidator must not be null");
    }

    public ReleaseInstruction createFromReleaseDecision(
            CreateReleaseInstructionCommand command,
            UserPrincipal currentUser
    ) {
        Objects.requireNonNull(command, "command must not be null");

        // 1. Creation Authority Check (Part C)
        if (currentUser == null) {
            throw new UnauthorizedException("ReleaseInstruction creation requires an authenticated platform principal");
        }
        if (!currentUser.isEnabled()) {
            throw new UnauthorizedException("User account is disabled or suspended: " + currentUser.getId());
        }
        Role role = currentUser.getRole();
        if (role != Role.ADMIN && role != Role.FINANCE) {
            throw new UnauthorizedException("Role " + role + " is not authorized to create release instructions. Only ADMIN and FINANCE permitted.");
        }

        // 2. Load Persisted Release Decision (Part D)
        EngineDecision releaseDecision = engineDecisionRepository.findById(command.releaseDecisionId())
                .orElseThrow(() -> new ResourceNotFoundException("EngineDecision", "id", command.releaseDecisionId()));

        if (releaseDecision.getSubjectId() == null) {
            throw new IllegalStateException("Release decision subjectId must not be null");
        }

        // 3. Resolve Deal & Milestone (Part G)
        Milestone milestone = milestoneRepository.findById(releaseDecision.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", releaseDecision.getSubjectId()));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (!milestone.getDealId().equals(deal.getId())) {
            throw new IllegalStateException("Milestone " + milestone.getId() + " does not belong to Deal " + deal.getId());
        }
        if (deal.getBuyerOrganizationId() == null) {
            throw new IllegalStateException("Deal buyerOrganizationId must not be null");
        }
        if (!deal.getBuyerOrganizationId().equals(releaseDecision.getOrganizationId())) {
            throw new IllegalStateException("Release decision organizationId " + releaseDecision.getOrganizationId()
                    + " does not match deal buyerOrganizationId " + deal.getBuyerOrganizationId());
        }
        if (deal.getSellerOrganizationId() == null) {
            throw new IllegalStateException("Deal sellerOrganizationId must not be null; cannot fallback seller organization");
        }

        // 4. Resolve Canonical Beneficiary (Part J)
        BeneficiaryEligibilityResult bResult = verificationEligibilityService.evaluateBeneficiaryEligibility(deal.getSellerOrganizationId());
        if (!bResult.eligible() || bResult.bankAccount() == null) {
            throw new IllegalStateException("Canonical beneficiary resolution failed for seller organization "
                    + deal.getSellerOrganizationId() + ": " + bResult.failureReason());
        }
        OrganizationBankAccount beneficiary = bResult.bankAccount();

        // 5. Invariant & Currentness Validation (Parts D, E, F, H, I, J)
        executionValidator.validateForCreation(releaseDecision, deal, milestone, beneficiary);

        // 6. Resolve Unique Source Funding Scope (Part K)
        ReleaseFundingScopeResolver.FundingScope fundingScope = fundingScopeResolver.resolveFundingScope(deal, milestone);

        // 7. Compute Deterministic Request Fingerprint (Part M)
        String requestHash = ReleaseInstructionRequestFingerprint.computeRequestFingerprint(
                deal.getBuyerOrganizationId(),
                deal.getId(),
                milestone.getId(),
                releaseDecision.getId(),
                releaseDecision.getDecisionFingerprint(),
                releaseDecision.getInputFingerprint(),
                releaseDecision.getPolicyVersionId(),
                releaseDecision.getPolicyContentHash(),
                beneficiary.getId(),
                fundingScope.provider(),
                fundingScope.providerAccountId(),
                milestone.getAmount(),
                milestone.getCurrency()
        );

        // 8. Creation Idempotency Check (Part N)
        Optional<ReleaseInstruction> existingByIdem = releaseInstructionRepository
                .findByOrganizationIdAndIdempotencyKey(deal.getBuyerOrganizationId(), command.idempotencyKey());
        if (existingByIdem.isPresent()) {
            ReleaseInstruction existing = existingByIdem.get();
            if (existing.getRequestHash().equals(requestHash) && existing.getReleaseDecisionId().equals(releaseDecision.getId())) {
                return existing;
            }
            throw new IllegalStateException("Idempotency conflict: ReleaseInstruction already exists for idempotencyKey '"
                    + command.idempotencyKey() + "' with conflicting request parameters");
        }

        Optional<ReleaseInstruction> existingByDecision = releaseInstructionRepository
                .findByReleaseDecisionId(releaseDecision.getId());
        if (existingByDecision.isPresent()) {
            ReleaseInstruction existing = existingByDecision.get();
            if (existing.getRequestHash().equals(requestHash)) {
                return existing;
            }
            throw new IllegalStateException("ReleaseDecision " + releaseDecision.getId()
                    + " already bound to conflicting ReleaseInstruction " + existing.getId());
        }

        // 9. Persist CREATED ReleaseInstruction (Part O & P)
        ReleaseInstruction instruction = new ReleaseInstruction(
                null,
                deal.getBuyerOrganizationId(),
                deal.getId(),
                milestone.getId(),
                releaseDecision.getId(),
                releaseDecision.getDecisionFingerprint(),
                releaseDecision.getPolicyVersionId(),
                releaseDecision.getPolicyContentHash(),
                beneficiary.getId(),
                fundingScope.provider(),
                fundingScope.providerAccountId(),
                milestone.getAmount(),
                milestone.getCurrency().trim().toUpperCase(),
                command.idempotencyKey(),
                requestHash,
                currentUser.getId()
        );

        return releaseInstructionRepository.save(instruction);
    }
}
