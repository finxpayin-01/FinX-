package com.finx.escrow.controller;

import com.finx.common.response.ApiResponse;
import com.finx.escrow.dto.request.ReleaseEscrowRequest;
import com.finx.escrow.dto.response.EscrowAccountResponse;
import com.finx.escrow.dto.response.EscrowLedgerResponse;
import com.finx.escrow.service.EscrowService;
import com.finx.security.service.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/escrow")
@Tag(name = "Escrow", description = "Fiat escrow account and ledger transaction endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class EscrowController {

    private final EscrowService escrowService;

    public EscrowController(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @GetMapping("/deal/{dealId}")
    @Operation(summary = "Get escrow account for deal", description = "Retrieves current balance and status of deal escrow account")
    public ResponseEntity<ApiResponse<EscrowAccountResponse>> getEscrowAccount(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        EscrowAccountResponse response = escrowService.getEscrowAccountForDeal(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/ledger/deal/{dealId}")
    @Operation(summary = "Get escrow ledger for deal", description = "Retrieves immutable audit trail of FUND/RELEASE transactions for deal")
    public ResponseEntity<ApiResponse<List<EscrowLedgerResponse>>> getEscrowLedger(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<EscrowLedgerResponse> ledger = escrowService.getEscrowLedgerForDeal(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(ledger));
    }

    @PostMapping("/{milestoneId}/release")
    @Operation(summary = "Release escrow funds", description = "Releases approved milestone escrow funds to the vendor")
    public ResponseEntity<ApiResponse<EscrowLedgerResponse>> releaseEscrow(
            @PathVariable UUID milestoneId,
            @RequestBody(required = false) ReleaseEscrowRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        throw new org.springframework.security.access.AccessDeniedException(
                "Direct escrow release is disabled pending settlement orchestration");
    }

    @GetMapping
    @Operation(summary = "Get all escrow accounts (Admin)", description = "Admin view of all platform escrow accounts")
    public ResponseEntity<ApiResponse<List<EscrowAccountResponse>>> getAllEscrowAccounts(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<EscrowAccountResponse> accounts = escrowService.getAllEscrowAccounts(currentUser);
        return ResponseEntity.ok(ApiResponse.success(accounts));
    }
}
