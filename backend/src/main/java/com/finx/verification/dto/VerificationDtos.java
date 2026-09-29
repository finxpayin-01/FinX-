package com.finx.verification.dto;

import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.verification.enums.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class VerificationDtos {

    // Consent
    public record RecordConsentRequest(
            @NotNull ConsentType verificationType,
            UUID organizationId,
            @NotBlank String purpose,
            String consentVersion
    ) {}

    public record ConsentResponse(
            UUID id,
            UUID userId,
            UUID organizationId,
            ConsentType verificationType,
            String consentVersion,
            String purpose,
            String consentedAt
    ) {}

    // KYC
    public record VerifyPanRequest(
            @NotBlank String panNumber,
            @NotBlank String fullName,
            LocalDate dateOfBirth
    ) {}

    public record VerifyPanResponse(
            boolean valid,
            String panMasked,
            String registeredName,
            String panStatus,
            String category,
            Double nameMatchScore,
            String nameMatchStatus,
            String providerReference,
            String message
    ) {}

    public record StartIdentitySessionRequest(
            String redirectUrl,
            String callbackUrl
    ) {}

    public record IdentitySessionResponse(
            String sessionId,
            String verificationUrl,
            String providerReference,
            String status
    ) {}

    public record KycResponse(
            UUID id,
            UUID userId,
            String fullName,
            String panNumberMasked,
            VerificationStatus status,
            String provider,
            String providerReference,
            VerificationStatus panStatus,
            VerificationStatus identityStatus,
            String nameMatchStatus,
            Double nameMatchScore,
            String rejectionReason,
            String verifiedAt,
            boolean isSandbox
    ) {}

    // KYB
    public record VerifyBusinessPanRequest(
            @NotBlank String businessPan
    ) {}

    public record VerifyGstinRequest(
            @NotBlank String gstin
    ) {}

    public record VerifyRegistrationRequest(
            String cinOrLlpin,
            String udyamNumber
    ) {}

    public record SubmitKybRequest(
            @NotBlank String businessPan,
            @NotBlank String gstin,
            String cinOrLlpin,
            String udyamNumber
    ) {}

    public record KybResponse(
            UUID id,
            UUID organizationId,
            String legalName,
            String tradeName,
            String businessPanMasked,
            String gstin,
            String cinOrLlpin,
            String udyamNumber,
            VerificationStatus status,
            String provider,
            String providerReference,
            VerificationStatus panStatus,
            VerificationStatus gstStatus,
            VerificationStatus registrationStatus,
            String nameMatchStatus,
            Double nameMatchScore,
            String rejectionReason,
            String verifiedAt,
            boolean isSandbox
    ) {}

    // Representatives
    public record AddRepresentativeRequest(
            @NotNull UUID userId,
            @NotNull AuthorityRelationshipType relationshipType,
            String documentRef,
            String notes
    ) {}

    public record RepresentativeResponse(
            UUID id,
            UUID organizationId,
            UUID userId,
            String userName,
            String userEmail,
            AuthorityRelationshipType relationshipType,
            AuthorityStatus authorityStatus,
            boolean kycVerified,
            String verifiedAt
    ) {}

    // Beneficial Owners
    public record AddBeneficialOwnerRequest(
            @NotBlank String personName,
            @NotNull BigDecimal ownershipPercentage,
            @NotBlank String relationshipType,
            String pan
    ) {}

    public record BeneficialOwnerResponse(
            UUID id,
            UUID organizationId,
            String personName,
            BigDecimal ownershipPercentage,
            String relationshipType,
            String panMasked,
            String verificationStatus
    ) {}

    // Bank Accounts
    public record AddBankAccountRequest(
            @NotBlank String accountHolderName,
            @NotBlank String accountNumber,
            @NotBlank String ifscCode,
            @NotBlank String bankName,
            String branchName,
            String accountType,
            boolean isPrimary
    ) {}

    public record BankAccountResponse(
            UUID id,
            UUID organizationId,
            String accountHolderName,
            String accountNumberMasked,
            String ifscCode,
            String bankName,
            String branchName,
            String accountType,
            boolean isPrimary,
            BankVerificationStatus verificationStatus,
            String verificationReference,
            Double nameMatchScore,
            String nameMatchStatus,
            String registeredNameReturned,
            String verifiedAt
    ) {}

    // Overall Organization Verification & Eligibility
    public record FinxCheckItem(
            String code,
            String name,
            String status, // VERIFIED, PENDING, FAILED, REVIEW, NOT_REQUIRED
            String detail,
            boolean required
    ) {}

    public record FinxOrganizationVerificationResponse(
            UUID organizationId,
            String legalName,
            String entityType,
            VerificationStatus overallStatus,
            boolean kybVerified,
            boolean canActivateDeal,
            boolean canSignAgreement,
            boolean canFundRealTransaction,
            boolean canReceiveRealSettlement,
            String moneyMovementStatus,
            List<FinxCheckItem> checks,
            String badge,
            boolean isSandbox
    ) {}

    // Admin Review
    public record AdminReviewRequest(
            @NotBlank String action, // APPROVE_REVIEW, REJECT, REQUEST_INFORMATION, REVERIFY
            @NotBlank String reason,
            String notes
    ) {}

    public record VerificationEventDto(
            UUID id,
            String subjectType,
            UUID subjectId,
            String verificationType,
            String provider,
            String oldStatus,
            String newStatus,
            String reason,
            String actorId,
            String timestamp
    ) {}

    public record AdminVerificationSummaryResponse(
            List<KycResponse> pendingKyc,
            List<KybResponse> pendingKyb,
            List<KybResponse> manualReviews,
            List<BankAccountResponse> bankMismatches,
            List<VerificationEventDto> recentEvents
    ) {}
}
