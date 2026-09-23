package com.finx.org.dto;

import com.finx.org.enums.OrgEnums.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class OrgDtos {

    public record CreateOrgRequest(
            @NotBlank String name,
            @NotBlank String legalName,
            @NotNull EntityType entityType,
            String registrationNumber,
            String taxId,
            String gstin,
            String registeredAddress
    ) {}

    public record AddMemberRequest(
            @NotNull UUID userId,
            @NotNull OrgRole role
    ) {}

    public record AddBankAccountRequest(
            @NotBlank String accountHolderName,
            @NotBlank String accountNumber,
            @NotBlank String ifscCode,
            @NotBlank String bankName,
            String branchName,
            boolean isPrimary
    ) {}

    public record OrganizationResponse(
            UUID id,
            String name,
            String legalName,
            EntityType entityType,
            String registrationNumber,
            String taxId,
            String gstin,
            String registeredAddress,
            OrgVerificationStatus verificationStatus,
            UUID createdBy,
            String createdAt
    ) {}

    public record MemberResponse(
            UUID id,
            UUID organizationId,
            UUID userId,
            OrgRole role,
            MemberStatus status,
            String joinedAt
    ) {}

    public record BankAccountResponse(
            UUID id,
            UUID organizationId,
            String accountHolderName,
            String accountNumberMasked,
            String ifscCode,
            String bankName,
            String branchName,
            boolean isPrimary,
            BankVerificationStatus verificationStatus,
            String verificationReference,
            String verifiedAt
    ) {}

    public record VerifiedBusinessProfile(
            UUID organizationId,
            String legalName,
            EntityType entityType,
            boolean isKybVerified,
            boolean isBankVerified,
            boolean isSignatoryVerified,
            int completedDeals,
            int completedMilestones,
            int repeatCounterparties,
            int disputeCount,
            BigDecimal totalVolumeSettled
    ) {}
}
