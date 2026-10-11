package com.finx.release.service;

import com.finx.deal.entity.Deal;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import com.finx.engine.policy.release.ReleasePolicyDefinition;
import com.finx.engine.release.ReleaseDecisionEngine;
import com.finx.engine.release.ReleaseEvaluationResult;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.milestone.entity.Milestone;
import com.finx.org.entity.OrganizationBankAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Validates persisted EngineDecision(RELEASE, ALLOW) integrity, policy binding,
 * latest decision status, component decision references, and performs deterministic
 * read-only currentness re-evaluation against active deal and milestone state.
 * <p>
 * STRICT INVARIANT:
 * Pure read-only verification.
 * Zero database writes to engine_decisions.
 * Zero bank calls.
 */
@Service
@Transactional(readOnly = true)
public class ReleaseDecisionExecutionValidator {

    private final EngineDecisionRepository engineDecisionRepository;
    private final EnginePolicyVersionRepository policyRepository;
    private final ReleaseDecisionEngine releaseDecisionEngine;

    public ReleaseDecisionExecutionValidator(
            EngineDecisionRepository engineDecisionRepository,
            EnginePolicyVersionRepository policyRepository,
            ReleaseDecisionEngine releaseDecisionEngine
    ) {
        this.engineDecisionRepository = Objects.requireNonNull(engineDecisionRepository, "engineDecisionRepository must not be null");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
        this.releaseDecisionEngine = Objects.requireNonNull(releaseDecisionEngine, "releaseDecisionEngine must not be null");
    }

    /**
     * Validates a release decision for ReleaseInstruction creation.
     */
    public void validateForCreation(
            EngineDecision releaseDecision,
            Deal deal,
            Milestone milestone,
            OrganizationBankAccount canonicalBeneficiary
    ) {
        Objects.requireNonNull(releaseDecision, "releaseDecision must not be null");
        Objects.requireNonNull(deal, "deal must not be null");
        Objects.requireNonNull(milestone, "milestone must not be null");
        Objects.requireNonNull(canonicalBeneficiary, "canonicalBeneficiary must not be null");

        validateCommonDecisionIntegrity(releaseDecision, deal, milestone);
        validateComponentDecisionReferences(releaseDecision);
        validateBeneficiaryReference(releaseDecision, canonicalBeneficiary.getId());
        validateDeterministicCurrentness(releaseDecision, deal, milestone);
    }

    /**
     * Validates a release decision during authorization and reservation under locks.
     */
    public void validateForExecution(
            UUID releaseDecisionId,
            Deal deal,
            Milestone milestone,
            UUID expectedBeneficiaryBankAccountId
    ) {
        Objects.requireNonNull(releaseDecisionId, "releaseDecisionId must not be null");
        Objects.requireNonNull(deal, "deal must not be null");
        Objects.requireNonNull(milestone, "milestone must not be null");
        Objects.requireNonNull(expectedBeneficiaryBankAccountId, "expectedBeneficiaryBankAccountId must not be null");

        EngineDecision releaseDecision = engineDecisionRepository.findById(releaseDecisionId)
                .orElseThrow(() -> new ResourceNotFoundException("EngineDecision", "id", releaseDecisionId));

        validateCommonDecisionIntegrity(releaseDecision, deal, milestone);
        validateComponentDecisionReferences(releaseDecision);
        validateBeneficiaryReference(releaseDecision, expectedBeneficiaryBankAccountId);
        validateDeterministicCurrentness(releaseDecision, deal, milestone);
    }

