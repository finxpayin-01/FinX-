package com.finx.admin.controller;

import com.finx.audit.entity.AuditLog;
import com.finx.audit.service.AuditService;
import com.finx.common.response.ApiResponse;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.payment.entity.Payment;
import com.finx.payment.entity.PaymentStatus;
import com.finx.payment.repository.PaymentRepository;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Platform management and global audit endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {

    private final UserRepository userRepository;
    private final DealRepository dealRepository;
    private final PaymentRepository paymentRepository;
    private final EscrowAccountRepository escrowAccountRepository;
    private final EscrowLedgerRepository escrowLedgerRepository;
    private final DisputeRepository disputeRepository;
    private final AuditService auditService;

    public AdminController(UserRepository userRepository,
                           DealRepository dealRepository,
                           PaymentRepository paymentRepository,
                           EscrowAccountRepository escrowAccountRepository,
                           EscrowLedgerRepository escrowLedgerRepository,
                           DisputeRepository disputeRepository,
                           AuditService auditService) {
        this.userRepository = userRepository;
        this.dealRepository = dealRepository;
        this.paymentRepository = paymentRepository;
        this.escrowAccountRepository = escrowAccountRepository;
        this.escrowLedgerRepository = escrowLedgerRepository;
        this.disputeRepository = disputeRepository;
        this.auditService = auditService;
    }

    /**
     * @return
     */
    @GetMapping("/dashboard")
    @Operation(summary = "Get admin dashboard overview", description = "Global financial and system logistics metrics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        List<Deal> allDeals = dealRepository.findAll();
        List<Payment> allPayments = paymentRepository.findAll();
        List<EscrowAccount> allEscrows = escrowAccountRepository.findAll();
        List<EscrowLedger> allLedgers = escrowLedgerRepository.findAll();

        final BigDecimal totalProjectValue = allDeals.stream()
                .map(Deal::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFundsDeposited = allPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .map(Payment::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFundsHeld = allEscrows.stream()
                .map(EscrowAccount::getBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFundsReleased = allLedgers.stream()
                .filter(l -> l.getTransactionType() == com.finx.escrow.entity.TransactionType.RELEASE)
                .map(EscrowLedger::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalUsers = userRepository.count();
        long totalDeals = allDeals.size();
        long activeDeals = allDeals.stream().filter(d -> d.getStatus() == DealStatus.ACTIVE).count();
        long completedDeals = allDeals.stream().filter(d -> d.getStatus() == DealStatus.COMPLETED).count();
        long totalDisputes = disputeRepository.count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalProjectValue", totalProjectValue);
        stats.put("totalFundsDeposited", totalFundsDeposited);
        stats.put("totalFundsHeld", totalFundsHeld);
        stats.put("totalFundsReleased", totalFundsReleased);
        stats.put("totalUsers", totalUsers);
        stats.put("totalDeals", totalDeals);
        stats.put("activeDeals", activeDeals);
        stats.put("completedDeals", completedDeals);
        stats.put("totalDisputes", totalDisputes);

        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "Get system audit logs", description = "Read-only global systemic trail")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAuditLogs() {
        List<AuditLog> rawLogs = auditService.getRecentAuditLogs();
        Map<UUID, User> userCache = new HashMap<>();

        List<Map<String, Object>> mapped = rawLogs.stream().map(log -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", log.getId());
            item.put("timestamp", log.getCreatedAt());
            item.put("action", log.getAction());
            item.put("entityType", log.getEntityType());
            item.put("entityId", log.getEntityId());
            item.put("description", log.getMetadata());

            if (log.getActorUserId() != null) {
                User actor = userCache.computeIfAbsent(log.getActorUserId(), id ->
                        userRepository.findById(id).orElse(null)
                );
                item.put("actorName", actor != null ? actor.getName() : "System / Automated");
                item.put("actorRole", actor != null ? actor.getRole().name() : "SYSTEM");
            } else {
                item.put("actorName", "System");
                item.put("actorRole", "SYSTEM");
            }

            return item;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(mapped));
    }

    @GetMapping("/users")
    @Operation(summary = "List all registered users", description = "Admin user directory")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUsers() {
        List<User> users = userRepository.findAll();
        List<Map<String, Object>> mapped = users.stream().map(u -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", u.getId());
            item.put("name", u.getName());
            item.put("email", u.getEmail());
            item.put("role", u.getRole().name());
            item.put("status", u.getStatus() != null ? u.getStatus().name() : "ACTIVE");
            item.put("createdAt", u.getCreatedAt());
            return item;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(mapped));
    }
}
