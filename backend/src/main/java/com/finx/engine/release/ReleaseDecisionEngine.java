package com.finx.engine.release;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.engine.adapter.*;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.evaluator.DecisionEvaluator;
import com.finx.engine.model.*;
import com.finx.engine.policy.release.ReleasePolicyDefinition;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.engine.service.EngineDecisionService;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.risk.dto.RiskDtos.UserType;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Deterministic, pure read-only evaluation engine for EngineType.RELEASE.
 * <p>
 * Evaluates the milestone release recommendation across all eight prerequisite
 * component engines under Platform Release Policy v1.0.0:
 * 1. RISK
 * 2. DOCUMENT
 * 3. MILESTONE_RULES
 * 4. APPROVAL
 * 5. TRUST
 * 6. FRAUD
 * 7. DISPUTE
 * 8. RECONCILIATION
 * <p>
 * STRICT SAFETY BOUNDARY:
 * - Pure recommendation engine producing EngineDecision(RELEASE).
 * - Zero bank calls, zero ledger/escrow writes, zero settlement dispatch.
 * - Zero ReleaseInstruction creation.
 * - Score is strictly NULL (no weighted or arithmetic score aggregation).
 * - Production payout remains DISABLED.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReleaseDecisionEngine implements DecisionEvaluator {

    public static final String REASON_RELEASE_ALL_GATES_ALLOW = "RELEASE_ALL_GATES_ALLOW";

    // Block reason codes (in EngineType order)
    public static final String REASON_BLOCKED_BY_RISK = "RELEASE_BLOCKED_BY_RISK";
    public static final String REASON_BLOCKED_BY_DOCUMENT = "RELEASE_BLOCKED_BY_DOCUMENT";
    public static final String REASON_BLOCKED_BY_MILESTONE_RULES = "RELEASE_BLOCKED_BY_MILESTONE_RULES";
    public static final String REASON_BLOCKED_BY_APPROVAL = "RELEASE_BLOCKED_BY_APPROVAL";
    public static final String REASON_BLOCKED_BY_TRUST = "RELEASE_BLOCKED_BY_TRUST";
    public static final String REASON_BLOCKED_BY_FRAUD = "RELEASE_BLOCKED_BY_FRAUD";
    public static final String REASON_BLOCKED_BY_DISPUTE = "RELEASE_BLOCKED_BY_DISPUTE";
    public static final String REASON_BLOCKED_BY_RECONCILIATION = "RELEASE_BLOCKED_BY_RECONCILIATION";

    // Review reason codes (in EngineType order)
    public static final String REASON_REVIEW_RISK = "RELEASE_REVIEW_RISK";
    public static final String REASON_REVIEW_DOCUMENT = "RELEASE_REVIEW_DOCUMENT";
    public static final String REASON_REVIEW_MILESTONE_RULES = "RELEASE_REVIEW_MILESTONE_RULES";
    public static final String REASON_REVIEW_APPROVAL = "RELEASE_REVIEW_APPROVAL";
    public static final String REASON_REVIEW_TRUST = "RELEASE_REVIEW_TRUST";
    public static final String REASON_REVIEW_FRAUD = "RELEASE_REVIEW_FRAUD";
    public static final String REASON_REVIEW_DISPUTE = "RELEASE_REVIEW_DISPUTE";
    public static final String REASON_REVIEW_RECONCILIATION = "RELEASE_REVIEW_RECONCILIATION";

    // General anomaly review codes
    public static final String REASON_SELLER_ORGANIZATION_MISSING = "RELEASE_SELLER_ORGANIZATION_MISSING";
    public static final String REASON_COMPONENT_MISSING = "RELEASE_COMPONENT_MISSING";
    public static final String REASON_COMPONENT_STALE = "RELEASE_COMPONENT_STALE";
    public static final String REASON_COMPONENT_ERROR = "RELEASE_COMPONENT_ERROR";
    public static final String REASON_COMPONENT_CONTEXT_MISMATCH = "RELEASE_COMPONENT_CONTEXT_MISMATCH";

    // Policy error codes
    public static final String ERROR_POLICY_UNAVAILABLE = "RELEASE_POLICY_UNAVAILABLE";
    public static final String ERROR_POLICY_INACTIVE = "RELEASE_POLICY_INACTIVE";
    public static final String ERROR_POLICY_INTEGRITY_FAILURE = "RELEASE_POLICY_INTEGRITY_FAILURE";

    private final EnginePolicyVersionRepository policyRepository;
    private final RiskDecisionAdapter riskAdapter;
    private final DocumentDecisionAdapter documentAdapter;
    private final MilestoneRulesDecisionAdapter milestoneRulesAdapter;
    private final ApprovalDecisionAdapter approvalAdapter;
    private final TrustDecisionAdapter trustAdapter;
    private final FraudDecisionAdapter fraudAdapter;
    private final DisputeDecisionAdapter disputeAdapter;
    private final ReconciliationDecisionAdapter reconciliationAdapter;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DealAgreementRepository agreementRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrganizationBankAccountRepository bankAccountRepository;
    private final VerificationEligibilityService eligibilityService;
    private final EngineDecisionService decisionService;
    private final DealAccessService dealAccessService;

    public ReleaseDecisionEngine(
            EnginePolicyVersionRepository policyRepository,
            RiskDecisionAdapter riskAdapter,
            DocumentDecisionAdapter documentAdapter,
            MilestoneRulesDecisionAdapter milestoneRulesAdapter,
            ApprovalDecisionAdapter approvalAdapter,
            TrustDecisionAdapter trustAdapter,
            FraudDecisionAdapter fraudAdapter,
            DisputeDecisionAdapter disputeAdapter,
            ReconciliationDecisionAdapter reconciliationAdapter,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            DealAgreementRepository agreementRepository,
            InvoiceRepository invoiceRepository,
            OrganizationBankAccountRepository bankAccountRepository,
            VerificationEligibilityService eligibilityService,
            EngineDecisionService decisionService,
            DealAccessService dealAccessService
    ) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
        this.riskAdapter = Objects.requireNonNull(riskAdapter, "riskAdapter must not be null");
        this.documentAdapter = Objects.requireNonNull(documentAdapter, "documentAdapter must not be null");
        this.milestoneRulesAdapter = Objects.requireNonNull(milestoneRulesAdapter, "milestoneRulesAdapter must not be null");
        this.approvalAdapter = Objects.requireNonNull(approvalAdapter, "approvalAdapter must not be null");
        this.trustAdapter = Objects.requireNonNull(trustAdapter, "trustAdapter must not be null");
        this.fraudAdapter = Objects.requireNonNull(fraudAdapter, "fraudAdapter must not be null");
        this.disputeAdapter = Objects.requireNonNull(disputeAdapter, "disputeAdapter must not be null");
        this.reconciliationAdapter = Objects.requireNonNull(reconciliationAdapter, "reconciliationAdapter must not be null");
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.agreementRepository = Objects.requireNonNull(agreementRepository, "agreementRepository must not be null");
        this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
        this.bankAccountRepository = Objects.requireNonNull(bankAccountRepository, "bankAccountRepository must not be null");
        this.eligibilityService = Objects.requireNonNull(eligibilityService, "eligibilityService must not be null");
        this.decisionService = Objects.requireNonNull(decisionService, "decisionService must not be null");
        this.dealAccessService = Objects.requireNonNull(dealAccessService, "dealAccessService must not be null");
    }

    @Override
    public EngineType type() {
        return EngineType.RELEASE;
    }

    /**
     * Standard SPI evaluation endpoint.
     */
    @Override
    public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!DecisionSubjectType.MILESTONE.name().equals(context.subjectType())) {
            throw new IllegalArgumentException("ReleaseDecisionEngine requires subjectType MILESTONE, got: " + context.subjectType());
        }

        Milestone milestone = milestoneRepository.findById(context.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", context.subjectId()));
        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        if (!context.organizationId().equals(deal.getBuyerOrganizationId())) {
            throw new UnauthorizedException("Release evaluation must be owned by buyer organization " + deal.getBuyerOrganizationId());
        }

        ReleaseEvaluationResult result = evaluateMilestone(
                deal.getId(),
                milestone.getId(),
                context.evaluationId(),
                context.correlationId(),
                context.evaluationTime(),
                null
        );

        return result.releaseCommand();
    }

    /**
     * Evaluates all 8 prerequisite domain decisions and the overall RELEASE recommendation
     * in memory without performing persistence.
     */
    public ReleaseEvaluationResult evaluateMilestone(
            UUID dealId,
            UUID milestoneId,
            UUID evaluationId,
            UUID correlationId,
            Instant evaluationTime,
            UserPrincipal caller
    ) {
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(milestoneId, "milestoneId must not be null");

        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        if (!deal.getId().equals(milestone.getDealId())) {
            throw new IllegalArgumentException("Milestone " + milestoneId + " does not belong to Deal " + dealId);
        }

        if (caller != null) {
            dealAccessService.requireDealAccess(deal, caller);
        }

        UUID evalId = evaluationId != null ? evaluationId : UUID.randomUUID();
        UUID corrId = correlationId != null ? correlationId : UUID.randomUUID();
        Instant now = evaluationTime != null ? evaluationTime : Instant.now();

        // 1. Resolve Platform Release Policy v1.0.0
        EnginePolicyVersion policy = policyRepository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        ).orElse(null);

        if (policy == null) {
            RecordEngineDecisionCommand errCmd = createPolicyErrorCommand(
                    deal.getBuyerOrganizationId(),
                    milestone.getId(),
                    evalId,
                    corrId,
                    now,
                    ERROR_POLICY_UNAVAILABLE,
                    "Platform release policy " + ReleasePolicyDefinition.POLICY_KEY + " " + ReleasePolicyDefinition.POLICY_VERSION + " is not registered.",
                    null,
                    null
            );
            return new ReleaseEvaluationResult(evalId, corrId, null, Collections.emptyList(), errCmd, Collections.emptyList());
        }

        if (policy.getActivatedAt() == null) {
            RecordEngineDecisionCommand errCmd = createPolicyErrorCommand(
                    deal.getBuyerOrganizationId(),
                    milestone.getId(),
                    evalId,
                    corrId,
                    now,
                    ERROR_POLICY_INACTIVE,
                    "Platform release policy " + ReleasePolicyDefinition.POLICY_KEY + " " + ReleasePolicyDefinition.POLICY_VERSION + " is inactive.",
                    policy.getId(),
                    policy.getContentHash()
            );
            return new ReleaseEvaluationResult(evalId, corrId, null, Collections.emptyList(), errCmd, Collections.emptyList());
        }

        boolean hashMatches = Objects.equals(policy.getContentHash(), ReleasePolicyDefinition.getExpectedContentHash());
        boolean contentMatches = Objects.equals(policy.getPolicyContent(), ReleasePolicyDefinition.getCanonicalPolicyContent());
        if (!hashMatches || !contentMatches) {
            RecordEngineDecisionCommand errCmd = createPolicyErrorCommand(
                    deal.getBuyerOrganizationId(),
                    milestone.getId(),
                    evalId,
                    corrId,
                    now,
                    ERROR_POLICY_INTEGRITY_FAILURE,
                    "Platform release policy " + ReleasePolicyDefinition.POLICY_KEY + " " + ReleasePolicyDefinition.POLICY_VERSION + " failed integrity verification.",
                    policy.getId(),
                    policy.getContentHash()
            );
            return new ReleaseEvaluationResult(evalId, corrId, null, Collections.emptyList(), errCmd, Collections.emptyList());
        }

        // 2. Fetch Supporting Contractual Entities
        DealAgreement agreement = agreementRepository.findTopByDealIdOrderByVersionDesc(deal.getId()).orElse(null);
        Invoice invoice = invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestone.getId()).orElse(null);
        
        // Canonical beneficiary selection via VerificationEligibilityService
        OrganizationBankAccount beneficiary = null;
        if (deal.getSellerOrganizationId() != null) {
            var beneficiaryResult = eligibilityService.evaluateBeneficiaryEligibility(deal.getSellerOrganizationId());
            if (beneficiaryResult.eligible()) {
                beneficiary = beneficiaryResult.bankAccount();
            }
        }

        // 3. Evaluate Exactly 8 Prerequisite Components
        Map<EngineType, RecordEngineDecisionCommand> componentMap = new EnumMap<>(EngineType.class);

        // Component 1: RISK (Buyer Risk)
        EvaluationContext riskContext = new EvaluationContext(
                evalId, deal.getBuyerOrganizationId(), corrId, DecisionSubjectType.ORGANIZATION.name(), deal.getBuyerOrganizationId(), now
        );
        componentMap.put(EngineType.RISK, riskAdapter.evaluateForDeal(deal, UserType.BUYER, riskContext));

        // Component 2: DOCUMENT (Invoice Document)
        if (invoice != null) {
            EvaluationContext docContext = new EvaluationContext(
                    evalId, deal.getBuyerOrganizationId(), corrId, DecisionSubjectType.INVOICE.name(), invoice.getId(), now
            );
            componentMap.put(EngineType.DOCUMENT, documentAdapter.evaluateInvoice(invoice, docContext));
        } else if (milestone.isInvoiceRequired()) {
            componentMap.put(EngineType.DOCUMENT, createMissingInvoiceDocumentCommand(deal.getBuyerOrganizationId(), milestone.getId(), evalId, corrId, now));
        } else {
            componentMap.put(EngineType.DOCUMENT, createInvoiceNotRequiredDocumentCommand(deal.getBuyerOrganizationId(), milestone.getId(), evalId, corrId, now));
        }

        // Component 3: MILESTONE_RULES
        EvaluationContext msRulesContext = new EvaluationContext(
                evalId, deal.getBuyerOrganizationId(), corrId, DecisionSubjectType.MILESTONE.name(), milestone.getId(), now
        );
        componentMap.put(EngineType.MILESTONE_RULES, milestoneRulesAdapter.evaluate(msRulesContext));

        // Component 4: APPROVAL
        EvaluationContext approvalContext = new EvaluationContext(
                evalId, deal.getBuyerOrganizationId(), corrId, DecisionSubjectType.MILESTONE.name(), milestone.getId(), now
        );
        componentMap.put(EngineType.APPROVAL, approvalAdapter.evaluate(approvalContext));

        // Component 5: TRUST (Counterparty / Seller Trust — Fail closed if seller organization is missing)
        if (deal.getSellerOrganizationId() == null) {
            componentMap.put(EngineType.TRUST, null); // Will trigger REASON_SELLER_ORGANIZATION_MISSING
        } else {
            UUID sellerOrgId = deal.getSellerOrganizationId();
            EvaluationContext trustContext = new EvaluationContext(
                    evalId, sellerOrgId, corrId, DecisionSubjectType.ORGANIZATION.name(), sellerOrgId, now
            );
            componentMap.put(EngineType.TRUST, trustAdapter.evaluateForOrganization(sellerOrgId, trustContext));
        }

        // Component 6: FRAUD
        EvaluationContext fraudContext = new EvaluationContext(
                evalId, deal.getBuyerOrganizationId(), corrId, DecisionSubjectType.MILESTONE.name(), milestone.getId(), now
        );
        componentMap.put(EngineType.FRAUD, fraudAdapter.evaluate(fraudContext));

        // Component 7: DISPUTE
        EvaluationContext disputeContext = new EvaluationContext(
                evalId, deal.getBuyerOrganizationId(), corrId, DecisionSubjectType.MILESTONE.name(), milestone.getId(), now
        );
        componentMap.put(EngineType.DISPUTE, disputeAdapter.evaluateForMilestone(deal.getId(), milestone.getId(), disputeContext));

        // Component 8: RECONCILIATION
        EvaluationContext reconContext = new EvaluationContext(
                evalId, deal.getBuyerOrganizationId(), corrId, DecisionSubjectType.MILESTONE.name(), milestone.getId(), now
        );
        componentMap.put(EngineType.RECONCILIATION, reconciliationAdapter.evaluate(reconContext));

        // 4. Precedence & Gating Evaluation
        List<String> blockReasons = new ArrayList<>();
        List<String> reviewReasons = new ArrayList<>();

        List<EngineType> requiredOrder = List.of(
                EngineType.RISK,
                EngineType.DOCUMENT,
                EngineType.MILESTONE_RULES,
                EngineType.APPROVAL,
                EngineType.TRUST,
                EngineType.FRAUD,
                EngineType.DISPUTE,
                EngineType.RECONCILIATION
        );

        List<RecordEngineDecisionCommand> orderedComponentCommands = new ArrayList<>();

        for (EngineType reqType : requiredOrder) {
            RecordEngineDecisionCommand cmd = componentMap.get(reqType);
            if (cmd == null) {
                if (reqType == EngineType.TRUST && deal.getSellerOrganizationId() == null) {
                    reviewReasons.add(REASON_SELLER_ORGANIZATION_MISSING);
                } else {
                    reviewReasons.add(REASON_COMPONENT_MISSING);
                }
                continue;
            }

            // Explicit context and subject binding validation
            boolean bindingValid = validateComponentBinding(cmd, reqType, deal, milestone, invoice, evalId, corrId, now);
            if (!bindingValid) {
                reviewReasons.add(REASON_COMPONENT_CONTEXT_MISMATCH);
            }

            orderedComponentCommands.add(cmd);

            if (cmd.evaluationOutcome() == EvaluationOutcome.ERROR) {
                reviewReasons.add(REASON_COMPONENT_ERROR);
            }

            if (cmd.decision() == DecisionStatus.BLOCK) {
                blockReasons.add(mapBlockReason(reqType));
            } else if (cmd.decision() == DecisionStatus.REVIEW) {
                reviewReasons.add(mapReviewReason(reqType));
            }
        }

        DecisionStatus releaseDecision;
        List<String> finalReasons = new ArrayList<>();
        String explanation;

        if (!blockReasons.isEmpty()) {
            releaseDecision = DecisionStatus.BLOCK;
            finalReasons.addAll(blockReasons);
            explanation = "Milestone release blocked by prerequisite decision engines: " + String.join(", ", blockReasons);
        } else if (!reviewReasons.isEmpty()) {
            releaseDecision = DecisionStatus.REVIEW;
            finalReasons.addAll(reviewReasons);
            explanation = "Milestone release requires human or operational review: " + String.join(", ", reviewReasons);
        } else {
            releaseDecision = DecisionStatus.ALLOW;
            finalReasons.add(REASON_RELEASE_ALL_GATES_ALLOW);
            explanation = "All eight prerequisite decision engines evaluated to ALLOW on current evidence. "
                    + "Release recommendation approved (NOTE: Recommendation only; NOT financial payment authorization).";
        }

        // 5. Build Snapshot Evidence References
        List<EvidenceReference> evidenceRefs = new ArrayList<>();
        evidenceRefs.add(new EvidenceReference("RELEASE_POLICY_VERSION", policy.getId(), policy.getActivatedAt(), policy.getContentHash(), "ENGINE_POLICY_VERSION", "1.0.0"));
        evidenceRefs.add(new EvidenceReference("DEAL", deal.getId(), deal.getUpdatedAt(), null, "DEAL_REPOSITORY", null));
        evidenceRefs.add(new EvidenceReference("MILESTONE", milestone.getId(), milestone.getUpdatedAt(), null, "MILESTONE_REPOSITORY", null));

        if (agreement != null) {
            evidenceRefs.add(new EvidenceReference("AGREEMENT", agreement.getId(), agreement.getUpdatedAt(), agreement.getDocumentSha256(), "AGREEMENT_REPOSITORY", String.valueOf(agreement.getVersion())));
        }
        if (invoice != null) {
            String provenance = invoice.getExtractionStatus() != null ? invoice.getExtractionStatus().name() : "UNKNOWN";
            evidenceRefs.add(new EvidenceReference("INVOICE", invoice.getId(), invoice.getUpdatedAt(), invoice.getDocumentHash(), provenance, invoice.getRevisionOf() != null ? invoice.getRevisionOf().toString() : null));
        }
        if (beneficiary != null) {
            evidenceRefs.add(new EvidenceReference("ORGANIZATION_BANK_ACCOUNT", beneficiary.getId(), beneficiary.getVerifiedAt() != null ? beneficiary.getVerifiedAt() : beneficiary.getUpdatedAt(), null, "ORGANIZATION_BANK_ACCOUNT_REPOSITORY", null));
        }

        // 6. Compute Deterministic Release Snapshot Fingerprint
        String snapshotFingerprint = computeSnapshotFingerprint(
                policy,
                deal,
                milestone,
                agreement,
                invoice,
                beneficiary,
                orderedComponentCommands
        );

        RecordEngineDecisionCommand releaseCommand = RecordEngineDecisionCommand.builder()
                .organizationId(deal.getBuyerOrganizationId())
                .engineType(EngineType.RELEASE)
                .subjectType(DecisionSubjectType.MILESTONE)
                .subjectId(milestone.getId())
                .decision(releaseDecision)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(null) // Strictly null
                .reasonCodes(finalReasons)
                .explanation(explanation)
                .evidenceReferences(evidenceRefs)
                .ruleVersion(ReleasePolicyDefinition.POLICY_KEY + ":" + ReleasePolicyDefinition.POLICY_VERSION)
                .policyVersionId(policy.getId())
                .policyContentHash(ReleasePolicyDefinition.getExpectedContentHash())
                .sourceAssessmentId(null)
                .evaluationId(evalId)
                .correlationId(corrId)
                .inputFingerprint(snapshotFingerprint)
                .inputRevision(ReleasePolicyDefinition.POLICY_VERSION)
                .schemaVersion(ReleasePolicyDefinition.SCHEMA_VERSION)
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(now)
                .build();

        return new ReleaseEvaluationResult(
                evalId,
                corrId,
                null,
                Collections.emptyList(),
                releaseCommand,
                orderedComponentCommands
        );
    }

    /**
     * Evaluates and records all 8 prerequisite domain decisions and the overall RELEASE decision
     * into the engine_decisions repository with idempotent replay protection.
     */
    @Transactional
    public ReleaseEvaluationResult evaluateAndRecordMilestone(
            UUID dealId,
            UUID milestoneId,
            UUID evaluationId,
            UUID correlationId,
            Instant evaluationTime,
            UserPrincipal caller
    ) {
        ReleaseEvaluationResult evaluated = evaluateMilestone(
            dealId,
            milestoneId,
            evaluationId,
            correlationId,
            evaluationTime,
            caller
        );

        List<EngineDecision> recordedComponents = new ArrayList<>();
        for (RecordEngineDecisionCommand compCmd : evaluated.componentCommands()) {
            recordedComponents.add(decisionService.recordDecision(compCmd));
        }

        // Augment release command evidence references with recorded component decision references
        List<EvidenceReference> augmentedRefs = new ArrayList<>(evaluated.releaseCommand().evidenceReferences());
        for (EngineDecision compDec : recordedComponents) {
            augmentedRefs.add(new EvidenceReference(
                    "ENGINE_DECISION",
                    compDec.getId(),
                    compDec.getEvaluatedAt(),
                    compDec.getInputFingerprint(),
                    compDec.getEngineType().name(),
                    compDec.getDecision().name()
            ));
        }

        RecordEngineDecisionCommand finalReleaseCmd = RecordEngineDecisionCommand.builder()
                .organizationId(evaluated.releaseCommand().organizationId())
                .engineType(EngineType.RELEASE)
                .subjectType(evaluated.releaseCommand().subjectType())
                .subjectId(evaluated.releaseCommand().subjectId())
                .decision(evaluated.releaseCommand().decision())
                .evaluationOutcome(evaluated.releaseCommand().evaluationOutcome())
                .errorCode(evaluated.releaseCommand().errorCode())
                .score(null)
                .reasonCodes(evaluated.releaseCommand().reasonCodes())
                .explanation(evaluated.releaseCommand().explanation())
                .evidenceReferences(augmentedRefs)
                .ruleVersion(evaluated.releaseCommand().ruleVersion())
                .policyVersionId(evaluated.releaseCommand().policyVersionId())
                .policyContentHash(evaluated.releaseCommand().policyContentHash())
                .sourceAssessmentId(evaluated.releaseCommand().sourceAssessmentId())
                .evaluationId(evaluated.releaseCommand().evaluationId())
                .correlationId(evaluated.releaseCommand().correlationId())
                .inputFingerprint(evaluated.releaseCommand().inputFingerprint())
                .inputRevision(evaluated.releaseCommand().inputRevision())
                .schemaVersion(evaluated.releaseCommand().schemaVersion())
                .engineImplementationVersion(evaluated.releaseCommand().engineImplementationVersion())
                .evaluatedAt(evaluated.releaseCommand().evaluatedAt())
                .build();

        EngineDecision recordedRelease = decisionService.recordDecision(finalReleaseCmd);

        return new ReleaseEvaluationResult(
                evaluated.evaluationId(),
                evaluated.correlationId(),
                recordedRelease,
                recordedComponents,
                finalReleaseCmd,
                evaluated.componentCommands()
        );
    }

    private static boolean validateComponentBinding(
            RecordEngineDecisionCommand cmd,
            EngineType expectedType,
            Deal deal,
            Milestone milestone,
            Invoice invoice,
            UUID evalId,
            UUID corrId,
            Instant now
    ) {
        if (cmd == null) return false;
        if (cmd.engineType() != expectedType) return false;
        if (!Objects.equals(cmd.evaluationId(), evalId)) return false;
        if (!Objects.equals(cmd.correlationId(), corrId)) return false;
        if (!Objects.equals(cmd.evaluatedAt(), now)) return false;

        String subjectTypeStr = cmd.subjectType();
        UUID subjectId = cmd.subjectId();
        UUID orgId = cmd.organizationId();

        return switch (expectedType) {
            case RISK -> ("ORGANIZATION".equals(subjectTypeStr) || DecisionSubjectType.ORGANIZATION.name().equals(subjectTypeStr))
                    && Objects.equals(subjectId, deal.getBuyerOrganizationId())
                    && Objects.equals(orgId, deal.getBuyerOrganizationId());
            case DOCUMENT -> {
                if (invoice != null) {
                    yield ("INVOICE".equals(subjectTypeStr) || DecisionSubjectType.INVOICE.name().equals(subjectTypeStr))
                            && Objects.equals(subjectId, invoice.getId())
                            && Objects.equals(orgId, deal.getBuyerOrganizationId());
                } else {
                    yield ("MILESTONE".equals(subjectTypeStr) || DecisionSubjectType.MILESTONE.name().equals(subjectTypeStr))
                            && Objects.equals(subjectId, milestone.getId())
                            && Objects.equals(orgId, deal.getBuyerOrganizationId());
                }
            }
            case MILESTONE_RULES -> ("MILESTONE".equals(subjectTypeStr) || DecisionSubjectType.MILESTONE.name().equals(subjectTypeStr))
                    && Objects.equals(subjectId, milestone.getId())
                    && Objects.equals(orgId, deal.getBuyerOrganizationId());
            case APPROVAL -> ("MILESTONE".equals(subjectTypeStr) || DecisionSubjectType.MILESTONE.name().equals(subjectTypeStr))
                    && Objects.equals(subjectId, milestone.getId())
                    && Objects.equals(orgId, deal.getBuyerOrganizationId());
            case TRUST -> ("ORGANIZATION".equals(subjectTypeStr) || DecisionSubjectType.ORGANIZATION.name().equals(subjectTypeStr))
                    && Objects.equals(subjectId, deal.getSellerOrganizationId())
                    && Objects.equals(orgId, deal.getSellerOrganizationId());
            case FRAUD -> ("MILESTONE".equals(subjectTypeStr) || DecisionSubjectType.MILESTONE.name().equals(subjectTypeStr))
                    && Objects.equals(subjectId, milestone.getId())
                    && Objects.equals(orgId, deal.getBuyerOrganizationId());
            case DISPUTE -> ("MILESTONE".equals(subjectTypeStr) || DecisionSubjectType.MILESTONE.name().equals(subjectTypeStr))
                    && Objects.equals(subjectId, milestone.getId())
                    && Objects.equals(orgId, deal.getBuyerOrganizationId());
            case RECONCILIATION -> ("MILESTONE".equals(subjectTypeStr) || DecisionSubjectType.MILESTONE.name().equals(subjectTypeStr))
                    && Objects.equals(subjectId, milestone.getId())
                    && Objects.equals(orgId, deal.getBuyerOrganizationId());
            default -> false;
        };
    }

    private static String mapBlockReason(EngineType type) {
        return switch (type) {
            case RISK -> REASON_BLOCKED_BY_RISK;
            case DOCUMENT -> REASON_BLOCKED_BY_DOCUMENT;
            case MILESTONE_RULES -> REASON_BLOCKED_BY_MILESTONE_RULES;
            case APPROVAL -> REASON_BLOCKED_BY_APPROVAL;
            case TRUST -> REASON_BLOCKED_BY_TRUST;
            case FRAUD -> REASON_BLOCKED_BY_FRAUD;
            case DISPUTE -> REASON_BLOCKED_BY_DISPUTE;
            case RECONCILIATION -> REASON_BLOCKED_BY_RECONCILIATION;
            default -> "RELEASE_BLOCKED_BY_" + type.name();
        };
    }

    private static String mapReviewReason(EngineType type) {
        return switch (type) {
            case RISK -> REASON_REVIEW_RISK;
            case DOCUMENT -> REASON_REVIEW_DOCUMENT;
            case MILESTONE_RULES -> REASON_REVIEW_MILESTONE_RULES;
            case APPROVAL -> REASON_REVIEW_APPROVAL;
            case TRUST -> REASON_REVIEW_TRUST;
            case FRAUD -> REASON_REVIEW_FRAUD;
            case DISPUTE -> REASON_REVIEW_DISPUTE;
            case RECONCILIATION -> REASON_REVIEW_RECONCILIATION;
            default -> "RELEASE_REVIEW_" + type.name();
        };
    }

    private static String computeSnapshotFingerprint(
            EnginePolicyVersion policy,
            Deal deal,
            Milestone milestone,
            DealAgreement agreement,
            Invoice invoice,
            OrganizationBankAccount beneficiary,
            List<RecordEngineDecisionCommand> components
    ) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();

            // Policy
            sb.append("POLICY:").append(policy.getId()).append("|").append(policy.getContentHash()).append("\n");

            // Deal
            sb.append("DEAL:").append(deal.getId()).append("|")
                    .append(deal.getBuyerOrganizationId()).append("|")
                    .append(deal.getSellerOrganizationId()).append("|")
                    .append(deal.getTotalAmount()).append("|")
                    .append(deal.getCurrency()).append("|")
                    .append(deal.getStatus()).append("|")
                    .append(deal.getUpdatedAt()).append("\n");

            // Milestone
            sb.append("MILESTONE:").append(milestone.getId()).append("|")
                    .append(milestone.getSequence()).append("|")
                    .append(milestone.getAmount()).append("|")
                    .append(milestone.getCurrency()).append("|")
                    .append(milestone.getStatus()).append("|")
                    .append(milestone.isInvoiceRequired()).append("|")
                    .append(milestone.getSubmissionDeadline()).append("|")
                    .append(milestone.getDueDate()).append("|")
                    .append(milestone.getUpdatedAt()).append("\n");

            // Agreement
            if (agreement != null) {
                sb.append("AGREEMENT:").append(agreement.getId()).append("|")
                        .append(agreement.getVersion()).append("|")
                        .append(agreement.getStatus()).append("|")
                        .append(agreement.getDocumentSha256()).append("|")
                        .append(agreement.getUpdatedAt()).append("\n");
            } else {
                sb.append("AGREEMENT:NONE\n");
            }

            // Invoice
            if (invoice != null) {
                sb.append("INVOICE:").append(invoice.getId()).append("|")
                        .append(invoice.getRevisionOf()).append("|")
                        .append(invoice.getStatus()).append("|")
                        .append(invoice.getDocumentHash()).append("|")
                        .append(invoice.getExtractionStatus()).append("|")
                        .append(invoice.getUpdatedAt()).append("\n");
            } else {
                sb.append("INVOICE:NONE\n");
            }

            // Beneficiary (safe fields only — no account numbers, masked accounts, or IFSC)
            if (beneficiary != null) {
                sb.append("BENEFICIARY:").append(beneficiary.getId()).append("|")
                        .append(beneficiary.getVerificationStatus()).append("|")
                        .append(beneficiary.getNameMatchStatus()).append("|")
                        .append(beneficiary.getVerifiedAt()).append("|")
                        .append(beneficiary.getUpdatedAt()).append("\n");
            } else {
                sb.append("BENEFICIARY:NONE\n");
            }

            // Components in sorted EngineType order
            for (RecordEngineDecisionCommand cmd : components) {
                sb.append("COMP:").append(cmd.engineType().name()).append("|")
                        .append(cmd.decision().name()).append("|")
                        .append(cmd.evaluationOutcome().name()).append("|")
                        .append(cmd.inputFingerprint()).append("|")
                        .append(cmd.ruleVersion()).append("|")
                        .append(cmd.policyContentHash()).append("|")
                        .append(cmd.evaluatedAt()).append("|")
                        .append(String.join(",", cmd.reasonCodes())).append("\n");
            }

            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private static RecordEngineDecisionCommand createPolicyErrorCommand(
            UUID orgId,
            UUID subjectId,
            UUID evalId,
            UUID corrId,
            Instant now,
            String errorCode,
            String explanation,
            UUID policyVersionId,
            String policyContentHash
    ) {
        String inputFingerprint = computeErrorFingerprint(orgId, subjectId, errorCode, ReleasePolicyDefinition.POLICY_VERSION);
        return RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RELEASE)
                .subjectType(DecisionSubjectType.MILESTONE)
                .subjectId(subjectId)
                .decision(DecisionStatus.REVIEW)
                .evaluationOutcome(EvaluationOutcome.ERROR)
                .errorCode(errorCode)
                .score(null)
                .reasonCodes(List.of(errorCode))
                .explanation(explanation)
                .evidenceReferences(List.of())
                .ruleVersion(ReleasePolicyDefinition.POLICY_KEY + ":" + ReleasePolicyDefinition.POLICY_VERSION)
                .policyVersionId(policyVersionId)
                .policyContentHash(policyContentHash)
                .sourceAssessmentId(null)
                .evaluationId(evalId)
                .correlationId(corrId)
                .inputFingerprint(inputFingerprint)
                .inputRevision(ReleasePolicyDefinition.POLICY_VERSION)
                .schemaVersion(ReleasePolicyDefinition.SCHEMA_VERSION)
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(now)
                .build();
    }

    private static RecordEngineDecisionCommand createMissingInvoiceDocumentCommand(
            UUID orgId,
            UUID milestoneId,
            UUID evalId,
            UUID corrId,
            Instant now
    ) {
        return RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.DOCUMENT)
                .subjectType(DecisionSubjectType.MILESTONE.name())
                .subjectId(milestoneId)
                .decision(DecisionStatus.REVIEW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(null)
                .reasonCodes(List.of(DocumentDecisionAdapter.REASON_DOCUMENT_ASSESSMENT_MISSING))
                .explanation("Required invoice evidence missing for milestone " + milestoneId)
                .evidenceReferences(List.of())
                .ruleVersion(null)
                .sourceAssessmentId(null)
                .evaluationId(evalId)
                .correlationId(corrId)
                .inputFingerprint(computeErrorFingerprint(orgId, milestoneId, "INVOICE_MISSING", "1.0.0"))
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(now)
                .build();
    }

    private static RecordEngineDecisionCommand createInvoiceNotRequiredDocumentCommand(
            UUID orgId,
            UUID milestoneId,
            UUID evalId,
            UUID corrId,
            Instant now
    ) {
        return RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.DOCUMENT)
                .subjectType(DecisionSubjectType.MILESTONE.name())
                .subjectId(milestoneId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(null)
                .reasonCodes(List.of("DOCUMENT_NOT_REQUIRED"))
                .explanation("Invoice evidence is not required for milestone " + milestoneId)
                .evidenceReferences(List.of())
                .ruleVersion(null)
                .sourceAssessmentId(null)
                .evaluationId(evalId)
                .correlationId(corrId)
                .inputFingerprint(computeErrorFingerprint(orgId, milestoneId, "INVOICE_NOT_REQUIRED", "1.0.0"))
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(now)
                .build();
    }

    private static String computeErrorFingerprint(UUID orgId, UUID subjectId, String errorCode, String version) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = "RELEASE_ERROR|" + orgId + "|" + subjectId + "|" + errorCode + "|" + version;
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
