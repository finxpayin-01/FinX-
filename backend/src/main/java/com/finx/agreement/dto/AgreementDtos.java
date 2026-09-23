package com.finx.agreement.dto;

import com.finx.agreement.enums.AgreementStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class AgreementDtos {

    public record CreateAgreementDraftRequest(
            @NotBlank String title,
            @NotBlank String termsContent
    ) {}

    public record SignAgreementRequest(
            int version
    ) {}

    public record AgreementResponse(
            UUID id,
            UUID dealId,
            int version,
            String title,
            String termsContent,
            String documentSha256,
            AgreementStatus status,
            String buyerAcceptedAt,
            UUID buyerAcceptedBy,
            String sellerAcceptedAt,
            UUID sellerAcceptedBy,
            String effectiveDate,
            String createdAt
    ) {}

    public record AgreementAuditEventResponse(
            UUID id,
            UUID agreementId,
            String action,
            UUID actorId,
            String ipAddress,
            String timestamp
    ) {}
}
