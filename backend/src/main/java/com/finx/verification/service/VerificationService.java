package com.finx.verification.service;

import com.finx.audit.service.AuditService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.org.entity.Organization;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.enums.OrgEnums.OrgVerificationStatus;
import com.finx.org.repository.OrganizationRepository;
import com.finx.org.service.OrganizationService;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.dto.VerificationDtos.*;
import com.finx.verification.entity.KybProfile;
import com.finx.verification.entity.KycProfile;
import com.finx.verification.enums.VerificationStatus;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final KycProfileRepository kycRepository;
    private final KybProfileRepository kybRepository;
    private final OrganizationRepository orgRepository;
    private final OrganizationService orgService;
    private final AuditService auditService;

    public VerificationService(KycProfileRepository kycRepository,
                               KybProfileRepository kybRepository,
                               OrganizationRepository orgRepository,
                               OrganizationService orgService,
                               AuditService auditService) {
        this.kycRepository = kycRepository;
        this.kybRepository = kybRepository;
        this.orgRepository = orgRepository;
        this.orgService = orgService;
        this.auditService = auditService;
    }

    @Transactional
    public KycResponse submitKyc(SubmitKycRequest request, UserPrincipal currentUser) {
        String pan = request.panNumber().trim().toUpperCase();
        if (pan.length() < 10) {
            throw new BadRequestException("Invalid PAN number format");
        }

        String maskedPan = maskPan(pan);

        KycProfile profile = kycRepository.findByUserId(currentUser.getId())
                .orElse(new KycProfile(currentUser.getId(), request.fullName().trim(), maskedPan));

        profile.setFullName(request.fullName().trim());
        profile.setPanNumberMasked(maskedPan);
        profile.setIdDocumentType(request.idDocumentType());
        profile.setIdDocumentRef(request.idDocumentRef());

        // In sandbox mode, immediately verify
        String ref = "KYC-SANDBOX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        profile.setStatus(VerificationStatus.VERIFIED);
        profile.setVerificationReference(ref);
        profile.setVerifiedAt(Instant.now());

        KycProfile saved = kycRepository.save(profile);

        auditService.logEvent(
                currentUser.getId(),
                "KYC_COMPLETED",
                "KYC",
                saved.getId().toString(),
                "Completed individual KYC for " + currentUser.getEmail() + " ref: " + ref
        );

        log.info("Individual KYC verified: userId={} ref={}", currentUser.getId(), ref);
        return mapKycToResponse(saved);
    }

    @Transactional(readOnly = true)
    public KycResponse getKycStatus(UserPrincipal currentUser) {
        return kycRepository.findByUserId(currentUser.getId())
                .map(this::mapKycToResponse)
                .orElse(new KycResponse(null, currentUser.getId(), "", "", VerificationStatus.NOT_STARTED, null, null));
    }

    @Transactional
    public KybResponse submitKyb(UUID orgId, SubmitKybRequest request, UserPrincipal currentUser) {
        orgService.validateOrgRole(orgId, currentUser, Set.of(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.AUTHORIZED_SIGNATORY));

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", orgId));

        String pan = request.businessPan().trim().toUpperCase();
        String maskedPan = maskPan(pan);

        KybProfile profile = kybRepository.findByOrganizationId(orgId)
                .orElse(new KybProfile(orgId, maskedPan, request.gstin().trim().toUpperCase(), request.cinOrLlpin(), request.udyamNumber()));

        profile.setBusinessPanMasked(maskedPan);
        profile.setGstin(request.gstin().trim().toUpperCase());
        profile.setCinOrLlpin(request.cinOrLlpin());
        profile.setUdyamNumber(request.udyamNumber());

        // In sandbox mode, auto-verify KYB
        String ref = "KYB-SANDBOX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        profile.setStatus(VerificationStatus.VERIFIED);
        profile.setVerificationReference(ref);
        profile.setVerifiedAt(Instant.now());

        KybProfile saved = kybRepository.save(profile);

        // Update organization status
        org.setVerificationStatus(OrgVerificationStatus.VERIFIED);
        orgRepository.save(org);

        auditService.logEvent(
                currentUser.getId(),
                "KYB_COMPLETED",
                "KYB",
                saved.getId().toString(),
                "Completed corporate KYB for org " + org.getName() + " ref: " + ref
        );

        log.info("Corporate KYB verified: orgId={} ref={}", orgId, ref);
        return mapKybToResponse(saved);
    }

    @Transactional(readOnly = true)
    public KybResponse getKybStatus(UUID orgId, UserPrincipal currentUser) {
        orgService.validateOrgMembership(orgId, currentUser);
        return kybRepository.findByOrganizationId(orgId)
                .map(this::mapKybToResponse)
                .orElse(new KybResponse(null, orgId, "", "", "", "", VerificationStatus.NOT_STARTED, null, null));
    }

    private KycResponse mapKycToResponse(KycProfile p) {
        return new KycResponse(
                p.getId(),
                p.getUserId(),
                p.getFullName(),
                p.getPanNumberMasked(),
                p.getStatus(),
                p.getVerificationReference(),
                p.getVerifiedAt() != null ? p.getVerifiedAt().toString() : null
        );
    }

    private KybResponse mapKybToResponse(KybProfile p) {
        return new KybResponse(
                p.getId(),
                p.getOrganizationId(),
                p.getBusinessPanMasked(),
                p.getGstin(),
                p.getCinOrLlpin(),
                p.getUdyamNumber(),
                p.getStatus(),
                p.getVerificationReference(),
                p.getVerifiedAt() != null ? p.getVerifiedAt().toString() : null
        );
    }

    private String maskPan(String raw) {
        if (raw.length() <= 4) return raw;
        return "*".repeat(raw.length() - 4) + raw.substring(raw.length() - 4);
    }
}
