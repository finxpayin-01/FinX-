package com.finx.agreement.controller;

import com.finx.agreement.dto.AgreementDtos.*;
import com.finx.agreement.service.AgreementService;
import com.finx.common.response.ApiResponse;
import com.finx.security.service.UserPrincipal;
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
@RequestMapping("/api/v1/agreements")
@Tag(name = "Agreements", description = "Deal Agreement Lifecycle, Versioning, and Cryptographic Acceptance")
@SecurityRequirement(name = "Bearer Authentication")
public class AgreementController {

    private final AgreementService agreementService;

    public AgreementController(AgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @PostMapping("/{dealId}")
    @Operation(summary = "Create or update agreement draft", description = "Generates SHA-256 hash and versioned draft")
    public ResponseEntity<ApiResponse<AgreementResponse>> createOrUpdateDraft(
            @PathVariable UUID dealId,
            @Valid @RequestBody CreateAgreementDraftRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        AgreementResponse response = agreementService.createOrUpdateDraft(dealId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Agreement draft saved", response));
    }

    @GetMapping("/{dealId}")
    @Operation(summary = "Get latest agreement for deal")
    public ResponseEntity<ApiResponse<AgreementResponse>> getLatestAgreement(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        AgreementResponse response = agreementService.getLatestAgreement(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{dealId}/sign")
    @Operation(summary = "Digitally sign deal agreement", description = "Validates buyer/seller signature and locks signed version")
    public ResponseEntity<ApiResponse<AgreementResponse>> signAgreement(
            @PathVariable UUID dealId,
            @Valid @RequestBody SignAgreementRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser,
            HttpServletRequest servletRequest) {
        String ip = servletRequest.getRemoteAddr();
        String ua = servletRequest.getHeader("User-Agent");
        AgreementResponse response = agreementService.signAgreement(dealId, request.version(), currentUser, ip, ua);
        return ResponseEntity.ok(ApiResponse.success("Agreement successfully signed", response));
    }

    @GetMapping("/{dealId}/versions")
    @Operation(summary = "Get all agreement versions for deal")
    public ResponseEntity<ApiResponse<List<AgreementResponse>>> getAgreementVersions(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<AgreementResponse> versions = agreementService.getAgreementVersions(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(versions));
    }
}
