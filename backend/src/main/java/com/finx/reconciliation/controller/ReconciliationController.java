package com.finx.reconciliation.controller;

import com.finx.common.response.ApiResponse;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.service.ReconciliationService;
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
@RequestMapping({"/api/v1/reconciliation", "/api/reconciliation"})
@Tag(name = "Reconciliation Engine", description = "FinX automated bank vs internal transaction reconciliation")
@SecurityRequirement(name = "Bearer Authentication")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/trigger")
    @Operation(summary = "Trigger a reconciliation run")
    public ResponseEntity<ApiResponse<ReconciliationRun>> triggerReconciliation(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        ReconciliationRun run = reconciliationService.triggerReconciliation(currentUser);
        return ResponseEntity.ok(ApiResponse.success("Reconciliation run completed", run));
    }

    @GetMapping("/runs")
    @Operation(summary = "List all reconciliation runs")
    public ResponseEntity<ApiResponse<List<ReconciliationRun>>> getReconciliationRuns() {
        List<ReconciliationRun> runs = reconciliationService.getRuns();
        return ResponseEntity.ok(ApiResponse.success(runs));
    }

    @GetMapping("/runs/{runId}/items")
    @Operation(summary = "Get items for a reconciliation run")
    public ResponseEntity<ApiResponse<List<ReconciliationItem>>> getReconciliationItems(@PathVariable UUID runId) {
        List<ReconciliationItem> items = reconciliationService.getItemsForRun(runId);
        return ResponseEntity.ok(ApiResponse.success(items));
    }
}
