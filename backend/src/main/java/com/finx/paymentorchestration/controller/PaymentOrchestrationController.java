package com.finx.paymentorchestration.controller;

import com.finx.common.response.ApiResponse;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.*;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.security.service.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payment Orchestration & Settlement", description = "Bank payment instructions, trusted confirmation, and gated settlement payouts")
@SecurityRequirement(name = "Bearer Authentication")
public class PaymentOrchestrationController {

    private final PaymentOrchestrationService orchestrationService;
    private final SettlementAuthorizationService settlementService;

    public PaymentOrchestrationController(PaymentOrchestrationService orchestrationService,
                                          SettlementAuthorizationService settlementService) {
        this.orchestrationService = orchestrationService;
        this.settlementService = settlementService;
    }

    @PostMapping("/payments/instructions")
    @Operation(summary = "Create bank payment instruction", description = "Generates virtual account/IFSC instruction for funding deal/milestone")
    public ResponseEntity<ApiResponse<PaymentInstructionResponse>> createInstruction(
            @Valid @RequestBody CreateInstructionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        PaymentInstructionResponse response = orchestrationService.createPaymentInstruction(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Payment instruction created", response));
    }

    @PostMapping("/payments/instructions/{id}/confirm")
    @Operation(summary = "Payment confirmation (disabled)", description = "Returns 403 for authenticated callers until trusted bank ingestion is available; does not confirm or fund payments")
    public ResponseEntity<ApiResponse<PaymentInstructionResponse>> confirmPayment(
            @PathVariable UUID id,
            @RequestBody(required = false) ConfirmPaymentRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ConfirmPaymentRequest req = request != null ? request : new ConfirmPaymentRequest(null, null);
        PaymentInstructionResponse response = orchestrationService.confirmPayment(id, req, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Payment confirmed and escrow funded", response));
    }

    @GetMapping("/payments/instructions/deal/{dealId}")
    @Operation(summary = "Get payment instructions for deal")
    public ResponseEntity<ApiResponse<List<PaymentInstructionResponse>>> getInstructionsForDeal(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<PaymentInstructionResponse> list = orchestrationService.getInstructionsForDeal(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @PostMapping({"/settlements", "/settlements/authorize"})
    @Operation(summary = "Authorize and execute milestone settlement payout (disabled)", description = "Settlement payout execution is disabled pending settlement orchestration redesign")
    public ResponseEntity<ApiResponse<SettlementResponse>> authorizeSettlement(
            @Valid @RequestBody PayoutAuthorizationRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        throw new AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign");
    }

    @GetMapping("/settlements/deal/{dealId}")
    @Operation(summary = "Get settlements for deal")
    public ResponseEntity<ApiResponse<List<SettlementResponse>>> getSettlementsForDeal(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<SettlementResponse> list = settlementService.getSettlementsForDeal(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}