    private void validateCommonDecisionIntegrity(EngineDecision decision, Deal deal, Milestone milestone) {
        // 1. EngineType and Decision Status
        if (decision.getEngineType() != EngineType.RELEASE) {
            throw new IllegalStateException("Expected engineType RELEASE but was " + decision.getEngineType());
        }
        if (decision.getDecision() != DecisionStatus.ALLOW) {
            throw new IllegalStateException("Expected decision ALLOW but was " + decision.getDecision());
        }
        if (decision.getEvaluationOutcome() != EvaluationOutcome.SUCCESS) {
            throw new IllegalStateException("Expected evaluationOutcome SUCCESS but was " + decision.getEvaluationOutcome());
        }
        if (!"MILESTONE".equalsIgnoreCase(decision.getSubjectType())) {
            throw new IllegalStateException("Expected subjectType MILESTONE but was " + decision.getSubjectType());
        }
        if (!milestone.getId().equals(decision.getSubjectId())) {
            throw new IllegalStateException("SubjectId mismatch: milestone " + milestone.getId() + " vs decision " + decision.getSubjectId());
        }
        if (deal.getBuyerOrganizationId() == null || !deal.getBuyerOrganizationId().equals(decision.getOrganizationId())) {
            throw new IllegalStateException("OrganizationId mismatch: deal buyer " + deal.getBuyerOrganizationId() + " vs decision " + decision.getOrganizationId());
        }

        // 2. Hash and Fingerprint formats (64-character lowercase hex)
        String inputFp = decision.getInputFingerprint();
        if (inputFp == null || !inputFp.matches("^[0-9a-f]{64}$")) {
            throw new IllegalStateException("Invalid inputFingerprint: " + inputFp);
        }
        String decisionFp = decision.getDecisionFingerprint();
        if (decisionFp == null || !decisionFp.matches("^[0-9a-f]{64}$")) {
            throw new IllegalStateException("Invalid decisionFingerprint: " + decisionFp);
        }
        String policyHash = decision.getPolicyContentHash();
        if (policyHash == null || !policyHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalStateException("Invalid policyContentHash: " + policyHash);
        }

        if (decision.getEvaluatedAt() == null) {
            throw new IllegalStateException("Decision evaluatedAt must not be null");
        }
        if (decision.getValidUntil() != null && !Instant.now().isBefore(decision.getValidUntil())) {
            throw new IllegalStateException("Release decision has expired: validUntil=" + decision.getValidUntil());
        }

        // 3. Platform Release Policy Binding (Part E)
        if (decision.getPolicyVersionId() == null) {
            throw new IllegalStateException("Decision policyVersionId must not be null");
        }
        EnginePolicyVersion policy = policyRepository.findById(decision.getPolicyVersionId())
                .orElseThrow(() -> new IllegalStateException("EnginePolicyVersion not found: " + decision.getPolicyVersionId()));

        if (policy.getEngineType() != EngineType.RELEASE) {
            throw new IllegalStateException("EnginePolicyVersion engineType mismatch: " + policy.getEngineType());
        }
        if (policy.getScope() != PolicyScope.PLATFORM) {
            throw new IllegalStateException("EnginePolicyVersion scope mismatch: " + policy.getScope());
        }
        if (policy.getOrganizationId() != null) {
            throw new IllegalStateException("EnginePolicyVersion platform scope must have null organizationId");
        }
        if (!ReleasePolicyDefinition.POLICY_KEY.equals(policy.getPolicyKey())) {
            throw new IllegalStateException("PolicyKey mismatch: " + policy.getPolicyKey());
        }
        if (!ReleasePolicyDefinition.POLICY_VERSION.equals(policy.getVersion())) {
            throw new IllegalStateException("PolicyVersion mismatch: " + policy.getVersion());
        }
        if (policy.getActivatedAt() == null) {
            throw new IllegalStateException("Policy version is not active: activatedAt is null");
        }
        if (!ReleasePolicyDefinition.getExpectedContentHash().equals(policy.getContentHash())) {
            throw new IllegalStateException("Policy content hash mismatch: " + policy.getContentHash());
        }
        if (!ReleasePolicyDefinition.getCanonicalPolicyContent().equals(policy.getPolicyContent())) {
            throw new IllegalStateException("Canonical policy content mismatch");
        }
        if (!policy.getContentHash().equals(decision.getPolicyContentHash())) {
            throw new IllegalStateException("Decision policyContentHash does not match active policy content hash");
        }

        // 4. Latest Decision Rule (Part F)
        Optional<EngineDecision> latestOpt = engineDecisionRepository.findFirstByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
                decision.getOrganizationId(),
                EngineType.RELEASE,
                "MILESTONE",
                decision.getSubjectId()
        );
        if (latestOpt.isEmpty() || !latestOpt.get().getId().equals(decision.getId())) {
            throw new IllegalStateException("Stale release decision: a newer release decision exists for milestone " + milestone.getId());
        }
    }

    private void validateComponentDecisionReferences(EngineDecision releaseDecision) {
        List<EvidenceReference> refs = releaseDecision.getEvidenceReferences();
        List<EvidenceReference> componentRefs = refs.stream()
                .filter(r -> "ENGINE_DECISION".equalsIgnoreCase(r.sourceType()))
                .toList();

        if (componentRefs.size() != 8) {
            throw new IllegalStateException("Expected exactly 8 component ENGINE_DECISION evidence references, found: " + componentRefs.size());
        }

        Set<EngineType> seenTypes = new HashSet<>();
        for (EvidenceReference ref : componentRefs) {
            if (ref.sourceId() == null) {
                throw new IllegalStateException("Component decision reference sourceId must not be null");
            }
            EngineType engineType;
            try {
                engineType = EngineType.valueOf(ref.provenance());
            } catch (Exception e) {
                throw new IllegalStateException("Invalid component engine type in reference provenance: " + ref.provenance());
            }

            if (!ReleasePolicyDefinition.REQUIRED_ENGINES.contains(engineType)) {
                throw new IllegalStateException("Unexpected component engine type: " + engineType);
            }
            if (!seenTypes.add(engineType)) {
                throw new IllegalStateException("Duplicate component engine type in references: " + engineType);
            }

            EngineDecision compDec = engineDecisionRepository.findById(ref.sourceId())
                    .orElseThrow(() -> new IllegalStateException("Referenced component EngineDecision not found: " + ref.sourceId()));

            if (!releaseDecision.getEvaluationId().equals(compDec.getEvaluationId())) {
                throw new IllegalStateException("Component evaluationId mismatch: " + compDec.getEvaluationId() + " vs " + releaseDecision.getEvaluationId());
            }
            if (!releaseDecision.getCorrelationId().equals(compDec.getCorrelationId())) {
                throw new IllegalStateException("Component correlationId mismatch: " + compDec.getCorrelationId() + " vs " + releaseDecision.getCorrelationId());
            }
            if (compDec.getDecision() != DecisionStatus.ALLOW) {
                throw new IllegalStateException("Component decision must be ALLOW, but was " + compDec.getDecision() + " for " + engineType);
            }
            if (compDec.getEvaluationOutcome() != EvaluationOutcome.SUCCESS) {
                throw new IllegalStateException("Component evaluationOutcome must be SUCCESS, but was " + compDec.getEvaluationOutcome() + " for " + engineType);
            }
            if (compDec.getEngineType() != engineType) {
                throw new IllegalStateException("Component entity engineType mismatch: " + compDec.getEngineType() + " vs reference " + engineType);
            }
        }

        if (seenTypes.size() != 8) {
            throw new IllegalStateException("Missing required component engine types. Seen: " + seenTypes);
        }
    }

    private void validateBeneficiaryReference(EngineDecision releaseDecision, UUID expectedBeneficiaryId) {
        List<EvidenceReference> refs = releaseDecision.getEvidenceReferences();
        List<EvidenceReference> bankAccountRefs = refs.stream()
                .filter(r -> "ORGANIZATION_BANK_ACCOUNT".equalsIgnoreCase(r.sourceType()))
                .toList();

        if (bankAccountRefs.size() != 1) {
            throw new IllegalStateException("Expected exactly 1 ORGANIZATION_BANK_ACCOUNT reference on RELEASE decision, found: " + bankAccountRefs.size());
        }

        EvidenceReference bankRef = bankAccountRefs.get(0);
        if (!expectedBeneficiaryId.equals(bankRef.sourceId())) {
            throw new IllegalStateException("Canonical beneficiary account changed: decision references " + bankRef.sourceId()
                    + " but current eligible beneficiary is " + expectedBeneficiaryId);
        }
    }

    private void validateDeterministicCurrentness(EngineDecision releaseDecision, Deal deal, Milestone milestone) {
        // Read-only in-memory deterministic re-evaluation
        ReleaseEvaluationResult reEvalResult = releaseDecisionEngine.evaluateMilestone(
                deal.getId(),
                milestone.getId(),
                releaseDecision.getEvaluationId(),
                releaseDecision.getCorrelationId(),
                releaseDecision.getEvaluatedAt(),
                null
        );

        RecordEngineDecisionCommand cmd = reEvalResult.releaseCommand();
        if (cmd.decision() != DecisionStatus.ALLOW) {
            throw new IllegalStateException("Deterministic re-evaluation yielded non-ALLOW: " + cmd.decision());
        }
        if (cmd.evaluationOutcome() != EvaluationOutcome.SUCCESS) {
            throw new IllegalStateException("Deterministic re-evaluation yielded non-SUCCESS: " + cmd.evaluationOutcome());
        }
        if (!deal.getBuyerOrganizationId().equals(cmd.organizationId())) {
            throw new IllegalStateException("Deterministic re-evaluation organizationId mismatch");
        }
        if (!milestone.getId().equals(cmd.subjectId())) {
            throw new IllegalStateException("Deterministic re-evaluation subjectId mismatch");
        }
        if (!releaseDecision.getPolicyVersionId().equals(cmd.policyVersionId())) {
            throw new IllegalStateException("Deterministic re-evaluation policyVersionId mismatch");
        }
        if (!releaseDecision.getPolicyContentHash().equals(cmd.policyContentHash())) {
            throw new IllegalStateException("Deterministic re-evaluation policyContentHash mismatch");
        }
        if (!Objects.equals(releaseDecision.getRuleVersion(), cmd.ruleVersion())) {
            throw new IllegalStateException("Deterministic re-evaluation ruleVersion mismatch");
        }
        if (!releaseDecision.getInputFingerprint().equals(cmd.inputFingerprint())) {
            throw new IllegalStateException("Deterministic re-evaluation inputFingerprint mismatch: current="
                    + cmd.inputFingerprint() + " vs stored=" + releaseDecision.getInputFingerprint());
        }
        if (!cmd.reasonCodes().contains(ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW)) {
            throw new IllegalStateException("Deterministic re-evaluation missing expected reason code " + ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW);
        }
    }
}
