package com.finx.verification.provider.model;

import java.time.LocalDate;

public class VerificationProviderModels {

    public record PanVerificationRequest(
            String pan,
            String consentId,
            String fullName,
            LocalDate dateOfBirth
    ) {}

    public record PanVerificationResult(
            boolean valid,
            String pan,
            String registeredName,
            String panStatus,
            String category,
            double nameMatchScore,
            String nameMatchStatus,
            String providerReference,
            String message
    ) {}

    public record GstVerificationRequest(
            String gstin,
            String consentId,
            String legalName
    ) {}

    public record GstVerificationResult(
            boolean valid,
            String gstin,
            String legalName,
            String tradeName,
            String status,
            LocalDate registrationDate,
            String constitutionOfBusiness,
            String address,
            double nameMatchScore,
            String nameMatchStatus,
            String providerReference,
            String message
    ) {}

    public record CinVerificationRequest(
            String cinOrLlpin,
            String consentId,
            String companyName
    ) {}

    public record CinVerificationResult(
            boolean valid,
            String cin,
            String companyName,
            String companyStatus,
            LocalDate dateOfIncorporation,
            String companyCategory,
            String registeredAddress,
            double nameMatchScore,
            String nameMatchStatus,
            String providerReference,
            String message
    ) {}

    public record BankVerificationRequest(
            String accountNumber,
            String ifsc,
            String consentId,
            String accountHolderName
    ) {}

    public record BankVerificationResult(
            boolean valid,
            String accountNumberMasked,
            String ifsc,
            String registeredName,
            String bankName,
            String branch,
            double nameMatchScore,
            String nameMatchStatus,
            String providerReference,
            String message
    ) {}

    public record IdentitySessionRequest(
            String userId,
            String consentId,
            String redirectUrl,
            String callbackUrl
    ) {}

    public record IdentitySessionResult(
            String sessionId,
            String verificationUrl,
            String providerReference,
            String status
    ) {}

    public record IdentityStatusResult(
            String sessionId,
            String status,
            String fullName,
            String maskedAadhaar,
            String dob,
            String address,
            double nameMatchScore,
            String providerReference,
            String message
    ) {}
}
