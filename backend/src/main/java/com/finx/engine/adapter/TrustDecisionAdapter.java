package com.finx.engine.adapter;

import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.verification.entity.AuthorizedRepresentative;
import com.finx.verification.entity.KybProfile;
import com.finx.verification.entity.KycProfile;
import com.finx.verification.enums.AuthorityStatus;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.AuthorizedRepresentativeRepository;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import com.finx.verification.service.VerificationEligibilityService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Deterministic, read-only decision adapter for counterparty TRUST evaluation.
 * <p>
 * Evaluates canonical KYB, signing authority, individual KYC, active membership,
 * and B09 beneficiary eligibility.
 * <p>
 * INVARIANTS:
 * - Read-only inspection of persisted domain entities (zero mutations)
 * - Organization-scoped subject (subjectType: ORGANIZATION, subjectId: organizationId)
 * - Zero numeric trust scoring (no 0-100 score, score is null)
 * - Broken D06 metrics (OrganizationService.getVerifiedBusinessProfile) are EXCLUDED
 * - New companies with zero transaction history are NOT blocked
 * - Strictly zero banking, ledger, escrow, settlement, or payment side effects
 */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TrustDecisionAdapter implements ReadOnlyDecisionAdapter {

    private final OrganizationRepository orgRepository;
    private final KybProfileRepository kybRepository;
    private final KycProfileRepository kycRepository;
    private final AuthorizedRepresentativeRepository repRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationBankAccountRepository bankRepository;
    private final VerificationEligibilityService eligibilityService;

    public TrustDecisionAdapter(
            OrganizationRepository orgRepository,
            KybProfileRepository kybRepository,
            KycProfileRepository kycRepository,
            AuthorizedRepresentativeRepository repRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationBankAccountRepository bankRepository,
            VerificationEligibilityService eligibilityService
    ) {
        this.orgRepository = Objects.requireNonNull(orgRepository, "orgRepository must not be null");
        this.kybRepository = Objects.requireNonNull(kybRepository, "kybRepository must not be null");
        this.kycRepository = Objects.requireNonNull(kycRepository, "kycRepository must not be null");
        this.repRepository = Objects.requireNonNull(repRepository, "repRepository must not be null");
        this.memberRepository = Objects.requireNonNull(memberRepository, "memberRepository must not be null");
        this.bankRepository = Objects.requireNonNull(bankRepository, "bankRepository must not be null");
        this.eligibilityService = Objects.requireNonNull(eligibilityService, "eligibilityService must not be null");
    }

    @Override
    public EngineType type() {
        return EngineType.TRUST;
    }

    @Override
    public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        return evaluateForOrganization(context.organizationId(), context);
    }

    public RecordEngineDecisionCommand evaluateForOrganization(UUID organizationId, EvaluationContext context) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        DecisionEvidence evidence = new DecisionEvidence();
        List<String> blocks = new ArrayList<>();
        Set<String> reviews = new LinkedHashSet<>();

        // 1. Organization Identity
        Organization org = orgRepository.findById(organizationId).orElse(null);
        if (org == null) {
            blocks.add("TRUST_ORGANIZATION_NOT_FOUND");
            evidence.input("ORGANIZATION_NOT_FOUND", organizationId);
            return evidence.command(context, type(), DecisionStatus.BLOCK, blocks,
                    "Organization record does not exist for ID: " + organizationId);
        }

        evidence.reference("ORGANIZATION", org.getId(), org.getUpdatedAt(), null, "ORGANIZATION_REPOSITORY", null);
        evidence.input("ORGANIZATION", org.getId(), org.getLegalName(), org.getEntityType(),
                org.getVerificationStatus(), org.getUpdatedAt());

        EntityType entityType = org.getEntityType();

        // 2. Canonical KYB Evaluation
        KybProfile kyb = kybRepository.findByOrganizationId(organizationId).orElse(null);
        boolean kybPanOk = false;
        boolean kybGstOk = false;
        boolean kybRegOk = false;

        if (kyb != null) {
            evidence.reference("KYB_PROFILE", kyb.getId(),
                    kyb.getVerifiedAt() != null ? kyb.getVerifiedAt() : kyb.getUpdatedAt(),
                    null, "KYB_PROFILE_REPOSITORY", null);
            evidence.input("KYB_PROFILE", kyb.getId(), kyb.getStatus(), kyb.getPanStatus(),
                    kyb.getGstStatus(), kyb.getRegistrationStatus(), kyb.getNameMatchStatus(),
                    kyb.getVerifiedAt(), kyb.getExpiresAt());

            // Check KYB hard failures
            if (kyb.getStatus() == VerificationStatus.FAILED || kyb.getStatus() == VerificationStatus.REJECTED
                    || kyb.getPanStatus() == VerificationStatus.FAILED || kyb.getPanStatus() == VerificationStatus.REJECTED
                    || kyb.getGstStatus() == VerificationStatus.FAILED || kyb.getGstStatus() == VerificationStatus.REJECTED
                    || kyb.getRegistrationStatus() == VerificationStatus.FAILED || kyb.getRegistrationStatus() == VerificationStatus.REJECTED) {
                blocks.add("TRUST_KYB_FAILED");
            }

            // Expiry / Freshness check (null expiry means no configured expiry)
            if (kyb.getExpiresAt() != null && !kyb.getExpiresAt().isAfter(context.evaluationTime())) {
                reviews.add("TRUST_VERIFICATION_EXPIRED");
            }

            // PAN check
            if (kyb.getPanStatus() == VerificationStatus.VERIFIED) {
                kybPanOk = true;
            }

            // GSTIN check (required for Ltd/Pvt Ltd/LLP, optional for Proprietorship)
            boolean gstRequired = entityType != EntityType.PROPRIETORSHIP;
            if (kyb.getGstStatus() == VerificationStatus.VERIFIED) {
                kybGstOk = true;
            } else if (!gstRequired && (kyb.getGstin() == null || kyb.getGstin().isBlank())) {
                kybGstOk = true;
            }

            // Registration check (CIN for PVT_LTD/PUBLIC_LTD, LLPIN for LLP, Udyam for PROPRIETORSHIP)
            boolean regRequired = entityType == EntityType.PRIVATE_LIMITED
                    || entityType == EntityType.PUBLIC_LIMITED
                    || entityType == EntityType.LLP;
            if (kyb.getRegistrationStatus() == VerificationStatus.VERIFIED) {
                kybRegOk = true;
            } else if (!regRequired && (kyb.getUdyamNumber() == null || kyb.getUdyamNumber().isBlank())) {
                kybRegOk = true;
            }

            if (!kybPanOk || !kybGstOk || !kybRegOk || kyb.getStatus() != VerificationStatus.VERIFIED) {
                if (blocks.isEmpty()) {
                    reviews.add("TRUST_KYB_PENDING");
                }
            }
        } else {
            reviews.add("TRUST_KYB_PENDING");
            evidence.input("KYB_MISSING", organizationId);
        }

        // 3. Authorized Representative, Active Membership, and KYC
        List<AuthorizedRepresentative> reps = repRepository.findByOrganizationId(organizationId).stream()
                .sorted(Comparator.comparing(AuthorizedRepresentative::getId))
                .toList();

        boolean hasVerifiedSignatory = false;
        boolean hasInactiveMember = false;

        for (AuthorizedRepresentative rep : reps) {
            evidence.reference("AUTHORIZED_REPRESENTATIVE", rep.getId(),
                    rep.getVerifiedAt() != null ? rep.getVerifiedAt() : rep.getUpdatedAt(),
                    null, "AUTHORIZED_REPRESENTATIVE_REPOSITORY", null);
            evidence.input("AUTHORIZED_REPRESENTATIVE", rep.getId(), rep.getUserId(),
                    rep.getRelationshipType(), rep.getAuthorityStatus(), rep.getVerifiedAt());

            if (rep.getAuthorityStatus() == AuthorityStatus.VERIFIED) {
                // Check membership
                Optional<OrganizationMember> memberOpt = memberRepository.findByOrganizationIdAndUserId(organizationId, rep.getUserId());
                boolean memberActive = false;
                if (memberOpt.isPresent()) {
                    OrganizationMember member = memberOpt.get();
                    evidence.reference("ORGANIZATION_MEMBER", member.getId(), member.getJoinedAt(),
                            null, "ORGANIZATION_MEMBER_REPOSITORY", null);
                    evidence.input("ORGANIZATION_MEMBER", member.getId(), member.getUserId(),
                            member.getRole(), member.getStatus(), member.getJoinedAt());

                    if (member.getStatus() == MemberStatus.ACTIVE) {
                        if (member.getRole() == OrgRole.OWNER
                                || member.getRole() == OrgRole.AUTHORIZED_SIGNATORY
                                || member.getRole() == OrgRole.ADMIN) {
                            memberActive = true;
                        }
                    } else {
                        hasInactiveMember = true;
                    }
                } else {
                    hasInactiveMember = true;
                }

                // Check KYC
                Optional<KycProfile> kycOpt = kycRepository.findByUserId(rep.getUserId());
                boolean kycVerified = false;
                if (kycOpt.isPresent()) {
                    KycProfile kyc = kycOpt.get();
                    evidence.reference("KYC_PROFILE", kyc.getId(),
                            kyc.getVerifiedAt() != null ? kyc.getVerifiedAt() : kyc.getUpdatedAt(),
                            null, "KYC_PROFILE_REPOSITORY", null);
                    evidence.input("KYC_PROFILE", kyc.getId(), kyc.getUserId(), kyc.getStatus(),
                            kyc.getPanStatus(), kyc.getIdentityStatus(), kyc.getNameMatchStatus(),
                            kyc.getVerifiedAt(), kyc.getExpiresAt());

                    if (kyc.getExpiresAt() != null && !kyc.getExpiresAt().isAfter(context.evaluationTime())) {
                        reviews.add("TRUST_VERIFICATION_EXPIRED");
                    }

                    if (kyc.getStatus() == VerificationStatus.VERIFIED) {
                        kycVerified = true;
                    }
                }

                if (memberActive && kycVerified) {
                    hasVerifiedSignatory = true;
                }
            }
        }

        if (hasInactiveMember && !hasVerifiedSignatory) {
            reviews.add("TRUST_MEMBERSHIP_INACTIVE");
        }

        if (!hasVerifiedSignatory) {
            reviews.add("TRUST_SIGNATORY_UNVERIFIED");
        }

        // 4. Beneficiary Verification (B09 Semantics)
        List<OrganizationBankAccount> accounts = bankRepository.findByOrganizationId(organizationId).stream()
                .sorted(Comparator.comparing(OrganizationBankAccount::getId))
                .toList();

        boolean hasNameMismatch = false;
        for (OrganizationBankAccount acc : accounts) {
            evidence.reference("ORGANIZATION_BANK_ACCOUNT", acc.getId(),
                    acc.getVerifiedAt() != null ? acc.getVerifiedAt() : acc.getUpdatedAt(),
                    null, "ORGANIZATION_BANK_ACCOUNT_REPOSITORY", null);
            evidence.input("BANK_ACCOUNT", acc.getId(), acc.getOrganizationId(),
                    acc.getVerificationStatus(), acc.getVerifiedAt(), acc.getNameMatchStatus(),
                    acc.getEncryptedAccountNumber() != null && !acc.getEncryptedAccountNumber().isBlank());

            if ("MISMATCH".equalsIgnoreCase(acc.getNameMatchStatus())) {
                hasNameMismatch = true;
            }
        }

        if (hasNameMismatch) {
            blocks.add("TRUST_BENEFICIARY_MISMATCH");
        }

        VerificationEligibilityService.BeneficiaryEligibilityResult beneficiaryResult =
                eligibilityService.evaluateBeneficiaryEligibility(organizationId);

        if (!beneficiaryResult.eligible()) {
            if (beneficiaryResult.failureReason() != null && beneficiaryResult.failureReason().startsWith("Ambiguous beneficiary")) {
                reviews.add("TRUST_BENEFICIARY_AMBIGUOUS");
            } else if (!hasNameMismatch) {
                reviews.add("TRUST_BENEFICIARY_UNVERIFIED");
            }
        }

        // 5. Informational Transaction History (New companies with no history are NOT blocked)
        evidence.input("TRANSACTION_HISTORY_NEUTRAL", organizationId, "INSUFFICIENT_TRANSACTION_HISTORY");

        // 6. Build Command
        List<String> reasons = new ArrayList<>(blocks);
        reasons.addAll(reviews);

        DecisionStatus status;
        String explanation;

        if (!blocks.isEmpty()) {
            status = DecisionStatus.BLOCK;
            explanation = "Organization failed required business verification or has active beneficiary mismatch.";
        } else if (!reviews.isEmpty()) {
            status = DecisionStatus.REVIEW;
            explanation = "Organization counterparty verification is pending, expired, incomplete, or requires review.";
        } else {
            status = DecisionStatus.ALLOW;
            reasons = List.of("TRUST_COUNTERPARTY_VERIFIED");
            explanation = "Organization identity, KYB, signing authority, individual KYC, active membership, and beneficiary are verified.";
        }

        evidence.input("FINAL_STATUS", status.name(), String.join("|", reasons));

        return evidence.command(context, type(), status, reasons, explanation);
    }
}
