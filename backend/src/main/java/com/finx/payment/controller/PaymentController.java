package com.finx.payment.controller;

import com.finx.common.response.ApiResponse;
import com.finx.payment.dto.request.CreateOrderRequest;
import com.finx.payment.dto.request.VerifyPaymentRequest;
import com.finx.payment.dto.response.CreateOrderResponse;
import com.finx.payment.dto.response.PaymentResponse;
import com.finx.payment.service.PaymentService;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Razorpay fiat payment processing and signature verification")
@SecurityRequirement(name = "Bearer Authentication")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/config")
    @Operation(summary = "Get Razorpay configuration status", description = "Returns public configuration and whether Razorpay is configured")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getPaymentConfig() {
        java.util.Map<String, Object> config = paymentService.getPaymentConfig();
        return ResponseEntity.ok(ApiResponse.success("Payment configuration retrieved", config));
    }

    @PostMapping("/create-order")
    @Operation(summary = "Create Razorpay payment order", description = "Initiates a payment order for an approved milestone")
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createPaymentOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        CreateOrderResponse order = paymentService.createPaymentOrder(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Payment order created successfully", order));
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify Razorpay payment", description = "Cryptographically verifies signature and credits deal escrow account")
    public ResponseEntity<ApiResponse<PaymentResponse>> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        PaymentResponse payment = paymentService.verifyPayment(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Payment verified and credited to escrow successfully", payment));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment by ID", description = "Retrieves payment details by payment ID")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPaymentById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        PaymentResponse payment = paymentService.getPaymentById(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success(payment));
    }

    @GetMapping("/deal/{dealId}")
    @Operation(summary = "Get payments for deal", description = "Retrieves all payments associated with a deal")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getPaymentsForDeal(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<PaymentResponse> payments = paymentService.getPaymentsForDeal(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(payments));
    }

    @GetMapping("/buyer")
    @Operation(summary = "Get payments for buyer", description = "Retrieves all payments made by the authenticated buyer")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getPaymentsForBuyer(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<PaymentResponse> payments = paymentService.getPaymentsForBuyer(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(payments));
    }

    @PostMapping("/webhook")
    @Operation(summary = "Razorpay Webhook Handler", description = "Receives asynchronous payment events and cryptographically verifies signatures")
    public ResponseEntity<java.util.Map<String, Object>> handleRazorpayWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        java.util.Map<String, Object> result = paymentService.processWebhook(rawPayload, signature);
        return ResponseEntity.ok(result);
    }
}
