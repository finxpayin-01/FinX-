package com.finx.org.service;

import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.BeneficiaryVerificationRequest;
import com.finx.banking.dto.BankingDtos.BeneficiaryVerificationResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.dto.OrgDtos.*;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.enums.OrgEnums.OrgVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import com.finx.verification.service.PiiEncryptionService;
import com.finx.verification.service.VerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    private final OrganizationRepository orgRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationBankAccountRepository bankAccountRepository;
    private final KybProfileRepository kybProfileRepository;
    private final KycProfileRepository kycProfileRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DisputeRepository disputeRepository;
    private final BankingProvider bankingProvider;
    private final AuditService auditService;
    private final PiiEncryptionService piiEncryptionService;
    private final VerificationService verificationService;

    public OrganizationService(OrganizationRepository orgRepository,
                               OrganizationMemberRepository memberRepository,
                               OrganizationBankAccountRepository bankAccountRepository,
                               KybProfileRepository kybProfileRepository,
                               KycProfileRepository kycProfileRepository,
                               DealRepository dealRepository,
                               MilestoneRepository milestoneRepository,
                               DisputeRepository disputeRepository,
                               BankingProvider bankingProvider,
                               AuditService auditService) {
        this(orgRepository, memberRepository, bankAccountRepository, kybProfileRepository,
                kycProfileRepository, dealRepository, milestoneRepository, disputeRepository,
                bankingProvider, auditService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrganizationService(OrganizationRepository orgRepository,
                               OrganizationMemberRepository memberRepository,
                               OrganizationBankAccountRepository bankAccountRepository,
                               KybProfileRepository kybProfileRepository,
                               KycProfileRepository kycProfileRepository,
                               DealRepository dealRepository,
                               MilestoneRepository milestoneRepository,
                               DisputeRepository disputeRepository,
                               BankingProvider bankingProvider,
                               AuditService auditService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) PiiEncryptionService piiEncryptionService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) @Lazy VerificationService verificationService) {
        this.orgRepository = orgRepository;
        this.memberRepository = memberRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.kybProfileRepository = kybProfileRepository;
        this.kycProfileRepository = kycProfileRepository;
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.disputeRepository = disputeRepository;
        this.bankingProvider = bankingProvider;
        this.auditService = auditService;
        this.piiEncryptionService = piiEncryptionService;
        this.verificationService = verificationService;
    }

    @Transactional
    public OrganizationResponse createOrganization(CreateOrgRequest request, UserPrincipal currentUser) {
        Organization org = new Organization(
                request.name(),
                request.legalName(),
                request.entityType(),
                request.registrationNumber(),
                request.taxId(),
                request.gstin(),
                request.registeredAddress(),
                currentUser.getId()
        );

        Organization savedOrg = orgRepository.save(org);

        // Assign creator as OWNER
        OrganizationMember member = new OrganizationMember(savedOrg.getId(), currentUser.getId(), OrgRole.OWNER);
        memberRepository.save(member);

        auditService.logEvent(
                currentUser.getId(),
                "ORGANIZATION_CREATED",
                "ORGANIZATION",
                savedOrg.getId().toString(),
                "Created organization: " + savedOrg.getName() + " (" + savedOrg.getLegalName() + ")"
        );

        log.info("Organization created: id={} name={} creator={}", savedOrg.getId(), savedOrg.getName(), currentUser.getId());
        return mapToResponse(savedOrg);
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> getMyOrganizations(UserPrincipal currentUser) {
        List<Organization> orgs = orgRepository.findByMemberUserId(currentUser.getId());
        return orgs.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getOrganizationById(UUID id, UserPrincipal currentUser) {
        Organization org = orgRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", id));
        validateOrgMembership(id, currentUser);
        return mapToResponse(org);
    }

    @Transactional
    public MemberResponse addMember(UUID orgId, AddMemberRequest request, UserPrincipal currentUser) {
        validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN));

        Optional<OrganizationMember> existing = memberRepository.findByOrganizationIdAndUserId(orgId, request.userId());
        if (existing.isPresent()) {
            throw new BadRequestException("User is already a member of this organization");
        }

        OrganizationMember member = new OrganizationMember(orgId, request.userId(), request.role());
        OrganizationMember saved = memberRepository.save(member);

        auditService.logEvent(
                currentUser.getId(),
                "ORGANIZATION_MEMBER_ADDED",
                "ORGANIZATION_MEMBER",
                saved.getId().toString(),
                "Added user " + request.userId() + " with role " + request.role() + " to org " + orgId
        );

        return new MemberResponse(saved.getId(), saved.getOrganizationId(), saved.getUserId(), saved.getRole(), saved.getStatus(), saved.getJoinedAt().toString());
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> getMembers(UUID orgId, UserPrincipal currentUser) {
        validateOrgMembership(orgId, currentUser);
        return memberRepository.findByOrganizationId(orgId).stream()
                .map(m -> new MemberResponse(m.getId(), m.getOrganizationId(), m.getUserId(), m.getRole(), m.getStatus(), m.getJoinedAt().toString()))
                .collect(Collectors.toList());
    }

    @Transactional
    public BankAccountResponse addBankAccount(UUID orgId, AddBankAccountRequest request, UserPrincipal currentUser) {
        validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.FINANCE));

        String rawAcc = request.accountNumber().trim();
        String hash = sha256(rawAcc);

        if (bankAccountRepository.existsByAccountNumberHash(hash)) {
            throw new BadRequestException("This bank account has already been registered");
        }

        String masked = piiEncryptionService != null ? piiEncryptionService.maskBankAccount(rawAcc) : maskAccountNumber(rawAcc);
        String encrypted = piiEncryptionService != null ? piiEncryptionService.encrypt(rawAcc) : null;

        // If marked primary, unset other primary accounts
        if (request.isPrimary()) {
            bankAccountRepository.findByOrganizationIdAndIsPrimaryTrue(orgId).ifPresent(acc -> {
                acc.setPrimary(false);
                bankAccountRepository.save(acc);
            });
        }

        OrganizationBankAccount bankAcc = new OrganizationBankAccount(
                orgId,
                request.accountHolderName().trim(),
                masked,
                hash,
                request.ifscCode().trim().toUpperCase(Locale.ROOT),
                request.bankName().trim(),
                request.branchName(),
                request.isPrimary()
        );
        bankAcc.setEncryptedAccountNumber(encrypted);
        bankAcc.setAccountType("CURRENT");

        OrganizationBankAccount saved = bankAccountRepository.save(bankAcc);

        auditService.logEvent(
                currentUser.getId(),
                "BANK_ACCOUNT_ADDED",
                "BANK_ACCOUNT",
                saved.getId().toString(),
                "Added bank account: " + masked + " (" + saved.getBankName() + ") for org " + orgId
        );

        return mapBankToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> getBankAccounts(UUID orgId, UserPrincipal currentUser) {
        validateOrgMembership(orgId, currentUser);
        return bankAccountRepository.findByOrganizationId(orgId).stream()
                .map(this::mapBankToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public BankAccountResponse verifyBankAccount(UUID orgId, UUID accountId, UserPrincipal currentUser) {
        if (verificationService != null) {
            verificationService.verifyBankAccount(orgId, accountId, currentUser);
            OrganizationBankAccount updated = bankAccountRepository.findById(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("OrganizationBankAccount", "id", accountId));
            return mapBankToResponse(updated);
        }
        validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.FINANCE));

        OrganizationBankAccount account = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationBankAccount", "id", accountId));

        if (!account.getOrganizationId().equals(orgId)) {
            throw new BadRequestException("Bank account does not belong to specified organization");
        }

        if (account.getEncryptedAccountNumber() == null || account.getEncryptedAccountNumber().isBlank()) {
            throw new BadRequestException("Cannot verify bank account: full account details are missing (masked-only record)");
        }

        throw new BadRequestException("Direct organization bank account verification is deprecated; canonical VerificationService required");
    }

    @Transactional(readOnly = true)
    public VerifiedBusinessProfile getVerifiedBusinessProfile(UUID orgId, UserPrincipal currentUser) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        if (currentUser == null) {
            throw new UnauthorizedException("Authentication required to view verified business profile");
        }
        if (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE) {
            validateOrgMembership(orgId, currentUser);
        }

        boolean isKybVerified = kybProfileRepository.existsByOrganizationIdAndStatus(orgId, VerificationStatus.VERIFIED)
                || org.getVerificationStatus() == OrgVerificationStatus.VERIFIED;

        boolean isBankVerified = bankAccountRepository.findByOrganizationIdAndVerificationStatus(orgId, BankVerificationStatus.VERIFIED).isPresent();

        // Check if any owner has verified KYC
        boolean isSignatoryVerified = memberRepository.findByOrganizationId(orgId).stream()
                .filter(m -> m.getRole() == OrgRole.OWNER || m.getRole() == OrgRole.AUTHORIZED_SIGNATORY)
                .anyMatch(m -> kycProfileRepository.existsByUserIdAndStatus(m.getUserId(), VerificationStatus.VERIFIED));

        // Calculate platform metrics (note: metrics remain non-authoritative pending D06)
        int completedDeals = dealRepository.findAll().stream()
                .filter(d -> (orgId.equals(d.getBuyerOrganizationId()) || orgId.equals(d.getSellerOrganizationId())) && d.getStatus() == DealStatus.COMPLETED)
                .mapToInt(d -> 1).sum();

        int completedMilestones = milestoneRepository.findAll().stream()
                .filter(m -> m.getStatus() == MilestoneStatus.COMPLETED)
                .mapToInt(m -> 1).sum();

        int disputeCount = (int) disputeRepository.count();

        return new VerifiedBusinessProfile(
                org.getId(),
                org.getLegalName(),
                org.getEntityType(),
                isKybVerified,
                isBankVerified,
                isSignatoryVerified,
                completedDeals,
                completedMilestones,
                completedDeals > 0 ? 1 : 0,
                disputeCount,
                BigDecimal.ZERO
        );
    }

    @Transactional(readOnly = true)
    public VerifiedBusinessProfile getVerifiedBusinessProfile(UUID orgId) {
        return getVerifiedBusinessProfile(orgId, null);
    }

    public void validateOrgMembership(UUID orgId, UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ADMIN) return;
        OrganizationMember member = memberRepository.findByOrganizationIdAndUserId(orgId, currentUser.getId())
                .orElseThrow(() -> new UnauthorizedException("You are not a member of this organization"));
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new UnauthorizedException("Organization membership is not active (status: " + member.getStatus() + ")");
        }
    }

    public void validateOrgRole(UUID orgId, UserPrincipal currentUser, Set<OrgRole> allowedRoles) {
        if (currentUser.getRole() == Role.ADMIN) return;
        OrganizationMember member = memberRepository.findByOrganizationIdAndUserId(orgId, currentUser.getId())
                .orElseThrow(() -> new UnauthorizedException("You are not a member of this organization"));
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new UnauthorizedException("Organization membership is not active (status: " + member.getStatus() + ")");
        }
        if (!allowedRoles.contains(member.getRole())) {
            throw new UnauthorizedException("Your role (" + member.getRole() + ") is not authorized to perform this operation");
        }
    }

    private OrganizationResponse mapToResponse(Organization org) {
        return new OrganizationResponse(
                org.getId(),
                org.getName(),
                org.getLegalName(),
                org.getEntityType(),
                org.getRegistrationNumber(),
                org.getTaxId(),
                org.getGstin(),
                org.getRegisteredAddress(),
                org.getVerificationStatus(),
                org.getCreatedBy(),
                org.getCreatedAt().toString()
        );
    }

    private BankAccountResponse mapBankToResponse(OrganizationBankAccount a) {
        return new BankAccountResponse(
                a.getId(),
                a.getOrganizationId(),
                a.getAccountHolderName(),
                a.getAccountNumberMasked(),
                a.getIfscCode(),
                a.getBankName(),
                a.getBranchName(),
                a.isPrimary(),
                a.getVerificationStatus(),
                a.getVerificationReference(),
                a.getVerifiedAt() != null ? a.getVerifiedAt().toString() : null
        );
    }

    private String maskAccountNumber(String raw) {
        if (raw.length() <= 4) return raw;
        return "*".repeat(raw.length() - 4) + raw.substring(raw.length() - 4);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Could not hash account number", e);
        }
    }
}
