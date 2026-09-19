package com.finx.dispute.controller;

import com.finx.common.response.ApiResponse;
import com.finx.dispute.dto.request.CreateDisputeRequest;
import com.finx.dispute.dto.response.DisputeResponse;
import com.finx.dispute.service.DisputeService;
import com.finx.security.service.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/disputes")
@Tag(name = "Disputes", description = "Dispute resolution and management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class DisputeController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.finx.blockchain.BlockchainEscrowRail chainAccess;

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }
    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all disputes", description = "Retrieves all system disputes for administrative oversight")
    public ResponseEntity<ApiResponse<List<DisputeResponse>>> getAllDisputes() {
        List<DisputeResponse> disputes = disputeService.getAllDisputes();
        return ResponseEntity.ok(ApiResponse.success(disputes));
    }

    @PostMapping
    @Operation(summary = "Raise a dispute", description = "Allows a participant or admin to raise a dispute on a deal")
    public ResponseEntity<ApiResponse<DisputeResponse>> createDispute(
            @Valid @RequestBody CreateDisputeRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        DisputeResponse dispute = disputeService.createDispute(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Dispute raised successfully", dispute));
    }

    @GetMapping("/deal/{dealId}")
    @Operation(summary = "Get disputes for deal", description = "Retrieves disputes for a specific deal")
    public ResponseEntity<ApiResponse<List<DisputeResponse>>> getDisputesForDeal(
            @PathVariable UUID dealId, @AuthenticationPrincipal UserPrincipal actor) {
        chainAccess.access(dealId, actor);
        List<DisputeResponse> disputes = disputeService.getDisputesForDeal(dealId);
        return ResponseEntity.ok(ApiResponse.success(disputes));
    }

    @PatchMapping("/{id}/resolve")
    @Operation(summary = "Resolve dispute", description = "Admin resolution of an active dispute")
    public ResponseEntity<ApiResponse<DisputeResponse>> resolveDispute(
            @PathVariable UUID id,
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        String resolutionNotes = payload.get("resolutionNotes");
        DisputeResponse dispute = disputeService.resolveDispute(id, resolutionNotes, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Dispute resolved successfully", dispute));
    }
}
