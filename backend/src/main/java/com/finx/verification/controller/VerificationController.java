package com.finx.verification.controller;

import com.finx.common.response.ApiResponse;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.dto.VerificationDtos.*;
import com.finx.verification.service.VerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Verification", description = "Individual KYC and Business KYB Verification")
@SecurityRequirement(name = "Bearer Authentication")
public class VerificationController {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping("/kyc")
    @Operation(summary = "Submit individual KYC", description = "Verifies PAN and authorized signatory credentials")
    public ResponseEntity<ApiResponse<KycResponse>> submitKyc(
            @Valid @RequestBody SubmitKycRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KycResponse response = verificationService.submitKyc(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Individual KYC verified successfully", response));
    }

    @GetMapping("/kyc")
    @Operation(summary = "Get user KYC status")
    public ResponseEntity<ApiResponse<KycResponse>> getKycStatus(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KycResponse response = verificationService.getKycStatus(currentUser);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/kyb/{orgId}")
    @Operation(summary = "Submit business KYB", description = "Verifies business PAN, GSTIN, CIN, and authorized signatories")
    public ResponseEntity<ApiResponse<KybResponse>> submitKyb(
            @PathVariable UUID orgId,
            @Valid @RequestBody SubmitKybRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse response = verificationService.submitKyb(orgId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Corporate KYB verified successfully", response));
    }

    @GetMapping("/kyb/{orgId}")
    @Operation(summary = "Get organization KYB status")
    public ResponseEntity<ApiResponse<KybResponse>> getKybStatus(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KybResponse response = verificationService.getKybStatus(orgId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
