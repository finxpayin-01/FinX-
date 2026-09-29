package com.finx.invoice.controller;

import com.finx.common.response.ApiResponse;
import com.finx.invoice.dto.InvoiceCheckResponse;
import com.finx.invoice.dto.InvoiceDashboardResponse;
import com.finx.invoice.dto.InvoiceResponse;
import com.finx.invoice.dto.InvoiceReviewDecisionRequest;
import com.finx.invoice.dto.InvoiceReviewResponse;
import com.finx.invoice.dto.InvoiceUploadRequest;
import com.finx.invoice.service.InvoiceService;
import com.finx.security.service.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api"})
@Tag(name = "Invoices", description = "FinX Invoice Matching and Evidence Verification Engine")
@SecurityRequirement(name = "Bearer Authentication")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping("/milestones/{milestoneId}/invoices")
    @Operation(summary = "Upload and process milestone invoice", description = "Vendor submits invoice for automated extraction and 14-point matching")
    public ResponseEntity<ApiResponse<InvoiceResponse>> uploadInvoice(
            @PathVariable UUID milestoneId,
            @Valid @RequestBody InvoiceUploadRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        InvoiceResponse response = invoiceService.uploadAndProcessInvoice(milestoneId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Invoice uploaded and verified successfully", response));
    }

    @GetMapping({"/invoices/{invoiceId}", "/invoices/{invoiceId}/match"})
    @Operation(summary = "Get invoice details", description = "Retrieves full invoice record with match score and status")
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoice(
            @PathVariable UUID invoiceId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        InvoiceResponse response = invoiceService.getInvoiceById(invoiceId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/invoices/{invoiceId}/checks")
    @Operation(summary = "Get invoice verification checks", description = "Retrieves granular 14-check breakdown with reason codes")
    public ResponseEntity<ApiResponse<List<InvoiceCheckResponse>>> getInvoiceChecks(
            @PathVariable UUID invoiceId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        InvoiceResponse response = invoiceService.getInvoiceById(invoiceId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response.checks()));
    }

    @GetMapping("/invoices/{invoiceId}/reviews")
    @Operation(summary = "Get invoice review history", description = "Retrieves human-in-the-loop review actions and notes")
    public ResponseEntity<ApiResponse<List<InvoiceReviewResponse>>> getInvoiceReviews(
            @PathVariable UUID invoiceId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        InvoiceResponse response = invoiceService.getInvoiceById(invoiceId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response.reviews()));
    }

    @PostMapping("/invoices/{invoiceId}/recalculate")
    @Operation(summary = "Recalculate invoice matching", description = "Re-evaluates invoice matching rules idempotently")
    public ResponseEntity<ApiResponse<InvoiceResponse>> recalculate(
            @PathVariable UUID invoiceId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        InvoiceResponse response = invoiceService.recalculateInvoiceMatching(invoiceId, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Invoice matching recalculated", response));
    }

    @PostMapping("/invoices/{invoiceId}/reviews/{reviewId}/decision")
    @Operation(summary = "Submit invoice review decision", description = "PM or Buyer approves, requests info, or rejects invoice")
    public ResponseEntity<ApiResponse<InvoiceResponse>> submitDecision(
            @PathVariable UUID invoiceId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody InvoiceReviewDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        InvoiceResponse response = invoiceService.submitReviewDecision(invoiceId, reviewId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Review decision recorded successfully", response));
    }

    @GetMapping("/milestones/{milestoneId}/invoices")
    @Operation(summary = "Get invoices for milestone", description = "Retrieves all invoice versions for a milestone")
    public ResponseEntity<ApiResponse<List<InvoiceResponse>>> getMilestoneInvoices(
            @PathVariable UUID milestoneId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<InvoiceResponse> responses = invoiceService.getInvoicesForMilestone(milestoneId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/deals/{dealId}/invoices")
    @Operation(summary = "Get invoices for deal", description = "Retrieves all invoices across milestones for a deal")
    public ResponseEntity<ApiResponse<List<InvoiceResponse>>> getDealInvoices(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<InvoiceResponse> responses = invoiceService.getInvoicesForDeal(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/admin/invoices")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all invoices for admin", description = "Lists invoices across all platform deals")
    public ResponseEntity<ApiResponse<List<InvoiceResponse>>> listAdminInvoices(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.adminPage(page,size)));
    }

    @GetMapping("/admin/invoices/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get admin invoice metrics", description = "Aggregates processed, matches, duplicates, and review queues")
    public ResponseEntity<ApiResponse<InvoiceDashboardResponse>> getAdminDashboard() {
        InvoiceDashboardResponse dashboard = invoiceService.getAdminDashboardMetrics();
        return ResponseEntity.ok(ApiResponse.success(dashboard));
    }

    @GetMapping("/invoices/{invoiceId}/document")
    public ResponseEntity<byte[]> download(@PathVariable UUID invoiceId, @AuthenticationPrincipal UserPrincipal user) {
        InvoiceResponse invoice = invoiceService.getInvoiceById(invoiceId, user);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=invoice-"+invoiceId+"."+("application/pdf".equals(invoice.mimeType())?"pdf":"image/png".equals(invoice.mimeType())?"png":"jpg"))
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "private, no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(invoice.mimeType())).body(invoiceService.download(invoiceId,user));
    }
    @GetMapping("/invoices/{invoiceId}/assessments")
    public ResponseEntity<?> history(@PathVariable UUID invoiceId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.history(invoiceId,user)));
    }
    @PostMapping("/invoices/{invoiceId}/reviews")
    public ResponseEntity<?> openReview(@PathVariable UUID invoiceId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.openReview(invoiceId,user)));
    }
    @GetMapping("/invoices/{invoiceId}/duplicates")
    public ResponseEntity<?> duplicates(@PathVariable UUID invoiceId,@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.duplicateReferences(invoiceId,user)));
    }
}
