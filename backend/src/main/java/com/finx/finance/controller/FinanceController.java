package com.finx.finance.controller;

import com.finx.common.response.ApiResponse;
import com.finx.finance.dto.FinanceTransactionView;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/finance")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
@Tag(name = "Finance Operations", description = "Finance operations dashboard, transaction volumes, and double-entry ledger summaries")
@SecurityRequirement(name = "Bearer Authentication")
public class FinanceController {

    private final PaymentInstructionRepository instructionRepository;
    private final SettlementInstructionRepository settlementRepository;
    private final ReleaseInstructionRepository releaseInstructionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerService ledgerService;

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceController(PaymentInstructionRepository instructionRepository,
                             SettlementInstructionRepository settlementRepository,
                             ReleaseInstructionRepository releaseInstructionRepository,
                             ReconciliationRunRepository reconciliationRunRepository,
                             LedgerAccountRepository ledgerAccountRepository,
                             LedgerService ledgerService) {
        this.instructionRepository = instructionRepository;
        this.settlementRepository = settlementRepository;
        this.releaseInstructionRepository = releaseInstructionRepository;
        this.reconciliationRunRepository = reconciliationRunRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerService = ledgerService;
    }

    public FinanceController(PaymentInstructionRepository instructionRepository,
                             SettlementInstructionRepository settlementRepository,
                             ReconciliationRunRepository reconciliationRunRepository,
                             LedgerAccountRepository ledgerAccountRepository,
                             LedgerService ledgerService) {
        this(instructionRepository, settlementRepository, null, reconciliationRunRepository, ledgerAccountRepository, ledgerService);
    }

    @GetMapping({"/summary", "/overview"})
    @Operation(summary = "Get platform financial operations summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFinanceSummary() {
        List<PaymentInstruction> allInstructions = instructionRepository.findAll();
        List<ReleaseInstruction> allReleases = releaseInstructionRepository != null
                ? releaseInstructionRepository.findAll()
                : List.of();

        BigDecimal totalInflowVolume = allInstructions.stream()
                .filter(i -> i.getStatus() == InstructionStatus.SUCCESS)
                .map(PaymentInstruction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSettledVolume = allReleases.stream()
                .filter(r -> r.getStatus() == ReleaseInstructionStatus.SETTLED)
                .map(ReleaseInstruction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingSettlementsCount = allReleases.stream()
                .filter(r -> r.getStatus() != null && r.getStatus().isActive())
                .count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInflowVolume", totalInflowVolume);
        summary.put("totalSettledVolume", totalSettledVolume);
        summary.put("totalInstructionsCount", allInstructions.size());
        summary.put("pendingSettlementsCount", pendingSettlementsCount);
        summary.put("totalReconciliationRuns", reconciliationRunRepository.count());

        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Get unified modern finance transactions view (funding and releases)")
    public ResponseEntity<ApiResponse<List<FinanceTransactionView>>> getFinanceTransactions() {
        List<PaymentInstruction> allInstructions = instructionRepository.findAll();
        List<ReleaseInstruction> allReleases = releaseInstructionRepository != null
                ? releaseInstructionRepository.findAll()
                : List.of();

        List<FinanceTransactionView> result = new ArrayList<>();

        for (PaymentInstruction pi : allInstructions) {
            result.add(new FinanceTransactionView(
                    pi.getId(),
                    "FUNDING",
                    pi.getDealId(),
                    pi.getMilestoneId(),
                    pi.getAmount(),
                    pi.getCurrency(),
                    pi.getStatus() != null ? pi.getStatus().name() : "UNKNOWN",
                    pi.getProvider(),
                    pi.getCreatedAt(),
                    pi.getConfirmedAt()
            ));
        }

        for (ReleaseInstruction ri : allReleases) {
            result.add(new FinanceTransactionView(
                    ri.getId(),
                    "RELEASE",
                    ri.getDealId(),
                    ri.getMilestoneId(),
                    ri.getAmount(),
                    ri.getCurrency(),
                    ri.getStatus() != null ? ri.getStatus().name() : "UNKNOWN",
                    ri.getProvider(),
                    ri.getCreatedAt(),
                    ri.getSettledAt()
            ));
        }

        result.sort(Comparator
                .comparing(FinanceTransactionView::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(tx -> tx.getId() != null ? tx.getId().toString() : ""));

        return ResponseEntity.ok(ApiResponse.success(result));
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
