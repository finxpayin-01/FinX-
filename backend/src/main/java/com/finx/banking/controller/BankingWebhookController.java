package com.finx.banking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.audit.service.AuditService;
import com.finx.banking.provider.BankingProvider;
import com.finx.exception.BadRequestException;
import com.finx.payment.entity.WebhookEvent;
import com.finx.payment.repository.WebhookEventRepository;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.ConfirmPaymentRequest;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Controller for fail-closed banking partner webhook ingestion.
 * Enforces raw payload authentication, provider binding, strict timestamp windowing,
 * and durable replay protection via the webhook_events table.
 */
@RestController
@RequestMapping("/api/v1/webhooks/banking")
@Tag(name = "Banking Webhooks", description = "Asynchronous banking and nodal settlement webhook ingestion")
public class BankingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BankingWebhookController.class);

    private static final long REPLAY_WINDOW_SECONDS = 300L;
    private static final long FUTURE_TOLERANCE_SECONDS = 60L;

    private final BankingProvider bankingProvider;
    private final PaymentInstructionRepository instructionRepository;
    private final PaymentOrchestrationService orchestrationService;
    private final WebhookEventRepository webhookEventRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public BankingWebhookController(BankingProvider bankingProvider,
                                    PaymentInstructionRepository instructionRepository,
                                    PaymentOrchestrationService orchestrationService,
                                    WebhookEventRepository webhookEventRepository,
                                    AuditService auditService,
                                    ObjectMapper objectMapper) {
        this.bankingProvider = bankingProvider;
        this.instructionRepository = instructionRepository;
        this.orchestrationService = orchestrationService;
        this.webhookEventRepository = webhookEventRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{provider}")
    @Operation(summary = "Process banking partner webhook", description = "Verifies signature, timestamp, durable idempotency, and updates ledger")
    public ResponseEntity<Map<String, Object>> handleBankingWebhook(
            @PathVariable String provider,
            @RequestBody(required = false) String rawPayload,
            @RequestHeader(value = "X-FinX-Signature", required = false) String signature,
            @RequestHeader(value = "X-FinX-Timestamp", required = false) String timestamp) {

        // 1. Raw Payload Presence
        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            log.warn("Banking webhook rejected: Empty or null payload");
            throw new BadRequestException("Webhook payload must not be empty");
        }

        // 2. Provider Binding Validation
        if (provider == null || provider.isBlank()) {
            log.warn("Banking webhook rejected: Provider path parameter is blank");
            throw new BadRequestException("Provider path parameter is required");
        }
        String normalizedProvider = provider.trim().toUpperCase(Locale.ROOT);
        String configuredProvider = bankingProvider.getProviderName() != null
                ? bankingProvider.getProviderName().trim().toUpperCase(Locale.ROOT)
                : "";
        if (!normalizedProvider.equals(configuredProvider)) {
            log.warn("Banking webhook rejected: Provider mismatch. Requested={}, Configured={}", normalizedProvider, configuredProvider);
            throw new BadRequestException("Provider mismatch or unknown provider: " + provider);
        }

        // 3. Signature Header Validation
        if (signature == null || signature.isBlank()) {
            log.warn("Banking webhook rejected: Missing or blank X-FinX-Signature header for provider {}", normalizedProvider);
            throw new BadRequestException("Missing or blank X-FinX-Signature header");
        }

        // 4. Timestamp Header & Window Validation
        if (timestamp == null || timestamp.isBlank()) {
            log.warn("Banking webhook rejected: Missing or blank X-FinX-Timestamp header for provider {}", normalizedProvider);
            throw new BadRequestException("Missing or blank X-FinX-Timestamp header");
        }
        long eventSec = parseTimestamp(timestamp);
        long currentSec = Instant.now().getEpochSecond();
        if (currentSec - eventSec > REPLAY_WINDOW_SECONDS) {
            log.warn("Banking webhook rejected: Stale timestamp {} (age={}s)", timestamp, (currentSec - eventSec));
            throw new BadRequestException("Webhook timestamp outside acceptable window (stale)");
        }
        if (eventSec - currentSec > FUTURE_TOLERANCE_SECONDS) {
            log.warn("Banking webhook rejected: Future timestamp {} (future={}s)", timestamp, (eventSec - currentSec));
            throw new BadRequestException("Webhook timestamp outside acceptable window (future-dated)");
        }

        // 5. Cryptographic Raw Payload Signature Verification
        if (!bankingProvider.verifyWebhookSignature(rawPayload, signature.trim(), timestamp.trim())) {
            log.warn("Banking webhook rejected: Invalid signature for provider {}", normalizedProvider);
            throw new BadRequestException("Invalid banking webhook signature");
        }

        // 6. Payload Parsing & Schema Validation
        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            log.warn("Banking webhook rejected: Malformed JSON payload: {}", e.getMessage());
            throw new BadRequestException("Malformed banking webhook payload");
        }
        if (root == null || !root.isObject()) {
            log.warn("Banking webhook rejected: JSON root is not an object");
            throw new BadRequestException("Malformed banking webhook payload: root is not a JSON object");
        }

        String eventId = root.path("eventId").asText("").trim();
        if (eventId.isEmpty()) {
            log.warn("Banking webhook rejected: Missing or blank eventId");
            throw new BadRequestException("Missing or blank eventId in webhook payload");
        }

        String eventType = root.path("eventType").asText("").trim();
        if (eventType.isEmpty()) {
            eventType = "UNKNOWN";
        }

        // 7. Durable Replay Detection via webhook_events
        String payloadHash = computePayloadHash(rawPayload);

        Optional<WebhookEvent> existingOpt = webhookEventRepository.findByProviderAndProviderEventId(normalizedProvider, eventId);
        WebhookEvent event;

        if (existingOpt.isPresent()) {
            event = existingOpt.get();
            // Payload hash integrity check
            if (event.getPayloadHash() != null && !event.getPayloadHash().equalsIgnoreCase(payloadHash)) {
                log.warn("Banking webhook rejected: Payload hash mismatch for provider {} eventId {}", normalizedProvider, eventId);
                throw new BadRequestException("Payload hash mismatch for event ID: " + eventId);
            }

            // Check if already processed
            if ("PROCESSED".equalsIgnoreCase(event.getProcessingStatus())) {
                log.info("Duplicate banking webhook event already processed: provider={} eventId={}", normalizedProvider, eventId);
                return ResponseEntity.ok(Map.of(
                        "status", "already_processed",
                        "provider", normalizedProvider,
                        "eventId", eventId
                ));
            }

            // Retry processing for previously failed or received events
            log.info("Reprocessing banking webhook event: provider={} eventId={} previousStatus={}",
                    normalizedProvider, eventId, event.getProcessingStatus());
            event.setProcessingStatus("PROCESSING");
            event = webhookEventRepository.saveAndFlush(event);
        } else {
            event = new WebhookEvent();
            event.setProvider(normalizedProvider);
            event.setProviderEventId(eventId);
            event.setEventType(eventType);
            event.setPayloadHash(payloadHash);
            event.setProcessingStatus("PROCESSING");
            try {
                event = webhookEventRepository.saveAndFlush(event);
            } catch (DataIntegrityViolationException dive) {
                // Concurrent duplicate insertion race condition caught by uq_provider_event
                existingOpt = webhookEventRepository.findByProviderAndProviderEventId(normalizedProvider, eventId);
                if (existingOpt.isPresent()) {
                    event = existingOpt.get();
                    if (event.getPayloadHash() != null && !event.getPayloadHash().equalsIgnoreCase(payloadHash)) {
                        throw new BadRequestException("Payload hash mismatch for event ID: " + eventId);
                    }
                    log.info("Concurrent duplicate banking webhook event handled: provider={} eventId={}", normalizedProvider, eventId);
                    return ResponseEntity.ok(Map.of(
                            "status", "already_processed",
                            "provider", normalizedProvider,
                            "eventId", eventId
                    ));
                }
                throw dive;
            }
        }

        // 8. Business Processing & Durable Status Lifecycle
        try {
            if ("PAYMENT_CREDITED".equalsIgnoreCase(eventType) || "DEPOSIT_CONFIRMED".equalsIgnoreCase(eventType)) {
                String instructionRef = root.path("instructionReference").asText(null);
                String utr = root.path("utrNumber").asText(null);
                if (instructionRef != null && !instructionRef.isBlank()) {
                    Optional<PaymentInstruction> instructionOpt = instructionRepository.findByInstructionReference(instructionRef.trim());
                    if (instructionOpt.isPresent()) {
                        PaymentInstruction instruction = instructionOpt.get();
                        if (instruction.getStatus() != InstructionStatus.SUCCESS) {
                            orchestrationService.confirmPayment(instruction.getId(), new ConfirmPaymentRequest(utr, eventId), null);
                        }
                    }
                }
            }

            event.setProcessingStatus("PROCESSED");
            event.setProcessedAt(Instant.now());
            webhookEventRepository.save(event);

            auditService.logEvent(
                    null,
                    "BANKING_WEBHOOK_PROCESSED",
                    "WEBHOOK",
                    eventId,
                    "Processed webhook for provider " + normalizedProvider + " event: " + eventType
            );

            return ResponseEntity.ok(Map.of(
                    "status", "processed",
                    "provider", normalizedProvider,
                    "eventType", eventType
            ));
        } catch (Exception ex) {
            log.error("Failed to process banking webhook event {}: {}", eventId, ex.getMessage());
            event.setProcessingStatus("FAILED");
            webhookEventRepository.save(event);
            throw ex;
        }
    }

    private long parseTimestamp(String timestamp) {
        String trimmed = timestamp.trim();
        try {
            long val = Long.parseLong(trimmed);
            if (val > 100_000_000_000L) {
                return val / 1000;
            }
            return val;
        } catch (NumberFormatException nfe) {
            try {
                return Instant.parse(trimmed).getEpochSecond();
            } catch (Exception ex) {
                throw new BadRequestException("Malformed webhook timestamp: " + timestamp);
            }
        }
    }

    private String computePayloadHash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
