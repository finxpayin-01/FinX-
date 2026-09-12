package com.finx.finance.controller;

import com.finx.common.response.ApiResponse;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.payment.entity.Payment;
import com.finx.payment.entity.PaymentStatus;
import com.finx.payment.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/finance")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
@Tag(name = "Finance", description = "Finance operations, reconciliation, and transaction ledgers")
@SecurityRequirement(name = "Bearer Authentication")
public class FinanceController {

    private final PaymentRepository paymentRepository;
    private final EscrowAccountRepository escrowAccountRepository;
    private final EscrowLedgerRepository escrowLedgerRepository;

    public FinanceController(PaymentRepository paymentRepository,
                             EscrowAccountRepository escrowAccountRepository,
                             EscrowLedgerRepository escrowLedgerRepository) {
        this.paymentRepository = paymentRepository;
        this.escrowAccountRepository = escrowAccountRepository;
        this.escrowLedgerRepository = escrowLedgerRepository;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Get finance metrics", description = "Aggregated fiat escrow and transaction numbers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        List<Payment> allPayments = paymentRepository.findAll();
        List<EscrowAccount> allEscrows = escrowAccountRepository.findAll();
        List<EscrowLedger> allLedgers = escrowLedgerRepository.findAll();

        long totalPayments = allPayments.size();
        long successfulPayments = allPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .count();
        long failedPayments = allPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.FAILED)
                .count();

        BigDecimal escrowFunds = allEscrows.stream()
                .map(EscrowAccount::getBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal releasedFunds = allLedgers.stream()
                .filter(l -> l.getTransactionType() == com.finx.escrow.entity.TransactionType.RELEASE)
                .map(EscrowLedger::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pendingFunds = allPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .map(Payment::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPayments", totalPayments);
        stats.put("successfulPayments", successfulPayments);
        stats.put("failedPayments", failedPayments);
        stats.put("escrowFunds", escrowFunds);
        stats.put("releasedFunds", releasedFunds);
        stats.put("pendingFunds", pendingFunds);

        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Get all financial transactions", description = "Settlement and escrow transaction history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTransactions() {
        List<Payment> payments = paymentRepository.findAll();
        List<Map<String, Object>> mapped = payments.stream().map(p -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", p.getId());
            item.put("dealId", p.getDealId());
            item.put("milestoneId", p.getMilestoneId());
            item.put("amount", p.getAmount());
            item.put("currency", p.getCurrency());
            item.put("provider", p.getProvider());
            item.put("providerOrderId", p.getProviderOrderId());
            item.put("providerPaymentId", p.getProviderPaymentId());
            item.put("status", p.getStatus().name());
            item.put("createdAt", p.getCreatedAt());
            return item;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(mapped));
    }
}
