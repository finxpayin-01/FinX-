package com.finx.verification.controller;

import com.finx.common.response.ApiResponse;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.dto.VerificationDtos.*;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Verification", description = "Individual KYC and Business KYB Verification Layer")
@SecurityRequirement(name = "Bearer Authentication")
public class VerificationController {

    private final VerificationService verificationService;
    private final VerificationEligibilityService eligibilityService;

    public VerificationController(VerificationService verificationService,
                                  VerificationEligibilityService eligibilityService) {
        this.verificationService = verificationService;
        this.eligibilityService = eligibilityService;
    }

    // ==========================================
    // CONSENT
    // ==========================================
    @PostMapping("/kyc/consent")
    @Operation(summary = "Capture user consent", description = "Records timestamped consent before running external checks")
    public ResponseEntity<ApiResponse<ConsentResponse>> recordConsent(
            @Valid @RequestBody RecordConsentRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser,
            HttpServletRequest servletRequest) {
        String ip = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader("User-Agent");
        ConsentResponse res = verificationService.recordConsent(request, currentUser, ip, userAgent);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Consent recorded", res));
    }

    // ==========================================
    // INDIVIDUAL KYC
    // ==========================================
    @PostMapping("/kyc/start")
    @Operation(summary = "Initialize individual KYC")
    public ResponseEntity<ApiResponse<KycResponse>> startKyc(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KycResponse res = verificationService.startKyc(currentUser);
        return ResponseEntity.ok(ApiResponse.success("KYC started", res));
    }

    @GetMapping("/kyc/status")
    @Operation(summary = "Get user KYC status")
    public ResponseEntity<ApiResponse<KycResponse>> getKycStatus(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KycResponse res = verificationService.getKycStatus(currentUser);
        return ResponseEntity.ok(ApiResponse.success(res));
    }

    // Backward-compatible alias for GET /api/v1/kyc
    @GetMapping("/kyc")
    @Operation(summary = "Get user KYC status (legacy alias)")
    public ResponseEntity<ApiResponse<KycResponse>> getKycStatusLegacy(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return getKycStatus(currentUser);
    }

    @PostMapping("/kyc/pan/verify")
    @Operation(summary = "Verify individual PAN against authoritative provider")
    public ResponseEntity<ApiResponse<VerifyPanResponse>> verifyPan(
            @Valid @RequestBody VerifyPanRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser,
            HttpServletRequest servletRequest) {
        String ip = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader("User-Agent");
        VerifyPanResponse res = verificationService.verifyIndividualPan(request, currentUser, ip, userAgent);
        return ResponseEntity.ok(ApiResponse.success(res.message(), res));
    }

