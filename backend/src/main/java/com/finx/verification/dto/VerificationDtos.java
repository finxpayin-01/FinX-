package com.finx.verification.dto;

import com.finx.verification.enums.VerificationStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class VerificationDtos {

    public record SubmitKycRequest(
            @NotBlank String fullName,
            @NotBlank String panNumber,
            String idDocumentType,
            String idDocumentRef
    ) {}

    public record SubmitKybRequest(
            @NotBlank String businessPan,
            @NotBlank String gstin,
            String cinOrLlpin,
            String udyamNumber
    ) {}

    public record KycResponse(
            UUID id,
            UUID userId,
            String fullName,
            String panNumberMasked,
            VerificationStatus status,
            String verificationReference,
            String verifiedAt
    ) {}

    public record KybResponse(
            UUID id,
            UUID organizationId,
            String businessPanMasked,
            String gstin,
            String cinOrLlpin,
            String udyamNumber,
            VerificationStatus status,
            String verificationReference,
            String verifiedAt
    ) {}
}
