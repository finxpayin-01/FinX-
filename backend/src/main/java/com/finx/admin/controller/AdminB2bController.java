package com.finx.admin.controller;

import com.finx.common.response.ApiResponse;
import com.finx.exception.ResourceNotFoundException;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.service.ReconciliationService;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.service.RiskEngineService;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.dto.VerificationDtos.*;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
import com.finx.verification.service.VerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin Operations", description = "FinX administrative inspection, reconciliation, verification review, and risk control")
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminB2bController {

    private final KycProfileRepository kycRepository;
    private final KybProfileRepository kybRepository;
    private final VerificationService verificationService;
    private final ReconciliationService reconciliationService;
    private final RiskEngineService riskEngineService;
    private final SettlementInstructionRepository settlementRepository;
    private final com.finx.ledger.service.LedgerService ledgerService;

    public AdminB2bController(KycProfileRepository kycRepository,
                              KybProfileRepository kybRepository,
                              VerificationService verificationService,
                              ReconciliationService reconciliationService,
                              RiskEngineService riskEngineService,
                              SettlementInstructionRepository settlementRepository,
                              com.finx.ledger.service.LedgerService ledgerService) {
        this.kycRepository = kycRepository;
        this.kybRepository = kybRepository;
        this.verificationService = verificationService;
        this.reconciliationService = reconciliationService;
        this.riskEngineService = riskEngineService;
        this.settlementRepository = settlementRepository;
        this.ledgerService = ledgerService;
    }

    @GetMapping("/ledger/entries")
    @Operation(summary = "List all double-entry journal entries")
    public ResponseEntity<ApiResponse<List<com.finx.ledger.dto.LedgerDtos.JournalEntryResponse>>> getLedgerEntries() {
        List<com.finx.ledger.dto.LedgerDtos.JournalEntryResponse> entries = ledgerService.getAllJournalEntries().stream()
                .map(e -> new com.finx.ledger.dto.LedgerDtos.JournalEntryResponse(
                        e.getId(),
                        e.getEntryReference(),
                        e.getTransactionType(),
                        e.getReferenceId(),
                        e.getDealId(),
                        e.getMilestoneId(),
                        e.getTotalDebit(),
                        e.getTotalCredit(),
                        e.getCurrency(),
                        e.getNarration(),
                        e.getPostedAt() != null ? e.getPostedAt().toString() : null
                ))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(entries));
    }

    @GetMapping("/verifications")
    @Operation(summary = "List all pending and reviewable KYC, KYB, and bank mismatches")
    public ResponseEntity<ApiResponse<AdminVerificationSummaryResponse>> getVerifications() {
        AdminVerificationSummaryResponse summary = verificationService.getAdminVerificationSummary();
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @PostMapping("/verifications/kyc/{id}/review")
    @Operation(summary = "Perform compliance review on individual KYC")
    public ResponseEntity<ApiResponse<KycResponse>> reviewKyc(
            @PathVariable UUID id,
            @Valid @RequestBody AdminReviewRequest request,
            @AuthenticationPrincipal UserPrincipal adminUser) {
        KycResponse res = verificationService.reviewKyc(id, request, adminUser);
        return ResponseEntity.ok(ApiResponse.success("KYC review decision recorded", res));
    }

    @PostMapping("/verifications/kyb/{id}/review")
    @Operation(summary = "Perform compliance review on corporate KYB")
    public ResponseEntity<ApiResponse<KybResponse>> reviewKyb(
            @PathVariable UUID id,
            @Valid @RequestBody AdminReviewRequest request,
            @AuthenticationPrincipal UserPrincipal adminUser) {
        KybResponse res = verificationService.reviewKyb(id, request, adminUser);
        return ResponseEntity.ok(ApiResponse.success("KYB review decision recorded", res));
    }

    @PostMapping("/verifications/{id}/review")
    @Operation(summary = "Perform compliance review on verification record")
    public ResponseEntity<ApiResponse<Object>> reviewGeneric(
            @PathVariable UUID id,
            @Valid @RequestBody AdminReviewRequest request,
            @AuthenticationPrincipal UserPrincipal adminUser) {
        if (kycRepository.existsById(id)) {
            KycResponse res = verificationService.reviewKyc(id, request, adminUser);
            return ResponseEntity.ok(ApiResponse.success("KYC review decision recorded", res));
        } else if (kybRepository.existsById(id)) {
            KybResponse res = verificationService.reviewKyb(id, request, adminUser);
            return ResponseEntity.ok(ApiResponse.success("KYB review decision recorded", res));
        } else {
            throw new ResourceNotFoundException("VerificationProfile", "id", id);
        }
    }

    @GetMapping("/reconciliation/runs")
    @Operation(summary = "List all reconciliation runs")
    public ResponseEntity<ApiResponse<List<ReconciliationRun>>> getReconciliationRuns() {
        List<ReconciliationRun> runs = reconciliationService.getRuns();
        return ResponseEntity.ok(ApiResponse.success(runs));
    }

    @PostMapping("/reconciliation/trigger")
    @Operation(summary = "Trigger a reconciliation run")
    public ResponseEntity<ApiResponse<ReconciliationRun>> triggerReconciliation(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ReconciliationRun run = reconciliationService.triggerReconciliation(currentUser);
        return ResponseEntity.ok(ApiResponse.success("Reconciliation run completed", run));
    }

    @GetMapping("/reconciliation/runs/{runId}/items")
    @Operation(summary = "Get items for reconciliation run")
    public ResponseEntity<ApiResponse<List<ReconciliationItem>>> getReconciliationItems(@PathVariable UUID runId) {
        List<ReconciliationItem> items = reconciliationService.getItemsForRun(runId);
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @GetMapping("/risk/deals/{dealId}")
    @Operation(summary = "Get risk assessment for deal")
    public ResponseEntity<ApiResponse<RiskAssessment>> getRiskAssessment(@PathVariable UUID dealId) {
        RiskAssessment assessment = riskEngineService.getAssessmentForDeal(dealId);
        return ResponseEntity.ok(ApiResponse.success(assessment));
    }

    @GetMapping("/settlements")
    @Operation(summary = "List all settlement payouts")
    public ResponseEntity<ApiResponse<List<SettlementInstruction>>> getAllSettlements() {
        List<SettlementInstruction> settlements = settlementRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(settlements));
    }
}
