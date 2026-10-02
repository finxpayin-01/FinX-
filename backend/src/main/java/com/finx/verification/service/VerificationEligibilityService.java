package com.finx.verification.service;

import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.verification.dto.VerificationDtos.*;
import com.finx.verification.entity.AuthorizedRepresentative;
import com.finx.verification.entity.KybProfile;
import com.finx.verification.entity.KycProfile;
import com.finx.verification.enums.AuthorityStatus;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.provider.VerificationProvider;
import com.finx.verification.repository.AuthorizedRepresentativeRepository;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class VerificationEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(VerificationEligibilityService.class);

    private final OrganizationRepository orgRepository;
    private final KybProfileRepository kybRepository;
    private final KycProfileRepository kycRepository;
    private final AuthorizedRepresentativeRepository repRepository;
    private final OrganizationBankAccountRepository bankRepository;
    private final VerificationProvider verificationProvider;

    public VerificationEligibilityService(OrganizationRepository orgRepository,
                                         KybProfileRepository kybRepository,
                                         KycProfileRepository kycRepository,
                                         AuthorizedRepresentativeRepository repRepository,
                                         OrganizationBankAccountRepository bankRepository,
                                         VerificationProvider verificationProvider) {
        this.orgRepository = orgRepository;
        this.kybRepository = kybRepository;
        this.kycRepository = kycRepository;
        this.repRepository = repRepository;
        this.bankRepository = bankRepository;
        this.verificationProvider = verificationProvider;
    }

    @Transactional(readOnly = true)
    public FinxOrganizationVerificationResponse getOrganizationVerification(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        KybProfile kyb = kybRepository.findByOrganizationId(orgId).orElse(null);
        List<AuthorizedRepresentative> reps = repRepository.findByOrganizationId(orgId);
        List<OrganizationBankAccount> bankAccounts = bankRepository.findByOrganizationId(orgId);

        List<FinxCheckItem> checks = new ArrayList<>();
        EntityType entityType = org.getEntityType();

        // 1. Business PAN Check
        boolean panOk = kyb != null && kyb.getPanStatus() == VerificationStatus.VERIFIED;
        checks.add(new FinxCheckItem(
                "PAN",
                "Business PAN Verification",
                kyb != null ? kyb.getPanStatus().name() : "NOT_STARTED",
                panOk ? "Authoritative PAN verification completed" : "Business PAN pending or unverified",
                true
        ));

        // 2. GSTIN Check (conditional: required for Ltd/Pvt Ltd/LLP, optional for Proprietorship)
        boolean gstRequired = entityType != EntityType.PROPRIETORSHIP;
        boolean gstOk = false;
        if (kyb != null && kyb.getGstStatus() == VerificationStatus.VERIFIED) {
            gstOk = true;
        } else if (!gstRequired && (kyb == null || kyb.getGstin() == null || kyb.getGstin().isBlank())) {
            gstOk = true; // Not required and not provided
        }
        checks.add(new FinxCheckItem(
                "GSTIN",
                "GSTIN Registry Verification",
                kyb != null ? kyb.getGstStatus().name() : (gstRequired ? "NOT_STARTED" : "NOT_REQUIRED"),
                gstOk ? (gstRequired ? "Active GSTIN verified" : "GSTIN not mandatory for proprietorship") : "GSTIN verification pending",
                gstRequired
        ));

        // 3. Corporate Registration Check (CIN for PVT_LTD/PUBLIC_LTD, LLPIN for LLP, Udyam for PROPRIETORSHIP)
        boolean regRequired = entityType == EntityType.PRIVATE_LIMITED || entityType == EntityType.PUBLIC_LIMITED || entityType == EntityType.LLP;
        boolean regOk = false;
        String regLabel = switch (entityType) {
            case PRIVATE_LIMITED, PUBLIC_LIMITED -> "Corporate CIN (MCA)";
            case LLP -> "LLPIN Registration";
            case PROPRIETORSHIP -> "MSME / Udyam Registration";
            default -> "Business Registration";
        };
        if (kyb != null && kyb.getRegistrationStatus() == VerificationStatus.VERIFIED) {
            regOk = true;
        } else if (!regRequired && (kyb == null || kyb.getUdyamNumber() == null || kyb.getUdyamNumber().isBlank())) {
            regOk = true;
        }
        checks.add(new FinxCheckItem(
                "REGISTRATION",
                regLabel,
                kyb != null ? kyb.getRegistrationStatus().name() : (regRequired ? "NOT_STARTED" : "NOT_REQUIRED"),
                regOk ? "Registration record validated" : "Registration proof pending",
                regRequired
        ));

        // 4. Authorized Representative & Signatory Check
        boolean signatoryOk = false;
        String signatoryDetail = "No authorized signatory verified";
        for (AuthorizedRepresentative rep : reps) {
            if (rep.getAuthorityStatus() == AuthorityStatus.VERIFIED) {
                KycProfile repKyc = kycRepository.findByUserId(rep.getUserId()).orElse(null);
                if (repKyc != null && repKyc.getStatus() == VerificationStatus.VERIFIED) {
                    signatoryOk = true;
                    signatoryDetail = "Verified signatory with completed individual KYC (" + rep.getRelationshipType() + ")";
                    break;
                } else {
                    signatoryDetail = "Representative authority verified, but individual KYC is pending";
                }
            }
        }
        checks.add(new FinxCheckItem(
                "SIGNATORY",
                "Authorized Signatory & KYC",
                signatoryOk ? "VERIFIED" : (reps.isEmpty() ? "NOT_STARTED" : "PENDING"),
                signatoryDetail,
                true
        ));

        // 5. Business Bank Account Check
        boolean bankOk = false;
        String bankDetail = "No verified business bank account";
        for (OrganizationBankAccount acc : bankAccounts) {
            if (acc.getVerificationStatus() == BankVerificationStatus.VERIFIED) {
                bankOk = true;
                bankDetail = "Verified " + acc.getBankName() + " account (" + acc.getAccountNumberMasked() + ")";
                break;
            } else if (acc.getVerificationStatus() == BankVerificationStatus.IN_PROGRESS) {
                bankDetail = "Penny drop verification in progress";
            }
        }
        checks.add(new FinxCheckItem(
                "BANK",
                "Business Bank Account Penny Drop",
                bankOk ? "VERIFIED" : (bankAccounts.isEmpty() ? "NOT_STARTED" : "PENDING"),
                bankDetail,
                true
        ));

        // Calculate overall verification status
        VerificationStatus overall;
        boolean hasFailed = (kyb != null && (kyb.getPanStatus() == VerificationStatus.FAILED || kyb.getGstStatus() == VerificationStatus.FAILED));
        boolean hasReview = (kyb != null && (kyb.getStatus() == VerificationStatus.PENDING_REVIEW || kyb.getPanStatus() == VerificationStatus.PENDING_REVIEW));

        if (panOk && gstOk && regOk && signatoryOk && bankOk) {
            overall = VerificationStatus.VERIFIED;
        } else if (hasFailed) {
            overall = VerificationStatus.FAILED;
        } else if (hasReview) {
            overall = VerificationStatus.PENDING_REVIEW;
        } else if (kyb != null && kyb.getStatus() == VerificationStatus.IN_PROGRESS) {
            overall = VerificationStatus.IN_PROGRESS;
        } else {
            overall = VerificationStatus.NOT_STARTED;
        }

        boolean kybVerified = overall == VerificationStatus.VERIFIED;
        boolean canActivate = kybVerified;
        boolean canSign = signatoryOk;
        boolean isSandbox = verificationProvider.isSandbox();

        String badge = kybVerified
                ? (isSandbox ? "MOCK / SANDBOX VERIFIED BUSINESS" : "FINX VERIFIED BUSINESS")
                : "ACTION REQUIRED";

        return new FinxOrganizationVerificationResponse(
                org.getId(),
                org.getLegalName(),
                org.getEntityType().name(),
                overall,
                kybVerified,
                canActivate,
                canSign,
                false, // Real money movement is NEVER enabled without regulated banking partner
                false,
                "BANKING_PARTNER_NOT_CONFIGURED",
                checks,
                badge,
                isSandbox
        );
    }

    public void validateDealActivationEligibility(UUID orgId) {
        FinxOrganizationVerificationResponse status = getOrganizationVerification(orgId);
        if (!status.kybVerified()) {
            throw new BadRequestException("Deal activation requires verified business status (KYB). Current status: " + status.overallStatus());
        }
    }

    public void validateAgreementSigningEligibility(UUID userId, UUID orgId) {
        KycProfile userKyc = kycRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Agreement signing requires completed individual KYC. Current status: NOT_STARTED"));

        if (userKyc.getStatus() != VerificationStatus.VERIFIED) {
            throw new BadRequestException("Agreement signing requires completed individual KYC. Current status: " + userKyc.getStatus());
        }

        if (orgId != null) {
            boolean hasAuthority = repRepository.existsByOrganizationIdAndUserIdAndAuthorityStatus(orgId, userId, AuthorityStatus.VERIFIED);
            if (!hasAuthority) {
                throw new BadRequestException("User does not have VERIFIED signing authority for organization " + orgId);
            }
        }
    }

    public void validateProductionMoneyMovement() {
        throw new BadRequestException("BANKING_PARTNER_NOT_CONFIGURED: Production money movement is disabled. Regulated banking/payment partner integration has not been configured yet.");
    }

    public record BeneficiaryEligibilityResult(
            boolean eligible,
            OrganizationBankAccount bankAccount,
            String failureReason,
            boolean isSandbox
    ) {}

    @Transactional(readOnly = true)
    public BeneficiaryEligibilityResult evaluateBeneficiaryEligibility(UUID organizationId) {
        if (organizationId == null) {
            return new BeneficiaryEligibilityResult(false, null, "Organization ID is required", false);
        }

        Organization org = orgRepository.findById(organizationId).orElse(null);
        if (org == null) {
            return new BeneficiaryEligibilityResult(false, null, "Organization not found: " + organizationId, false);
        }

        List<OrganizationBankAccount> accounts = bankRepository.findByOrganizationId(organizationId);

        List<OrganizationBankAccount> eligibleAccounts = accounts.stream()
                .filter(a -> a.getVerificationStatus() == BankVerificationStatus.VERIFIED)
                .filter(a -> a.getEncryptedAccountNumber() != null && !a.getEncryptedAccountNumber().isBlank())
                .filter(a -> a.getVerifiedAt() != null)
                .filter(a -> !"MISMATCH".equalsIgnoreCase(a.getNameMatchStatus()))
                .collect(Collectors.toList());

        if (eligibleAccounts.isEmpty()) {
            return new BeneficiaryEligibilityResult(false, null, "No eligible verified bank accounts found for organization " + organizationId, false);
        }

        if (eligibleAccounts.size() > 1) {
            return new BeneficiaryEligibilityResult(
                    false,
                    null,
                    "Ambiguous beneficiary: multiple eligible verified bank accounts found (" + eligibleAccounts.size() + ") for organization " + organizationId + ". Explicit beneficiary selection required.",
                    false
            );
        }

        OrganizationBankAccount single = eligibleAccounts.get(0);
        boolean isSandbox = "MOCK".equalsIgnoreCase(single.getProvider())
                || (single.getVerificationReference() != null && single.getVerificationReference().startsWith("MOCK"))
                || verificationProvider.isSandbox();

        return new BeneficiaryEligibilityResult(true, single, null, isSandbox);
    }

    @Transactional(readOnly = true)
    public OrganizationBankAccount requireSingleEligibleBeneficiary(UUID organizationId) {
        BeneficiaryEligibilityResult result = evaluateBeneficiaryEligibility(organizationId);
        if (!result.eligible()) {
            throw new BadRequestException("Beneficiary eligibility check failed: " + result.failureReason());
        }
        return result.bankAccount();
    }
}