    @PostMapping("/kyc/identity/start")
    @Operation(summary = "Start DigiLocker / eKYC identity session")
    public ResponseEntity<ApiResponse<IdentitySessionResponse>> startIdentitySession(
            @Valid @RequestBody StartIdentitySessionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        IdentitySessionResponse res = verificationService.createIdentitySession(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Identity session created", res));
    }

    @GetMapping("/kyc/identity/{sessionId}/status")
    @Operation(summary = "Get status of DigiLocker identity session")
    public ResponseEntity<ApiResponse<KycResponse>> getIdentityStatus(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KycResponse res = verificationService.checkIdentityStatus(sessionId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(res));
    }

    // ==========================================
    // CORPORATE KYB
    // ==========================================
    @PostMapping("/kyb/{orgId}/start")
    @Operation(summary = "Start corporate KYB for organization")
    public ResponseEntity<ApiResponse<KybResponse>> startKyb(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse res = verificationService.startKyb(orgId, currentUser);
        return ResponseEntity.ok(ApiResponse.success("KYB started", res));
    }

    @GetMapping("/kyb/{orgId}/status")
    @Operation(summary = "Get corporate KYB status")
    public ResponseEntity<ApiResponse<KybResponse>> getKybStatus(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse res = verificationService.getKybStatus(orgId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(res));
    }

    // Backward-compatible alias for GET /api/v1/kyb/{orgId}
    @GetMapping("/kyb/{orgId}")
    @Operation(summary = "Get corporate KYB status (legacy alias)")
    public ResponseEntity<ApiResponse<KybResponse>> getKybStatusLegacy(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return getKybStatus(orgId, currentUser);
    }

    @PostMapping("/kyb/{orgId}/pan/verify")
    @Operation(summary = "Verify corporate PAN")
    public ResponseEntity<ApiResponse<KybResponse>> verifyBusinessPan(
            @PathVariable UUID orgId,
            @Valid @RequestBody VerifyBusinessPanRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse res = verificationService.verifyBusinessPan(orgId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Business PAN verified", res));
    }

    @PostMapping("/kyb/{orgId}/gstin/verify")
    @Operation(summary = "Verify corporate GSTIN")
    public ResponseEntity<ApiResponse<KybResponse>> verifyGstin(
            @PathVariable UUID orgId,
            @Valid @RequestBody VerifyGstinRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse res = verificationService.verifyGstin(orgId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("GSTIN verified", res));
    }

    @PostMapping("/kyb/{orgId}/registration/verify")
    @Operation(summary = "Verify corporate CIN/LLPIN/Udyam")
    public ResponseEntity<ApiResponse<KybResponse>> verifyRegistration(
            @PathVariable UUID orgId,
            @Valid @RequestBody VerifyRegistrationRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse res = verificationService.verifyRegistration(orgId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Registration status updated", res));
    }

    // Legacy submission endpoint for backward compatibility
    @PostMapping("/kyb/{orgId}")
    @Operation(summary = "Submit legacy business KYB bundle")
    public ResponseEntity<ApiResponse<KybResponse>> submitKybLegacy(
            @PathVariable UUID orgId,
            @Valid @RequestBody SubmitKybRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse res = verificationService.submitKybLegacy(orgId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Corporate KYB processed", res));
    }

    // ==========================================
    // OVERALL VERIFICATION & ELIGIBILITY STATUS
    // ==========================================
    @GetMapping("/organizations/{id}/verification")
    @Operation(summary = "Get comprehensive FinX verification badge and checklist")
    public ResponseEntity<ApiResponse<FinxOrganizationVerificationResponse>> getOrganizationVerification(
            @PathVariable UUID id) {
        FinxOrganizationVerificationResponse res = eligibilityService.getOrganizationVerification(id);
        return ResponseEntity.ok(ApiResponse.success(res));
    }

    // ==========================================
    // AUTHORIZED REPRESENTATIVES
    // ==========================================
    @PostMapping("/organizations/{id}/representatives")
    @Operation(summary = "Add authorized representative/signatory to company")
    public ResponseEntity<ApiResponse<RepresentativeResponse>> addRepresentative(
            @PathVariable UUID id,
            @Valid @RequestBody AddRepresentativeRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        RepresentativeResponse res = verificationService.addRepresentative(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Representative added", res));
    }

    @GetMapping("/organizations/{id}/representatives")
    @Operation(summary = "List company authorized representatives")
    public ResponseEntity<ApiResponse<List<RepresentativeResponse>>> getRepresentatives(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<RepresentativeResponse> res = verificationService.getRepresentatives(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success(res));
    }

    // ==========================================
    // BENEFICIAL OWNERS
    // ==========================================
    @PostMapping("/organizations/{id}/beneficial-owners")
    @Operation(summary = "Record beneficial owner information")
    public ResponseEntity<ApiResponse<BeneficialOwnerResponse>> addBeneficialOwner(
            @PathVariable UUID id,
            @Valid @RequestBody AddBeneficialOwnerRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        BeneficialOwnerResponse res = verificationService.addBeneficialOwner(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Beneficial owner added", res));
    }

    @GetMapping("/organizations/{id}/beneficial-owners")
    @Operation(summary = "List company beneficial owners")
    public ResponseEntity<ApiResponse<List<BeneficialOwnerResponse>>> getBeneficialOwners(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<BeneficialOwnerResponse> res = verificationService.getBeneficialOwners(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success(res));
    }
}
