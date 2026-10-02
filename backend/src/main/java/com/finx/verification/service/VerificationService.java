package com.finx.verification.service;

import com.finx.audit.service.AuditService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.enums.OrgEnums.OrgVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.org.service.OrganizationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.verification.dto.VerificationDtos.*;
import com.finx.verification.entity.*;
import com.finx.verification.enums.*;
import com.finx.verification.provider.VerificationProvider;
import com.finx.verification.provider.model.VerificationProviderModels.*;
import com.finx.verification.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final KycProfileRepository kycRepository;
    private final KybProfileRepository kybRepository;
    private final OrganizationRepository orgRepository;
    private final OrganizationBankAccountRepository bankRepository;
    private final AuthorizedRepresentativeRepository repRepository;
    private final BeneficialOwnerRepository beneficialOwnerRepository;
    private final VerificationConsentRepository consentRepository;
    private final VerificationEventRepository eventRepository;
    private final VerificationCheckRepository checkRepository;
    private final UserRepository userRepository;
    private final OrganizationService orgService;
    private final VerificationProvider verificationProvider;
    private final NameMatchService nameMatchService;
    private final PiiEncryptionService piiEncryptionService;
    private final AuditService auditService;

    public VerificationService(KycProfileRepository kycRepository,
                               KybProfileRepository kybRepository,
                               OrganizationRepository orgRepository,
                               OrganizationBankAccountRepository bankRepository,
                               AuthorizedRepresentativeRepository repRepository,
                               BeneficialOwnerRepository beneficialOwnerRepository,
                               VerificationConsentRepository consentRepository,
                               VerificationEventRepository eventRepository,
                               VerificationCheckRepository checkRepository,
                               UserRepository userRepository,
                               OrganizationService orgService,
                               VerificationProvider verificationProvider,
                               NameMatchService nameMatchService,
                               PiiEncryptionService piiEncryptionService,
                               AuditService auditService) {
        this.kycRepository = kycRepository;
        this.kybRepository = kybRepository;
        this.orgRepository = orgRepository;
        this.bankRepository = bankRepository;
        this.repRepository = repRepository;
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.consentRepository = consentRepository;
        this.eventRepository = eventRepository;
        this.checkRepository = checkRepository;
        this.userRepository = userRepository;
        this.orgService = orgService;
        this.verificationProvider = verificationProvider;
        this.nameMatchService = nameMatchService;
        this.piiEncryptionService = piiEncryptionService;
        this.auditService = auditService;
    }

    // ==========================================
    // 1. CONSENT CAPTURE
    // ==========================================
    @Transactional
    public ConsentResponse recordConsent(RecordConsentRequest request, UserPrincipal currentUser, String ip, String userAgent) {
        VerificationConsent consent = new VerificationConsent(
                currentUser.getId(),
                request.organizationId(),
                request.verificationType(),
                request.consentVersion(),
                request.purpose(),
                ip,
                userAgent
        );
        VerificationConsent saved = consentRepository.save(consent);

        recordEvent("USER", currentUser.getId(), "CONSENT_" + request.verificationType(),
                verificationProvider.getProviderName(), saved.getId().toString(),
                null, "CONSENTED", "User granted verification consent for " + request.verificationType(),
                currentUser.getId(), "{\"version\":\"" + request.consentVersion() + "\"}");

        return new ConsentResponse(
                saved.getId(), saved.getUserId(), saved.getOrganizationId(),
                saved.getVerificationType(), saved.getConsentVersion(),
                saved.getPurpose(), saved.getConsentedAt().toString()
        );
    }

    private void enforceConsent(UUID userId, UUID orgId, ConsentType type) {
        boolean hasConsent = orgId != null
                ? consentRepository.existsByUserIdAndOrganizationIdAndVerificationType(userId, orgId, type)
                : consentRepository.existsByUserIdAndVerificationType(userId, type);

        if (!hasConsent) {
            throw new BadRequestException("Verification consent required before initiating " + type + " verification. Please record consent first.");
        }
    }

    // ==========================================
    // 2. INDIVIDUAL KYC
    // ==========================================
    @Transactional
    public KycResponse startKyc(UserPrincipal currentUser) {
        KycProfile profile = kycRepository.findByUserId(currentUser.getId())
                .orElseGet(() -> {
                    KycProfile p = new KycProfile(currentUser.getId(), currentUser.getName() != null ? currentUser.getName() : "User", "");
                    p.setStatus(VerificationStatus.IN_PROGRESS);
                    p.setVerificationStartedAt(Instant.now());
                    p.setProvider(verificationProvider.getProviderName());
                    return kycRepository.save(p);
                });
        return mapKycToResponse(profile);
    }

    @Transactional(readOnly = true)
    public KycResponse getKycStatus(UserPrincipal currentUser) {
        return kycRepository.findByUserId(currentUser.getId())
                .map(this::mapKycToResponse)
                .orElse(new KycResponse(null, currentUser.getId(), currentUser.getName(), "", VerificationStatus.NOT_STARTED,
                        verificationProvider.getProviderName(), null, VerificationStatus.NOT_STARTED, VerificationStatus.NOT_STARTED,
                        "NOT_STARTED", 0.0, null, null, verificationProvider.isSandbox()));
    }

    @Transactional
    public VerifyPanResponse verifyIndividualPan(VerifyPanRequest request, UserPrincipal currentUser, String ip, String userAgent) {
        enforceConsent(currentUser.getId(), null, ConsentType.PAN);

        String rawPan = request.panNumber().trim().toUpperCase(Locale.ROOT);
        String maskedPan = piiEncryptionService.maskPan(rawPan);

        KycProfile profile = kycRepository.findByUserId(currentUser.getId())
                .orElseGet(() -> new KycProfile(currentUser.getId(), request.fullName().trim(), maskedPan));

        String oldStatus = profile.getStatus().name();

        PanVerificationRequest providerReq = new PanVerificationRequest(
                rawPan, null, request.fullName().trim(), request.dateOfBirth()
        );
        PanVerificationResult result = verificationProvider.verifyPan(providerReq);

        // Record check
        VerificationCheck check = new VerificationCheck(
                "KYC", profile.getId(), "PAN", result.valid() ? "VERIFIED" : "FAILED",
                verificationProvider.getProviderName(), result.providerReference(),
                result.panStatus(), result.message(), result.nameMatchStatus(), result.nameMatchScore()
        );
        checkRepository.save(check);

        // Update profile
        profile.setFullName(request.fullName().trim());
        profile.setPanNumberMasked(maskedPan);
        profile.setProvider(verificationProvider.getProviderName());
        profile.setProviderReferenceId(result.providerReference());
        profile.setNameMatchScore(result.nameMatchScore());
        profile.setNameMatchStatus(result.nameMatchStatus());

        if (result.valid() && "MATCH".equals(result.nameMatchStatus())) {
            profile.setPanStatus(VerificationStatus.VERIFIED);
        } else if (result.valid() && "PARTIAL_MATCH".equals(result.nameMatchStatus())) {
            profile.setPanStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("Name partial match requires review: " + result.nameMatchScore() + "%");
        } else if (result.valid()) {
            profile.setPanStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("Name mismatch flagged for compliance review: " + result.nameMatchScore() + "%");
        } else {
            profile.setPanStatus(VerificationStatus.FAILED);
            profile.setRejectionReason(result.message());
        }

        evaluateKycOverallStatus(profile);
        KycProfile saved = kycRepository.save(profile);

        recordEvent("USER", currentUser.getId(), "PAN", verificationProvider.getProviderName(),
                result.providerReference(), oldStatus, saved.getStatus().name(),
                result.message(), currentUser.getId(), "{\"panMasked\":\"" + maskedPan + "\"}");

        return new VerifyPanResponse(
                result.valid(), maskedPan, result.registeredName(), result.panStatus(),
                result.category(), result.nameMatchScore(), result.nameMatchStatus(),
                result.providerReference(), result.message()
        );
    }

    @Transactional
    public IdentitySessionResponse createIdentitySession(StartIdentitySessionRequest request, UserPrincipal currentUser) {
        enforceConsent(currentUser.getId(), null, ConsentType.IDENTITY);

        KycProfile profile = kycRepository.findByUserId(currentUser.getId())
                .orElseGet(() -> new KycProfile(currentUser.getId(), currentUser.getName() != null ? currentUser.getName() : "User", ""));

        IdentitySessionRequest req = new IdentitySessionRequest(
                currentUser.getId().toString(), null, request.redirectUrl(), request.callbackUrl()
        );
        IdentitySessionResult res = verificationProvider.createIdentitySession(req);

        profile.setIdentityStatus(VerificationStatus.IN_PROGRESS);
        profile.setProviderReferenceId(res.sessionId());
        kycRepository.save(profile);

        recordEvent("USER", currentUser.getId(), "IDENTITY_SESSION_CREATED", verificationProvider.getProviderName(),
                res.sessionId(), null, "IN_PROGRESS", "Created eKYC/DigiLocker session", currentUser.getId(), null);

        return new IdentitySessionResponse(res.sessionId(), res.verificationUrl(), res.providerReference(), res.status());
    }

    @Transactional
    public KycResponse checkIdentityStatus(String sessionId, UserPrincipal currentUser) {
        KycProfile profile = kycRepository.findByUserId(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("KycProfile", "userId", currentUser.getId()));

        IdentityStatusResult res = verificationProvider.getIdentityStatus(sessionId);

        VerificationCheck check = new VerificationCheck(
                "KYC", profile.getId(), "IDENTITY", res.status(),
                verificationProvider.getProviderName(), res.providerReference(),
                res.status(), res.message(), "MATCH", res.nameMatchScore()
        );
        checkRepository.save(check);

        if ("VERIFIED".equalsIgnoreCase(res.status())) {
            profile.setIdentityStatus(VerificationStatus.VERIFIED);
        } else if ("FAILED".equalsIgnoreCase(res.status())) {
            profile.setIdentityStatus(VerificationStatus.FAILED);
            profile.setRejectionReason(res.message());
        }

        evaluateKycOverallStatus(profile);
        KycProfile saved = kycRepository.save(profile);

        recordEvent("USER", currentUser.getId(), "IDENTITY_STATUS_CHECK", verificationProvider.getProviderName(),
                res.providerReference(), null, profile.getIdentityStatus().name(), res.message(), currentUser.getId(), null);

        return mapKycToResponse(saved);
    }

    private void evaluateKycOverallStatus(KycProfile profile) {
        if (profile.getPanStatus() == VerificationStatus.VERIFIED &&
                (profile.getIdentityStatus() == VerificationStatus.VERIFIED || profile.getIdentityStatus() == VerificationStatus.NOT_STARTED)) {
            // In sandbox or when PAN is verified
            profile.setStatus(VerificationStatus.VERIFIED);
            profile.setVerifiedAt(Instant.now());
            profile.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
            profile.setVerificationReference("KYC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        } else if (profile.getPanStatus() == VerificationStatus.FAILED || profile.getIdentityStatus() == VerificationStatus.FAILED) {
            profile.setStatus(VerificationStatus.FAILED);
        } else if (profile.getPanStatus() == VerificationStatus.PENDING_REVIEW || profile.getIdentityStatus() == VerificationStatus.PENDING_REVIEW) {
            profile.setStatus(VerificationStatus.PENDING_REVIEW);
        } else {
            profile.setStatus(VerificationStatus.IN_PROGRESS);
        }
    }

    // ==========================================
    // 3. CORPORATE KYB
    // ==========================================
    @Transactional
    public KybResponse startKyb(UUID orgId, UserPrincipal currentUser) {
        orgService.validateOrgMembership(orgId, currentUser);
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        KybProfile profile = kybRepository.findByOrganizationId(orgId)
                .orElseGet(() -> {
                    KybProfile p = new KybProfile(orgId, "", "", "", "");
                    p.setStatus(VerificationStatus.IN_PROGRESS);
                    p.setVerificationStartedAt(Instant.now());
                    p.setProvider(verificationProvider.getProviderName());
                    return kybRepository.save(p);
                });

        return mapKybToResponse(profile, org);
    }

    @Transactional(readOnly = true)
    public KybResponse getKybStatus(UUID orgId, UserPrincipal currentUser) {
        orgService.validateOrgMembership(orgId, currentUser);
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        return kybRepository.findByOrganizationId(orgId)
                .map(p -> mapKybToResponse(p, org))
                .orElse(new KybResponse(null, orgId, org.getLegalName(), "", "", "", "", "",
                        VerificationStatus.NOT_STARTED, verificationProvider.getProviderName(), null,
                        VerificationStatus.NOT_STARTED, VerificationStatus.NOT_STARTED, VerificationStatus.NOT_STARTED,
                        "NOT_STARTED", 0.0, null, null, verificationProvider.isSandbox()));
    }

    @Transactional
    public KybResponse verifyBusinessPan(UUID orgId, VerifyBusinessPanRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.AUTHORIZED_SIGNATORY));
        enforceConsent(currentUser.getId(), orgId, ConsentType.PAN);

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        String rawPan = request.businessPan().trim().toUpperCase(Locale.ROOT);
        String maskedPan = piiEncryptionService.maskPan(rawPan);

        KybProfile profile = kybRepository.findByOrganizationId(orgId)
                .orElseGet(() -> new KybProfile(orgId, maskedPan, "", "", ""));

        PanVerificationRequest req = new PanVerificationRequest(rawPan, null, org.getLegalName(), null);
        PanVerificationResult res = verificationProvider.verifyPan(req);

        VerificationCheck check = new VerificationCheck(
                "KYB", profile.getId(), "PAN", res.valid() ? "VERIFIED" : "FAILED",
                verificationProvider.getProviderName(), res.providerReference(),
                res.panStatus(), res.message(), res.nameMatchStatus(), res.nameMatchScore()
        );
        checkRepository.save(check);

        profile.setBusinessPanMasked(maskedPan);
        profile.setProvider(verificationProvider.getProviderName());
        profile.setProviderReferenceId(res.providerReference());
        profile.setNameMatchScore(res.nameMatchScore());
        profile.setNameMatchStatus(res.nameMatchStatus());

        if (res.valid() && "MATCH".equals(res.nameMatchStatus())) {
            profile.setPanStatus(VerificationStatus.VERIFIED);
        } else if (res.valid() && "PARTIAL_MATCH".equals(res.nameMatchStatus())) {
            profile.setPanStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("PAN name partial match: " + res.nameMatchScore() + "%");
        } else if (res.valid()) {
            profile.setPanStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("PAN name mismatch requires review: " + res.nameMatchScore() + "%");
        } else {
            profile.setPanStatus(VerificationStatus.FAILED);
            profile.setRejectionReason(res.message());
        }

        evaluateKybOverallStatus(profile, org);
        KybProfile saved = kybRepository.save(profile);

        recordEvent("ORGANIZATION", orgId, "PAN", verificationProvider.getProviderName(),
                res.providerReference(), null, saved.getStatus().name(), res.message(), currentUser.getId(),
                "{\"panMasked\":\"" + maskedPan + "\"}");

        return mapKybToResponse(saved, org);
    }

    @Transactional
    public KybResponse verifyGstin(UUID orgId, VerifyGstinRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.AUTHORIZED_SIGNATORY));
        enforceConsent(currentUser.getId(), orgId, ConsentType.GST);

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        String rawGstin = request.gstin().trim().toUpperCase(Locale.ROOT);
        KybProfile profile = kybRepository.findByOrganizationId(orgId)
                .orElseGet(() -> new KybProfile(orgId, "", rawGstin, "", ""));

        GstVerificationRequest req = new GstVerificationRequest(rawGstin, null, org.getLegalName());
        GstVerificationResult res = verificationProvider.verifyGstin(req);

        VerificationCheck check = new VerificationCheck(
                "KYB", profile.getId(), "GSTIN", res.valid() ? "VERIFIED" : "FAILED",
                verificationProvider.getProviderName(), res.providerReference(),
                res.status(), res.message(), res.nameMatchStatus(), res.nameMatchScore()
        );
        checkRepository.save(check);

        profile.setGstin(rawGstin);
        profile.setTradeName(res.tradeName());
        profile.setAddressStatus(res.address() != null ? "VERIFIED" : "UNVERIFIED");

        if (res.valid() && "MATCH".equals(res.nameMatchStatus())) {
            profile.setGstStatus(VerificationStatus.VERIFIED);
        } else if (res.valid()) {
            profile.setGstStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("GST name match (" + res.nameMatchScore() + "%) flagged for review");
        } else {
            profile.setGstStatus(VerificationStatus.FAILED);
            profile.setRejectionReason(res.message());
        }

        evaluateKybOverallStatus(profile, org);
        KybProfile saved = kybRepository.save(profile);

        recordEvent("ORGANIZATION", orgId, "GSTIN", verificationProvider.getProviderName(),
                res.providerReference(), null, saved.getStatus().name(), res.message(), currentUser.getId(),
                "{\"gstin\":\"" + rawGstin + "\"}");

        return mapKybToResponse(saved, org);
    }

    @Transactional
    public KybResponse verifyRegistration(UUID orgId, VerifyRegistrationRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.AUTHORIZED_SIGNATORY));

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        KybProfile profile = kybRepository.findByOrganizationId(orgId)
                .orElseGet(() -> new KybProfile(orgId, "", "", "", ""));

        String cinOrLlpin = request.cinOrLlpin() != null ? request.cinOrLlpin().trim().toUpperCase(Locale.ROOT) : "";
        profile.setCinOrLlpin(cinOrLlpin);
        profile.setUdyamNumber(request.udyamNumber());

        CinVerificationRequest req = new CinVerificationRequest(cinOrLlpin, null, org.getLegalName());
        CinVerificationResult res = verificationProvider.verifyCin(req);

        VerificationCheck check = new VerificationCheck(
                "KYB", profile.getId(), "CIN", res.valid() ? "VERIFIED" : (res.companyStatus().equals("NOT_SUPPORTED") ? "MANUAL_REVIEW" : "FAILED"),
                verificationProvider.getProviderName(), res.providerReference(),
                res.companyStatus(), res.message(), res.nameMatchStatus(), res.nameMatchScore()
        );
        checkRepository.save(check);

        if (res.valid()) {
            profile.setRegistrationStatus(VerificationStatus.VERIFIED);
            profile.setIncorporationDate(res.dateOfIncorporation());
            profile.setBusinessStatus(res.companyStatus());
        } else if ("MANUAL_REVIEW".equalsIgnoreCase(res.nameMatchStatus()) || "NOT_SUPPORTED".equalsIgnoreCase(res.companyStatus())) {
            profile.setRegistrationStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("Automated check not supported; document required for manual review");
        } else {
            profile.setRegistrationStatus(VerificationStatus.FAILED);
            profile.setRejectionReason(res.message());
        }

        evaluateKybOverallStatus(profile, org);
        KybProfile saved = kybRepository.save(profile);

        recordEvent("ORGANIZATION", orgId, "REGISTRATION", verificationProvider.getProviderName(),
                res.providerReference(), null, saved.getStatus().name(), "Registration verification updated", currentUser.getId(), null);
        return mapKybToResponse(saved, org);
    }

    @Transactional
    public KybResponse submitKybLegacy(UUID orgId, SubmitKybRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.AUTHORIZED_SIGNATORY));
        // Run PAN, GSTIN, and Registration verification
        verifyBusinessPan(orgId, new VerifyBusinessPanRequest(request.businessPan()), currentUser);
        if (request.gstin() != null && !request.gstin().isBlank()) {
            verifyGstin(orgId, new VerifyGstinRequest(request.gstin()), currentUser);
        }
        if (request.cinOrLlpin() != null && !request.cinOrLlpin().isBlank()) {
            verifyRegistration(orgId, new VerifyRegistrationRequest(request.cinOrLlpin(), request.udyamNumber()), currentUser);
        }
        return getKybStatus(orgId, currentUser);
    }

    private void evaluateKybOverallStatus(KybProfile profile, Organization org) {
        boolean panOk = profile.getPanStatus() == VerificationStatus.VERIFIED;
        boolean gstOk = profile.getGstStatus() == VerificationStatus.VERIFIED ||
                (org.getEntityType() == com.finx.org.enums.OrgEnums.EntityType.PROPRIETORSHIP && (profile.getGstin() == null || profile.getGstin().isBlank()));
        boolean regOk = profile.getRegistrationStatus() == VerificationStatus.VERIFIED ||
                (org.getEntityType() == com.finx.org.enums.OrgEnums.EntityType.PROPRIETORSHIP && (profile.getCinOrLlpin() == null || profile.getCinOrLlpin().isBlank()));

        if (panOk && gstOk && regOk) {
            profile.setStatus(VerificationStatus.VERIFIED);
            profile.setVerifiedAt(Instant.now());
            profile.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
            profile.setVerificationReference("KYB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            org.setVerificationStatus(OrgVerificationStatus.VERIFIED);
            orgRepository.save(org);
        } else if (profile.getPanStatus() == VerificationStatus.FAILED || profile.getGstStatus() == VerificationStatus.FAILED) {
            profile.setStatus(VerificationStatus.FAILED);
            org.setVerificationStatus(OrgVerificationStatus.FAILED);
            orgRepository.save(org);
        } else if (profile.getPanStatus() == VerificationStatus.PENDING_REVIEW || profile.getGstStatus() == VerificationStatus.PENDING_REVIEW) {
            profile.setStatus(VerificationStatus.PENDING_REVIEW);
            org.setVerificationStatus(OrgVerificationStatus.PENDING_REVIEW);
            orgRepository.save(org);
        } else {
            profile.setStatus(VerificationStatus.IN_PROGRESS);
            org.setVerificationStatus(OrgVerificationStatus.IN_PROGRESS);
            orgRepository.save(org);
        }
    }

    // ==========================================
    // 4. AUTHORIZED REPRESENTATIVES
    // ==========================================
    @Transactional
    public RepresentativeResponse addRepresentative(UUID orgId, AddRepresentativeRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN));

        User targetUser = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.userId()));

        AuthorizedRepresentative rep = repRepository.findByOrganizationIdAndUserId(orgId, request.userId())
                .orElse(new AuthorizedRepresentative(orgId, request.userId(), request.relationshipType()));

        rep.setRelationshipType(request.relationshipType());
        rep.setAuthorizationDocumentRef(request.documentRef());
        rep.setNotes(request.notes());

        // In sandbox or owner role, mark authority status VERIFIED
        if (request.relationshipType() == AuthorityRelationshipType.OWNER ||
                request.relationshipType() == AuthorityRelationshipType.PROPRIETOR ||
                verificationProvider.isSandbox()) {
            rep.setAuthorityStatus(AuthorityStatus.VERIFIED);
            rep.setVerifiedAt(Instant.now());
            rep.setVerifiedBy(currentUser.getId());
        }

        AuthorizedRepresentative saved = repRepository.save(rep);

        recordEvent("REPRESENTATIVE", saved.getId(), "AUTHORITY_ADDED", verificationProvider.getProviderName(),
                null, null, saved.getAuthorityStatus().name(),
                "Added representative " + targetUser.getEmail() + " as " + request.relationshipType(),
                currentUser.getId(), "{\"orgId\":\"" + orgId + "\"}");

        boolean kycOk = kycRepository.findByUserId(targetUser.getId())
                .map(k -> k.getStatus() == VerificationStatus.VERIFIED)
                .orElse(false);

        return new RepresentativeResponse(
                saved.getId(), saved.getOrganizationId(), saved.getUserId(),
                targetUser.getName(), targetUser.getEmail(), saved.getRelationshipType(),
                saved.getAuthorityStatus(), kycOk,
                saved.getVerifiedAt() != null ? saved.getVerifiedAt().toString() : null
        );
    }

    @Transactional(readOnly = true)
    public List<RepresentativeResponse> getRepresentatives(UUID orgId, UserPrincipal currentUser) {
        orgService.validateOrgMembership(orgId, currentUser);
        return repRepository.findByOrganizationId(orgId).stream()
                .map(r -> {
                    User u = userRepository.findById(r.getUserId()).orElse(null);
                    boolean kycOk = kycRepository.findByUserId(r.getUserId())
                            .map(k -> k.getStatus() == VerificationStatus.VERIFIED)
                            .orElse(false);
                    return new RepresentativeResponse(
                            r.getId(), r.getOrganizationId(), r.getUserId(),
                            u != null ? u.getName() : "Unknown",
                            u != null ? u.getEmail() : "",
                            r.getRelationshipType(), r.getAuthorityStatus(), kycOk,
                            r.getVerifiedAt() != null ? r.getVerifiedAt().toString() : null
                    );
                })
                .collect(Collectors.toList());
    }

    // ==========================================
    // 5. BENEFICIAL OWNERS
    // ==========================================
    @Transactional
    public BeneficialOwnerResponse addBeneficialOwner(UUID orgId, AddBeneficialOwnerRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN));

        String maskedPan = request.pan() != null && !request.pan().isBlank()
                ? piiEncryptionService.maskPan(request.pan().trim().toUpperCase(Locale.ROOT))
                : null;

        BeneficialOwner bo = new BeneficialOwner(
                orgId, request.personName().trim(), request.ownershipPercentage(),
                request.relationshipType().trim(), maskedPan
        );
        BeneficialOwner saved = beneficialOwnerRepository.save(bo);

        recordEvent("ORGANIZATION", orgId, "BENEFICIAL_OWNER_ADDED", verificationProvider.getProviderName(),
                null, null, "PENDING", "Recorded beneficial owner: " + saved.getPersonName(),
                currentUser.getId(), "{\"ownership\":\"" + saved.getOwnershipPercentage() + "%\"}");

        return new BeneficialOwnerResponse(
                saved.getId(), saved.getOrganizationId(), saved.getPersonName(),
                saved.getOwnershipPercentage(), saved.getRelationshipType(),
                saved.getPanMasked(), saved.getVerificationStatus()
        );
    }

    @Transactional(readOnly = true)
    public List<BeneficialOwnerResponse> getBeneficialOwners(UUID orgId, UserPrincipal currentUser) {
        orgService.validateOrgMembership(orgId, currentUser);
        return beneficialOwnerRepository.findByOrganizationId(orgId).stream()
                .map(bo -> new BeneficialOwnerResponse(
                        bo.getId(), bo.getOrganizationId(), bo.getPersonName(),
                        bo.getOwnershipPercentage(), bo.getRelationshipType(),
                        bo.getPanMasked(), bo.getVerificationStatus()
                ))
                .collect(Collectors.toList());
    }

    // ==========================================
    // 6. BANK ACCOUNT VERIFICATION
    // ==========================================
    @Transactional
    public BankAccountResponse addBankAccount(UUID orgId, AddBankAccountRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.FINANCE));

        String rawAcc = request.accountNumber().trim();
        String hash = sha256(rawAcc);

        if (bankRepository.existsByAccountNumberHash(hash)) {
            throw new BadRequestException("This bank account has already been registered");
        }

        String masked = piiEncryptionService.maskBankAccount(rawAcc);
        String encrypted = piiEncryptionService.encrypt(rawAcc);

        if (request.isPrimary()) {
            bankRepository.findByOrganizationIdAndIsPrimaryTrue(orgId).ifPresent(acc -> {
                acc.setPrimary(false);
                bankRepository.save(acc);
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
        bankAcc.setAccountType(request.accountType() != null ? request.accountType() : "CURRENT");
        bankAcc.setProvider(verificationProvider.getProviderName());

        OrganizationBankAccount saved = bankRepository.save(bankAcc);

        recordEvent("BANK_ACCOUNT", saved.getId(), "BANK_ACCOUNT_ADDED", verificationProvider.getProviderName(),
                null, null, "NOT_STARTED", "Added bank account " + masked,
                currentUser.getId(), "{\"orgId\":\"" + orgId + "\"}");

        return mapBankToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> getBankAccounts(UUID orgId, UserPrincipal currentUser) {
        orgService.validateOrgMembership(orgId, currentUser);
        return bankRepository.findByOrganizationId(orgId).stream()
                .map(this::mapBankToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public BankAccountResponse verifyBankAccount(UUID orgId, UUID accountId, UserPrincipal currentUser) {
        return verifyBankAccount(orgId, accountId, currentUser, null, null);
    }

    @Transactional
    public BankAccountResponse verifyBankAccount(UUID orgId, UUID accountId, UserPrincipal currentUser, String ip, String userAgent) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.FINANCE));
        enforceConsent(currentUser.getId(), orgId, ConsentType.BANK);

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        OrganizationBankAccount account = bankRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationBankAccount", "id", accountId));

        if (!account.getOrganizationId().equals(orgId)) {
            throw new BadRequestException("Bank account does not belong to specified organization");
        }

        if (account.getEncryptedAccountNumber() == null || account.getEncryptedAccountNumber().isBlank()) {
            throw new BadRequestException("Cannot verify bank account: full account details are missing (masked-only record)");
        }

        String rawAccount = piiEncryptionService.decrypt(account.getEncryptedAccountNumber());

        BankVerificationRequest req = new BankVerificationRequest(
                rawAccount, account.getIfscCode(), null, account.getAccountHolderName()
        );
        BankVerificationResult res = verificationProvider.verifyBankAccount(req);

        // Record check
        VerificationCheck check = new VerificationCheck(
                "BANK_ACCOUNT", account.getId(), "BANK", res.valid() ? "VERIFIED" : "FAILED",
                verificationProvider.getProviderName(), res.providerReference(),
                "PENNY_DROP", res.message(), res.nameMatchStatus(), res.nameMatchScore()
        );
        checkRepository.save(check);

        account.setProvider(verificationProvider.getProviderName());
        account.setProviderReferenceId(res.providerReference());
        account.setRegisteredNameReturned(res.registeredName());
        account.setNameMatchScore(res.nameMatchScore());
        account.setNameMatchStatus(res.nameMatchStatus());
        account.setNameMatchReason(res.message());

        if (res.valid() && ("MATCH".equals(res.nameMatchStatus()) || "PARTIAL_MATCH".equals(res.nameMatchStatus()))) {
            account.setVerificationStatus(BankVerificationStatus.VERIFIED);
            account.setVerificationReference(res.providerReference());
            account.setVerifiedAt(Instant.now());
        } else if (res.valid()) {
            account.setVerificationStatus(BankVerificationStatus.FAILED);
            account.setNameMatchReason("Bank account registered name mismatch (" + res.nameMatchScore() + "%). Routed to compliance review.");
        } else {
            account.setVerificationStatus(BankVerificationStatus.FAILED);
        }

        OrganizationBankAccount saved = bankRepository.save(account);

        recordEvent("BANK_ACCOUNT", saved.getId(), "BANK_PENNY_DROP", verificationProvider.getProviderName(),
                res.providerReference(), null, saved.getVerificationStatus().name(), res.message(),
                currentUser.getId(), "{\"score\":" + res.nameMatchScore() + "}");

        return mapBankToResponse(saved);
    }

    // ==========================================
    // 7. ADMIN VERIFICATIONS & REVIEWS
    // ==========================================
    @Transactional(readOnly = true)
    public AdminVerificationSummaryResponse getAdminVerificationSummary() {
        List<KycResponse> pendingKyc = kycRepository.findAll().stream()
                .filter(k -> k.getStatus() == VerificationStatus.PENDING_REVIEW || k.getStatus() == VerificationStatus.IN_PROGRESS)
                .map(this::mapKycToResponse)
                .collect(Collectors.toList());

        List<KybResponse> pendingKyb = kybRepository.findAll().stream()
                .filter(k -> k.getStatus() == VerificationStatus.PENDING_REVIEW || k.getStatus() == VerificationStatus.IN_PROGRESS)
                .map(p -> {
                    Organization org = orgRepository.findById(p.getOrganizationId()).orElse(null);
                    return mapKybToResponse(p, org);
                })
                .collect(Collectors.toList());

        List<KybResponse> manualReviews = kybRepository.findAll().stream()
                .filter(k -> k.getStatus() == VerificationStatus.PENDING_REVIEW)
                .map(p -> {
                    Organization org = orgRepository.findById(p.getOrganizationId()).orElse(null);
                    return mapKybToResponse(p, org);
                })
                .collect(Collectors.toList());

        List<BankAccountResponse> bankMismatches = bankRepository.findAll().stream()
                .filter(b -> "MISMATCH".equalsIgnoreCase(b.getNameMatchStatus()) || b.getVerificationStatus() == BankVerificationStatus.FAILED)
                .map(this::mapBankToResponse)
                .collect(Collectors.toList());

        List<VerificationEventDto> recentEvents = eventRepository.findAllByOrderByTimestampDesc().stream()
                .limit(50)
                .map(e -> new VerificationEventDto(
                        e.getId(), e.getSubjectType(), e.getSubjectId(), e.getVerificationType(),
                        e.getProvider(), e.getOldStatus(), e.getNewStatus(), e.getReason(),
                        e.getActorId() != null ? e.getActorId().toString() : "SYSTEM",
                        e.getTimestamp().toString()
                ))
                .collect(Collectors.toList());

        return new AdminVerificationSummaryResponse(pendingKyc, pendingKyb, manualReviews, bankMismatches, recentEvents);
    }

    @Transactional
    public KycResponse reviewKyc(UUID kycId, AdminReviewRequest request, UserPrincipal adminUser) {
        KycProfile profile = kycRepository.findById(kycId)
                .orElseThrow(() -> new ResourceNotFoundException("KycProfile", "id", kycId));

        String oldStatus = profile.getStatus().name();
        String action = request.action().toUpperCase(Locale.ROOT);

        if ("APPROVE_REVIEW".equals(action)) {
            profile.setStatus(VerificationStatus.VERIFIED);
            profile.setPanStatus(VerificationStatus.VERIFIED);
            profile.setIdentityStatus(VerificationStatus.VERIFIED);
            profile.setVerifiedAt(Instant.now());
            profile.setRejectionReason(null);
        } else if ("REJECT".equals(action)) {
            profile.setStatus(VerificationStatus.REJECTED);
            profile.setRejectionReason(request.reason());
        } else if ("REQUEST_INFORMATION".equals(action)) {
            profile.setStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("Compliance requested more information: " + request.reason());
        } else if ("REVERIFY".equals(action)) {
            profile.setStatus(VerificationStatus.IN_PROGRESS);
            profile.setPanStatus(VerificationStatus.NOT_STARTED);
            profile.setIdentityStatus(VerificationStatus.NOT_STARTED);
        } else {
            throw new BadRequestException("Unknown review action: " + action);
        }

        KycProfile saved = kycRepository.save(profile);

        recordEvent("USER", saved.getUserId(), "ADMIN_KYC_REVIEW", "ADMIN",
                saved.getId().toString(), oldStatus, saved.getStatus().name(),
                request.reason(), adminUser.getId(), "{\"action\":\"" + action + "\"}");

        return mapKycToResponse(saved);
    }

    @Transactional
    public KybResponse reviewKyb(UUID kybId, AdminReviewRequest request, UserPrincipal adminUser) {
        KybProfile profile = kybRepository.findById(kybId)
                .orElseThrow(() -> new ResourceNotFoundException("KybProfile", "id", kybId));

        Organization org = orgRepository.findById(profile.getOrganizationId()).orElse(null);
        String oldStatus = profile.getStatus().name();
        String action = request.action().toUpperCase(Locale.ROOT);

        if ("APPROVE_REVIEW".equals(action)) {
            profile.setStatus(VerificationStatus.VERIFIED);
            profile.setPanStatus(VerificationStatus.VERIFIED);
            profile.setGstStatus(VerificationStatus.VERIFIED);
            profile.setRegistrationStatus(VerificationStatus.VERIFIED);
            profile.setVerifiedAt(Instant.now());
            profile.setRejectionReason(null);
            if (org != null) {
                org.setVerificationStatus(OrgVerificationStatus.VERIFIED);
                orgRepository.save(org);
            }
        } else if ("REJECT".equals(action)) {
            profile.setStatus(VerificationStatus.REJECTED);
            profile.setRejectionReason(request.reason());
            if (org != null) {
                org.setVerificationStatus(OrgVerificationStatus.REJECTED);
                orgRepository.save(org);
            }
        } else if ("REQUEST_INFORMATION".equals(action)) {
            profile.setStatus(VerificationStatus.PENDING_REVIEW);
            profile.setRejectionReason("Compliance requested information: " + request.reason());
            if (org != null) {
                org.setVerificationStatus(OrgVerificationStatus.PENDING_REVIEW);
                orgRepository.save(org);
            }
        } else if ("REVERIFY".equals(action)) {
            profile.setStatus(VerificationStatus.IN_PROGRESS);
            profile.setPanStatus(VerificationStatus.NOT_STARTED);
            profile.setGstStatus(VerificationStatus.NOT_STARTED);
            profile.setRegistrationStatus(VerificationStatus.NOT_STARTED);
        } else {
            throw new BadRequestException("Unknown review action: " + action);
        }

        KybProfile saved = kybRepository.save(profile);

        recordEvent("ORGANIZATION", saved.getOrganizationId(), "ADMIN_KYB_REVIEW", "ADMIN",
                saved.getId().toString(), oldStatus, saved.getStatus().name(),
                request.reason(), adminUser.getId(), "{\"action\":\"" + action + "\"}");

        return mapKybToResponse(saved, org);
    }

    // ==========================================
    // HELPERS & MAPPERS
    // ==========================================
    private void recordEvent(String subjectType, UUID subjectId, String verifType, String provider,
                             String ref, String oldStatus, String newStatus, String reason,
                             UUID actorId, String safeMeta) {
        VerificationEvent event = new VerificationEvent(
                subjectType, subjectId, verifType, provider, ref, oldStatus, newStatus, reason, actorId, safeMeta
        );
        eventRepository.save(event);
        if (Set.of("USER", "ORGANIZATION", "BANK_ACCOUNT").contains(subjectType)
                && !verifType.startsWith("CONSENT_")) {
            String action = "BANK_ACCOUNT_ADDED".equals(verifType) ? "BANK_ACCOUNT_CHANGED"
                    : "USER".equals(subjectType) ? ("FAILED".equals(newStatus) ? "KYC_FAILED" : "KYC_UPDATED")
                    : ("FAILED".equals(newStatus) ? "KYB_FAILED" : "KYB_UPDATED");
            auditService.logEvent(actorId, action, subjectType, subjectId.toString(),
                    "Verification status changed to " + newStatus + "; evidence=" + event.getId());
        }
    }

    private KycResponse mapKycToResponse(KycProfile p) {
        return new KycResponse(
                p.getId(), p.getUserId(), p.getFullName(), p.getPanNumberMasked(),
                p.getStatus(), p.getProvider(), p.getVerificationReference(),
                p.getPanStatus(), p.getIdentityStatus(), p.getNameMatchStatus(),
                p.getNameMatchScore(), p.getRejectionReason(),
                p.getVerifiedAt() != null ? p.getVerifiedAt().toString() : null,
                verificationProvider.isSandbox()
        );
    }

    private KybResponse mapKybToResponse(KybProfile p, Organization org) {
        String legalName = org != null ? org.getLegalName() : "";
        return new KybResponse(
                p.getId(), p.getOrganizationId(), legalName, p.getTradeName(),
                p.getBusinessPanMasked(), p.getGstin(), p.getCinOrLlpin(),
                p.getUdyamNumber(), p.getStatus(), p.getProvider(),
                p.getVerificationReference(), p.getPanStatus(), p.getGstStatus(),
                p.getRegistrationStatus(), p.getNameMatchStatus(), p.getNameMatchScore(),
                p.getRejectionReason(), p.getVerifiedAt() != null ? p.getVerifiedAt().toString() : null,
                verificationProvider.isSandbox()
        );
    }

    private BankAccountResponse mapBankToResponse(OrganizationBankAccount a) {
        return new BankAccountResponse(
                a.getId(), a.getOrganizationId(), a.getAccountHolderName(),
                a.getAccountNumberMasked(), a.getIfscCode(), a.getBankName(),
                a.getBranchName(), a.getAccountType(), a.isPrimary(),
                a.getVerificationStatus(), a.getVerificationReference(),
                a.getNameMatchScore(), a.getNameMatchStatus(),
                a.getRegisteredNameReturned(),
                a.getVerifiedAt() != null ? a.getVerifiedAt().toString() : null
        );
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 error", e);
        }
    }
}
