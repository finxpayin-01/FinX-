package com.finx.finance.controller;

import com.finx.common.response.ApiResponse;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.payment.entity.Payment;
import com.finx.payment.entity.PaymentStatus;
import com.finx.payment.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/finance")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
@Tag(name = "Finance Ledger & Reconciliation", description = "Finance endpoints for project ledgers and reconciliation operations")
@SecurityRequirement(name = "bearerAuth")
public class FinanceProjectController {

    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final PaymentRepository paymentRepository;
    private final EscrowAccountRepository escrowAccountRepository;
    private final EscrowLedgerRepository escrowLedgerRepository;

    public FinanceProjectController(DealRepository dealRepository,
                                    MilestoneRepository milestoneRepository,
                                    PaymentRepository paymentRepository,
                                    EscrowAccountRepository escrowAccountRepository,
                                    EscrowLedgerRepository escrowLedgerRepository) {
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.paymentRepository = paymentRepository;
        this.escrowAccountRepository = escrowAccountRepository;
        this.escrowLedgerRepository = escrowLedgerRepository;
    }

    @GetMapping("/projects")
    @Operation(summary = "Get Finance Project Ledger", description = "Fetch all authorized projects with extensive financial ledger aggregations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getFinanceProjects() {
        List<Deal> deals = dealRepository.findAll();
        List<Map<String, Object>> response = new ArrayList<>();

        for (Deal deal : deals) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", deal.getId());
            map.put("projectId", deal.getProjectId());
            map.put("title", deal.getTitle());
            map.put("buyerId", deal.getBuyerId());
            map.put("sellerId", deal.getSellerId());
            map.put("totalValue", deal.getTotalAmount());
            map.put("currency", deal.getCurrency());
            map.put("status", deal.getStatus().name());

            Optional<EscrowAccount> account = escrowAccountRepository.findByDealId(deal.getId());
            BigDecimal fundedAmount = account.map(EscrowAccount::getBalance).orElse(BigDecimal.ZERO);
            map.put("fundedAmount", fundedAmount);

            List<EscrowLedger> ledgers = account.map(a -> escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(a.getId())).orElse(Collections.emptyList());
            BigDecimal releasedAmount = ledgers.stream()
                    .filter(l -> l.getTransactionType() == com.finx.escrow.entity.TransactionType.RELEASE)
                    .map(EscrowLedger::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal pendingAmount = deal.getTotalAmount().subtract(fundedAmount).subtract(releasedAmount);
            if(pendingAmount.compareTo(BigDecimal.ZERO) < 0) pendingAmount = BigDecimal.ZERO;

            map.put("releasedAmount", releasedAmount);
            map.put("pendingAmount", pendingAmount);
            
            response.add(map);
        }
        return ResponseEntity.ok(ApiResponse.success("Project ledgers retrieved successfully", response));
    }

    @GetMapping("/projects/{id}")
    @Operation(summary = "Get specific Project Financial details", description = "Fetch detailed view of financial stats, milestones, and audit history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFinanceProjectDetails(@PathVariable UUID id) {
        Deal deal = dealRepository.findById(id).orElse(null);
        if (deal == null) {
            return ResponseEntity.status(404).body(ApiResponse.success("Deal not found", null));
        }

        Map<String, Object> details = new HashMap<>();
        details.put("id", deal.getId());
        details.put("projectId", deal.getProjectId());
        details.put("title", deal.getTitle());
        details.put("buyerId", deal.getBuyerId());
        details.put("sellerId", deal.getSellerId());
        details.put("totalValue", deal.getTotalAmount());
        details.put("currency", deal.getCurrency());
        details.put("status", deal.getStatus().name());

        Optional<EscrowAccount> account = escrowAccountRepository.findByDealId(deal.getId());
        BigDecimal fundedAmount = account.map(EscrowAccount::getBalance).orElse(BigDecimal.ZERO);
        details.put("fundedAmount", fundedAmount);

        List<Milestone> milestones = milestoneRepository.findByDealIdOrderBySequenceAsc(deal.getId());
        details.put("milestones", milestones);

        List<EscrowLedger> ledgers = account.map(a -> escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(a.getId())).orElse(Collections.emptyList());
        details.put("transactions", ledgers);

        return ResponseEntity.ok(ApiResponse.success("Detailed ledger fetched", details));
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Reconciliation engine", description = "Identifies mismatches across payment states and ledger truth")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getReconciliation() {
        List<Payment> payments = paymentRepository.findAll();
        List<Map<String, Object>> mismatches = new ArrayList<>();

        for (Payment p : payments) {
            boolean hasMismatch = false;
            String reason = null;

            if (p.getStatus() == PaymentStatus.SUCCESS && p.getMilestoneId() != null) {
                // Confirm there is an EscrowLedger for this milestone that is DEPOSIT
                Optional<Milestone> mOpt = milestoneRepository.findById(p.getMilestoneId());
                if (mOpt.isPresent()) {
                    Optional<EscrowAccount> eAccount = escrowAccountRepository.findByDealId(p.getDealId());
                    List<EscrowLedger> ledgers = eAccount.map(a -> escrowLedgerRepository.findByEscrowAccountIdOrderByCreatedAtDesc(a.getId())).orElse(Collections.emptyList());
                    boolean foundDeposit = ledgers.stream().anyMatch(l -> 
                        l.getTransactionType() == com.finx.escrow.entity.TransactionType.FUND && 
                        l.getAmount().compareTo(p.getAmount()) == 0);
                    
                    if (!foundDeposit) {
                        hasMismatch = true;
                        reason = "Payment SUCCESS but no corresponding Escrow FUND ledger found";
                    }
                }
            } else if (p.getStatus() == PaymentStatus.PENDING) {
                // If pending for a really long time, could flag it.
                if (p.getCreatedAt().isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(1)))) {
                    hasMismatch = true;
                    reason = "Payment PENDING for over 24 hours without confirmation";
                }
            }

            if (hasMismatch) {
                Map<String, Object> discrepancy = new HashMap<>();
                discrepancy.put("paymentId", p.getId());
                discrepancy.put("dealId", p.getDealId());
                discrepancy.put("provider", p.getProvider());
                discrepancy.put("providerOrderId", p.getProviderOrderId());
                discrepancy.put("amount", p.getAmount());
                discrepancy.put("status", "MISMATCH");
                discrepancy.put("reason", reason);
                discrepancy.put("createdAt", p.getCreatedAt());
                mismatches.add(discrepancy);
            }
        }
        return ResponseEntity.ok(ApiResponse.success("Reconciliation mismatches generated", mismatches));
    }
}
