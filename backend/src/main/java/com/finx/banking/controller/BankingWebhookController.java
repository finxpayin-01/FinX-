package com.finx.banking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.audit.service.AuditService;
import com.finx.banking.provider.BankingProvider;
import com.finx.exception.BadRequestException;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.ConfirmPaymentRequest;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/webhooks/banking")
@Tag(name = "Banking Webhooks", description = "Asynchronous banking and nodal settlement webhook ingestion")
public class BankingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BankingWebhookController.class);
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    private final BankingProvider bankingProvider;
    private final PaymentInstructionRepository instructionRepository;
    private final PaymentOrchestrationService orchestrationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public BankingWebhookController(BankingProvider bankingProvider,
                                    PaymentInstructionRepository instructionRepository,
                                    PaymentOrchestrationService orchestrationService,
                                    AuditService auditService,
                                    ObjectMapper objectMapper) {
        this.bankingProvider = bankingProvider;
        this.instructionRepository = instructionRepository;
        this.orchestrationService = orchestrationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{provider}")
    @Operation(summary = "Process banking partner webhook", description = "Verifies signature, timestamp, idempotency, and updates ledger")
    public ResponseEntity<Map<String, Object>> handleBankingWebhook(
            @PathVariable String provider,
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-FinX-Signature", required = false) String signature,
            @RequestHeader(value = "X-FinX-Timestamp", required = false) String timestamp) {

        log.info("Received banking webhook for provider {}: signaturePresent={}", provider, signature != null);

        // 1. Signature validation
        if (signature != null && !bankingProvider.verifyWebhookSignature(rawPayload, signature, timestamp)) {
            log.warn("Banking webhook rejected: Invalid signature for provider {}", provider);
            throw new BadRequestException("Invalid banking webhook signature");
        }

        // 2. Timestamp validation (replay attack prevention: 5 min window)
        if (timestamp != null && !timestamp.isBlank()) {
            try {
                long epochSec = Long.parseLong(timestamp);
                long currentSec = Instant.now().getEpochSecond();
                if (Math.abs(currentSec - epochSec) > 300) {
                    log.warn("Banking webhook rejected: Stale timestamp {}", timestamp);
                    throw new BadRequestException("Webhook timestamp outside acceptable window");
                }
            } catch (NumberFormatException ignored) {}
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventId = root.path("eventId").asText("");
            String eventType = root.path("eventType").asText("");
            String instructionRef = root.path("instructionReference").asText(null);
            String utr = root.path("utrNumber").asText(null);

            // 3. Idempotency / Duplicate-event detection
            if (!eventId.isBlank() && !processedEventIds.add(eventId)) {
                log.info("Duplicate webhook event ignored: {}", eventId);
                return ResponseEntity.ok(Map.of("status", "already_processed", "eventId", eventId));
            }

            if ("PAYMENT_CREDITED".equalsIgnoreCase(eventType) || "DEPOSIT_CONFIRMED".equalsIgnoreCase(eventType)) {
                if (instructionRef != null && !instructionRef.isBlank()) {
                    Optional<PaymentInstruction> instructionOpt = instructionRepository.findByInstructionReference(instructionRef);
                    if (instructionOpt.isPresent()) {
                        PaymentInstruction instruction = instructionOpt.get();
                        if (instruction.getStatus() != InstructionStatus.SUCCESS) {
                            orchestrationService.confirmPayment(instruction.getId(), new ConfirmPaymentRequest(utr, eventId), null);
                        }
                    }
                }
            }

            auditService.logEvent(
                    null,
                    "BANKING_WEBHOOK_PROCESSED",
                    "WEBHOOK",
                    eventId.isBlank() ? "N/A" : eventId,
                    "Processed webhook for provider " + provider + " event: " + eventType
            );

            return ResponseEntity.ok(Map.of("status", "processed", "provider", provider, "eventType", eventType));
        } catch (BadRequestException bre) {
            throw bre;
        } catch (Exception e) {
            log.error("Failed to parse banking webhook: {}", e.getMessage(), e);
            throw new BadRequestException("Malformed banking webhook payload");
        }
    }
}
