package com.finx.agreement.service;

import com.finx.agreement.dto.AgreementDtos.*;
import com.finx.agreement.entity.AgreementAuditEvent;
import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.AgreementAuditEventRepository;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.security.service.UserPrincipal;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.repository.OrganizationMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AgreementService {

    private static final Logger log = LoggerFactory.getLogger(AgreementService.class);

    private final DealAgreementRepository agreementRepository;
    private final AgreementAuditEventRepository auditEventRepository;
    private final DealRepository dealRepository;
    private final AuditService auditService;
    private final OrganizationMemberRepository memberRepository;

    public AgreementService(DealAgreementRepository agreementRepository,
                            AgreementAuditEventRepository auditEventRepository,
                            DealRepository dealRepository,
                            AuditService auditService) {
        this(agreementRepository, auditEventRepository, dealRepository, auditService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgreementService(DealAgreementRepository agreementRepository,
                            AgreementAuditEventRepository auditEventRepository,
                            DealRepository dealRepository,
                            AuditService auditService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) OrganizationMemberRepository memberRepository) {
        this.agreementRepository = agreementRepository;
        this.auditEventRepository = auditEventRepository;
        this.dealRepository = dealRepository;
        this.auditService = auditService;
        this.memberRepository = memberRepository;
    }

    @Transactional
    public AgreementResponse createOrUpdateDraft(UUID dealId, CreateAgreementDraftRequest request, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        validateDealParty(deal, currentUser);

        String terms = request.termsContent().trim();
        String sha256 = calculateSha256(terms);

        Optional<DealAgreement> latestOpt = agreementRepository.findTopByDealIdOrderByVersionDesc(dealId);
        int nextVersion = 1;

        if (latestOpt.isPresent()) {
            DealAgreement latest = latestOpt.get();
            if (latest.getStatus() == AgreementStatus.SIGNED) {
                // If signed, create new version
                nextVersion = latest.getVersion() + 1;
            } else {
                // Update existing draft
                latest.setTitle(request.title().trim());
                latest.setTermsContent(terms);
                latest.setDocumentSha256(sha256);
                latest.setStatus(AgreementStatus.DRAFT);
                latest.setBuyerAcceptedAt(null);
                latest.setBuyerAcceptedBy(null);
                latest.setSellerAcceptedAt(null);
                latest.setSellerAcceptedBy(null);

                DealAgreement saved = agreementRepository.save(latest);
                recordAudit(saved.getId(), "DRAFT_UPDATED", currentUser.getId(), null, null, "Updated draft terms");
                return mapToResponse(saved);
            }
        }

        DealAgreement agreement = new DealAgreement(dealId, nextVersion, request.title().trim(), terms, sha256);
        DealAgreement saved = agreementRepository.save(agreement);

        recordAudit(saved.getId(), "DRAFT_CREATED", currentUser.getId(), null, null, "Created agreement version " + nextVersion);

        auditService.logEvent(
                currentUser.getId(),
                "AGREEMENT_DRAFT_CREATED",
                "AGREEMENT",
                saved.getId().toString(),
                "Created agreement v" + nextVersion + " for deal " + dealId + " sha256: " + sha256
        );

        log.info("Agreement created: id={} dealId={} v={}", saved.getId(), dealId, nextVersion);
        return mapToResponse(saved);
    }

    @Transactional
    public AgreementResponse signAgreement(UUID dealId, int version, UserPrincipal currentUser, String ipAddress, String userAgent) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        DealAgreement agreement = agreementRepository.findByDealIdAndVersion(dealId, version)
                .orElseThrow(() -> new ResourceNotFoundException("DealAgreement", "version", version));

        if (agreement.getStatus() == AgreementStatus.SIGNED) {
            throw new BadRequestException("This agreement has already been fully signed");
        }

        boolean isBuyerParty = deal.getBuyerId().equals(currentUser.getId());
        boolean isSellerParty = deal.getSellerId() != null && deal.getSellerId().equals(currentUser.getId());
        boolean isBuyer = false;
        boolean isSeller = false;

        if (deal.getBuyerOrganizationId() != null) {
            if (memberRepository != null) {
                var memberOpt = memberRepository.findByOrganizationIdAndUserId(deal.getBuyerOrganizationId(), currentUser.getId());
                if (memberOpt.isPresent()) {
                    var m = memberOpt.get();
                    if (m.getStatus() != MemberStatus.ACTIVE) {
                        throw new UnauthorizedException("Signer organization membership is not active (status: " + m.getStatus() + ")");
                    }
                    if (m.getRole() != OrgRole.OWNER && m.getRole() != OrgRole.ADMIN && m.getRole() != OrgRole.AUTHORIZED_SIGNATORY) {
                        throw new UnauthorizedException("Signer role (" + m.getRole() + ") is not authorized to sign agreements");
                    }
                    isBuyer = true;
                } else if (isBuyerParty) {
                    throw new UnauthorizedException("Signer is not an active member of buyer organization " + deal.getBuyerOrganizationId());
                }
            } else if (isBuyerParty) {
                isBuyer = true;
            }
        } else if (isBuyerParty) {
            isBuyer = true;
        }

        if (deal.getSellerOrganizationId() != null) {
            if (memberRepository != null) {
                var memberOpt = memberRepository.findByOrganizationIdAndUserId(deal.getSellerOrganizationId(), currentUser.getId());
                if (memberOpt.isPresent()) {
                    var m = memberOpt.get();
                    if (m.getStatus() != MemberStatus.ACTIVE) {
                        throw new UnauthorizedException("Signer organization membership is not active (status: " + m.getStatus() + ")");
                    }
                    if (m.getRole() != OrgRole.OWNER && m.getRole() != OrgRole.ADMIN && m.getRole() != OrgRole.AUTHORIZED_SIGNATORY) {
                        throw new UnauthorizedException("Signer role (" + m.getRole() + ") is not authorized to sign agreements");
                    }
                    isSeller = true;
                } else if (isSellerParty) {
                    throw new UnauthorizedException("Signer is not an active member of seller organization " + deal.getSellerOrganizationId());
                }
            } else if (isSellerParty) {
                isSeller = true;
            }
        } else if (isSellerParty) {
            isSeller = true;
        }

        if (!isBuyer && !isSeller && currentUser.getRole() != Role.ADMIN) {
            throw new UnauthorizedException("Only an authorized active buyer or seller representative can sign this agreement");
        }

        if (isBuyer) {
            agreement.setBuyerAcceptedAt(Instant.now());
            agreement.setBuyerAcceptedBy(currentUser.getId());
            agreement.setBuyerSignerIp(ipAddress != null ? ipAddress : "127.0.0.1");
            recordAudit(agreement.getId(), "BUYER_SIGNED", currentUser.getId(), ipAddress, userAgent, "Buyer digitally signed terms SHA: " + agreement.getDocumentSha256());
        }

        if (isSeller) {
            agreement.setSellerAcceptedAt(Instant.now());
            agreement.setSellerAcceptedBy(currentUser.getId());
            agreement.setSellerSignerIp(ipAddress != null ? ipAddress : "127.0.0.1");
            recordAudit(agreement.getId(), "SELLER_SIGNED", currentUser.getId(), ipAddress, userAgent, "Seller digitally signed terms SHA: " + agreement.getDocumentSha256());
        }

        // If both accepted, mark SIGNED
        if (agreement.getBuyerAcceptedAt() != null && agreement.getSellerAcceptedAt() != null) {
            agreement.setStatus(AgreementStatus.SIGNED);
            agreement.setEffectiveDate(Instant.now());

            recordAudit(agreement.getId(), "AGREEMENT_FULLY_EXECUTED", currentUser.getId(), ipAddress, userAgent, "Both parties executed agreement v" + agreement.getVersion());

            auditService.logEvent(
                    currentUser.getId(),
                    "AGREEMENT_SIGNED",
                    "AGREEMENT",
                    agreement.getId().toString(),
                    "Agreement v" + agreement.getVersion() + " for deal " + dealId + " fully executed and signed"
            );
            log.info("Agreement fully signed: dealId={} version={}", dealId, version);
        }

        DealAgreement saved = agreementRepository.save(agreement);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public AgreementResponse getLatestAgreement(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        validateDealParty(deal, currentUser);

        return agreementRepository.findTopByDealIdOrderByVersionDesc(dealId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResourceNotFoundException("DealAgreement", "dealId", dealId));
    }

    @Transactional(readOnly = true)
    public List<AgreementResponse> getAgreementVersions(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        validateDealParty(deal, currentUser);

        return agreementRepository.findByDealIdOrderByVersionDesc(dealId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public boolean isAgreementFullySigned(UUID dealId) {
        return agreementRepository.findTopByDealIdOrderByVersionDesc(dealId)
                .map(a -> a.getStatus() == AgreementStatus.SIGNED)
                .orElse(false);
    }

    private void recordAudit(UUID agreementId, String action, UUID actorId, String ip, String ua, String meta) {
        AgreementAuditEvent event = new AgreementAuditEvent(agreementId, action, actorId, ip, ua, meta);
        auditEventRepository.save(event);
    }

    private void validateDealParty(Deal deal, UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ADMIN) return;
        boolean isParty = deal.getBuyerId().equals(currentUser.getId()) ||
                (deal.getSellerId() != null && deal.getSellerId().equals(currentUser.getId()));
        if (!isParty) {
            throw new UnauthorizedException("You are not authorized to view or edit agreements for this deal");
        }
    }

    private AgreementResponse mapToResponse(DealAgreement a) {
        return new AgreementResponse(
                a.getId(),
                a.getDealId(),
                a.getVersion(),
                a.getTitle(),
                a.getTermsContent(),
                a.getDocumentSha256(),
                a.getStatus(),
                a.getBuyerAcceptedAt() != null ? a.getBuyerAcceptedAt().toString() : null,
                a.getBuyerAcceptedBy(),
                a.getSellerAcceptedAt() != null ? a.getSellerAcceptedAt().toString() : null,
                a.getSellerAcceptedBy(),
                a.getEffectiveDate() != null ? a.getEffectiveDate().toString() : null,
                a.getCreatedAt().toString()
        );
    }

    private String calculateSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 calculation failed", e);
        }
    }
}
