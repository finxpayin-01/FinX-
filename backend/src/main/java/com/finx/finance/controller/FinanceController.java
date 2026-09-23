package com.finx.finance.controller;

import com.finx.common.response.ApiResponse;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/finance")
@Tag(name = "Finance Operations", description = "Finance operations dashboard, transaction volumes, and double-entry ledger summaries")
@SecurityRequirement(name = "Bearer Authentication")
public class FinanceController {

    private final PaymentInstructionRepository instructionRepository;
    private final SettlementInstructionRepository settlementRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerService ledgerService;

    public FinanceController(PaymentInstructionRepository instructionRepository,
                             SettlementInstructionRepository settlementRepository,
                             ReconciliationRunRepository reconciliationRunRepository,
                             LedgerAccountRepository ledgerAccountRepository,
                             LedgerService ledgerService) {
        this.instructionRepository = instructionRepository;
        this.settlementRepository = settlementRepository;
        this.reconciliationRunRepository = reconciliationRunRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerService = ledgerService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Get platform financial operations summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFinanceSummary() {
        List<PaymentInstruction> allInstructions = instructionRepository.findAll();
        List<SettlementInstruction> allSettlements = settlementRepository.findAll();

        BigDecimal totalInflowVolume = allInstructions.stream()
                .filter(i -> i.getStatus() == InstructionStatus.SUCCESS)
                .map(PaymentInstruction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSettledVolume = allSettlements.stream()
                .filter(s -> s.getStatus() == SettlementStatus.SETTLED)
                .map(SettlementInstruction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingSettlementsCount = allSettlements.stream()
                .filter(s -> s.getStatus() == SettlementStatus.PENDING || s.getStatus() == SettlementStatus.PROCESSING)
                .count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInflowVolume", totalInflowVolume);
        summary.put("totalSettledVolume", totalSettledVolume);
        summary.put("totalInstructionsCount", allInstructions.size());
        summary.put("pendingSettlementsCount", pendingSettlementsCount);
        summary.put("totalReconciliationRuns", reconciliationRunRepository.count());

        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/ledger")
    @Operation(summary = "Get internal double-entry ledger account balances")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLedgerBalances() {
        Map<String, Object> balances = new HashMap<>();
        balances.put(LedgerService.ACC_ESCROW_POOL, ledgerService.getAccountBalance(LedgerService.ACC_ESCROW_POOL));
        balances.put(LedgerService.ACC_BANK_NODAL, ledgerService.getAccountBalance(LedgerService.ACC_BANK_NODAL));
        balances.put(LedgerService.ACC_ESCROW_LIABILITY, ledgerService.getAccountBalance(LedgerService.ACC_ESCROW_LIABILITY));
        balances.put(LedgerService.ACC_VENDOR_PAYABLE, ledgerService.getAccountBalance(LedgerService.ACC_VENDOR_PAYABLE));
        balances.put(LedgerService.ACC_FEE_REVENUE, ledgerService.getAccountBalance(LedgerService.ACC_FEE_REVENUE));

        return ResponseEntity.ok(ApiResponse.success(balances));
    }
}
