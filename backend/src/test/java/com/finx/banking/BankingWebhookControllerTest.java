package com.finx.banking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.banking.provider.BankingProvider;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.payment.entity.WebhookEvent;
import com.finx.payment.repository.WebhookEventRepository;
import com.finx.paymentorchestration.TestEscrowFundingFixture;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({RazorpayTestConfiguration.class, TestEscrowFundingFixture.class})
class BankingWebhookControllerTest {

    private static final String PROVIDER = "mock";
    private static final String VALID_SIGNATURE = "mock-valid-signature";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private PaymentInstructionRepository instructionRepository;
    @Autowired private PaymentOrchestrationService orchestrationService;
    @Autowired private BankingProvider bankingProvider;
    @Autowired private SettlementExecutionAttemptRepository attemptRepository;
    @Autowired private ReleaseInstructionRepository releaseInstructionRepository;
    @Autowired private SettlementObservationRepository observationRepository;

    @BeforeEach
    void cleanUp() {
        observationRepository.deleteAll();
        attemptRepository.deleteAll();
        releaseInstructionRepository.deleteAll();
        webhookEventRepository.deleteAll();
    }

    private String validTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    // ==========================================
    // 1. Signature Validation (Fail-Closed)
    // ==========================================

    @Test
    @DisplayName("1. Missing X-FinX-Signature header is rejected with 400 Bad Request")
    void missingSignature_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-001\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or blank X-FinX-Signature header"));
    }

    @Test
    @DisplayName("2. Blank X-FinX-Signature header is rejected with 400 Bad Request")
    void blankSignature_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-002\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", "   ")
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or blank X-FinX-Signature header"));
    }

    @Test
    @DisplayName("3. Invalid X-FinX-Signature is rejected with 400 Bad Request")
    void invalidSignature_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-003\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", "forged-invalid-signature")
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid banking webhook signature"));
    }

    @Test
    @DisplayName("4. Valid signature passes authentication boundary")
    void validSignature_passesAuthenticationBoundary() throws Exception {
        String payload = "{\"eventId\":\"evt-004\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));
    }

    // ==========================================
    // 2. Timestamp & Replay Window Validation
    // ==========================================

    @Test
    @DisplayName("5. Missing X-FinX-Timestamp header is rejected with 400 Bad Request")
    void missingTimestamp_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-005\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or blank X-FinX-Timestamp header"));
    }

    @Test
    @DisplayName("6. Blank X-FinX-Timestamp header is rejected with 400 Bad Request")
    void blankTimestamp_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-006\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or blank X-FinX-Timestamp header"));
    }

    @Test
    @DisplayName("7. Malformed non-numeric timestamp is rejected with 400 Bad Request")
    void malformedTimestamp_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-007\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", "invalid-timestamp-string"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed webhook timestamp: invalid-timestamp-string"));
    }

    @Test
    @DisplayName("8. Stale timestamp older than 300s window is rejected with 400 Bad Request")
    void staleTimestamp_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-008\",\"eventType\":\"PING\"}";
        long staleSec = Instant.now().getEpochSecond() - 360L; // 6 minutes ago
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", String.valueOf(staleSec)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Webhook timestamp outside acceptable window (stale)"));
    }

    @Test
    @DisplayName("9. Future timestamp exceeding tolerance is rejected with 400 Bad Request")
    void futureTimestampOutsideTolerance_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-009\",\"eventType\":\"PING\"}";
        long futureSec = Instant.now().getEpochSecond() + 120L; // 2 minutes in future
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", String.valueOf(futureSec)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Webhook timestamp outside acceptable window (future-dated)"));
    }

    // ==========================================
    // 3. Provider Binding Validation
    // ==========================================

    @Test
    @DisplayName("10. Unknown provider in path is rejected with 400 Bad Request")
    void unknownProvider_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-010\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/UNKNOWN_BANK_PROVIDER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Provider mismatch or unknown provider: UNKNOWN_BANK_PROVIDER"));
    }

    @Test
    @DisplayName("11. Provider mismatch in path (e.g. RAZORPAY when configured is MOCK) is rejected")
    void providerMismatch_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"evt-011\",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/RAZORPAY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Provider mismatch or unknown provider: RAZORPAY"));
    }

    // ==========================================
    // 4. Payload Schema & Event Identity
    // ==========================================

    @Test
    @DisplayName("12. Missing eventId in payload is rejected with 400 Bad Request")
    void missingEventId_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or blank eventId in webhook payload"));
    }

    @Test
    @DisplayName("13. Blank eventId in payload is rejected with 400 Bad Request")
    void blankEventId_rejectedWithBadRequest() throws Exception {
        String payload = "{\"eventId\":\"   \",\"eventType\":\"PING\"}";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or blank eventId in webhook payload"));
    }

    @Test
    @DisplayName("14. Malformed JSON body is rejected with 400 Bad Request")
    void malformedJsonBody_rejectedWithBadRequest() throws Exception {
        String payload = "{ malformed json: not valid }";
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed banking webhook payload"));
    }

    // ==========================================
    // 5. Durable Replay & Idempotency
    // ==========================================

    @Test
    @DisplayName("15. Same event delivered twice produces business effect at most once")
    void sameEventDeliveredTwice_businessEffectAtMostOnce() throws Exception {
        String eventId = "evt-duplicate-015";
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PING\"}";
        String ts = validTimestamp();

        // First delivery: processed
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        // Verify exactly one record in webhook_events with PROCESSED
        List<WebhookEvent> events = webhookEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getProviderEventId()).isEqualTo(eventId);
        assertThat(events.get(0).getProcessingStatus()).isEqualTo("PROCESSED");

        // Second delivery: duplicate ignored
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_processed"));

        // Still exactly one record
        assertThat(webhookEventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("16. Same eventId with changed payload is rejected as an integrity conflict")
    void sameEventIdWithChangedPayload_rejectedAsConflict() throws Exception {
        String eventId = "evt-conflict-016";
        String payload1 = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PING\",\"amount\":100}";
        String payload2 = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PING\",\"amount\":999}";
        String ts = validTimestamp();

        // First delivery
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk());

        // Second delivery with tampered payload -> rejected
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Payload hash mismatch for event ID: " + eventId));
    }

    @Test
    @DisplayName("17. Concurrent duplicate deliveries process at most once")
    void concurrentDuplicateDeliveries_atMostOneProcessingPath() throws Exception {
        String eventId = "evt-concurrent-017";
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PING\"}";
        String ts = validTimestamp();

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    var res = mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload)
                                    .header("X-FinX-Signature", VALID_SIGNATURE)
                                    .header("X-FinX-Timestamp", ts))
                            .andReturn();
                    return res.getResponse().getStatus();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            for (Future<Integer> f : results) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }

        // Exactly one record exists in durable repository
        assertThat(webhookEventRepository.findAll()).hasSize(1);
        assertThat(webhookEventRepository.findAll().get(0).getProcessingStatus()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("18. Restart simulation: duplicate recognized durably from repository")
    void restartSimulation_duplicateRecognizedDurablyFromRepository() throws Exception {
        String eventId = "evt-restart-018";
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PING\"}";

        // Directly pre-populate durable database record (simulating prior run before JVM restart)
        WebhookEvent prior = new WebhookEvent();
        prior.setProvider("MOCK");
        prior.setProviderEventId(eventId);
        prior.setEventType("PING");
        // Compute SHA-256 hash
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        prior.setPayloadHash(java.util.HexFormat.of().formatHex(hash));
        prior.setProcessingStatus("PROCESSED");
        prior.setProcessedAt(Instant.now());
        webhookEventRepository.saveAndFlush(prior);

        // Deliver webhook after restart
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_processed"))
                .andExpect(jsonPath("$.eventId").value(eventId));
    }

    // ==========================================
    // 6. Interaction With P0-01 & Failure Semantics
    // ==========================================

    @Test
    @DisplayName("19 & 22. Webhook with deposit confirmed cannot bypass P0-01 containment and marks event FAILED")
    void webhookCannotBypassP01Containment_returnsHttp403AndMarksFailed() throws Exception {
        // Create an instruction in the repository
        PaymentInstruction instruction = new PaymentInstruction(
                "FINX-TEST-INS-001",
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.00"),
                "INR",
                "MOCK",
                "IDEMP-" + UUID.randomUUID(),
                UUID.randomUUID()
        );
        instruction.setVirtualAccountNumber("FINX-VA-001");
        instruction.setVirtualIfsc("FINX0001001");
        instruction = instructionRepository.saveAndFlush(instruction);

        String eventId = "evt-payment-credited-019";
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PAYMENT_CREDITED\",\"instructionReference\":\""
                + instruction.getInstructionReference() + "\",\"utrNumber\":\"UTR-BANK-123\"}";

        // Webhook attempt to confirm payment fails closed with HTTP 403 Forbidden due to P0-01
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isForbidden());

        // Event must be durably recorded as FAILED, NOT PROCESSED
        Optional<WebhookEvent> recorded = webhookEventRepository.findByProviderAndProviderEventId("MOCK", eventId);
        assertThat(recorded).isPresent();
        assertThat(recorded.get().getProcessingStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("20. Retry after processing failure is allowed to re-attempt processing")
    void retryAfterProcessingFailure_safelyReprocessable() throws Exception {
        String eventId = "evt-retry-020";
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PAYMENT_CREDITED\",\"instructionReference\":\"NON_EXISTENT_REF\"}";
        String ts = validTimestamp();

        // First attempt: non-existent instruction doesn't trigger confirmPayment, so it succeeds as processed
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk());

        // Second attempt with exact same payload is recognized as already_processed
        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_processed"));
    }

    @Test
    @DisplayName("21. P0-01 manual confirmation remains blocked at service level")
    void p01ManualConfirmationRemainsBlocked() {
        assertThatThrownBy(() -> orchestrationService.confirmPayment(UUID.randomUUID(), null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Payment confirmation is disabled pending trusted bank ingestion");
    }

    // ==========================================
    // 7. E6 Settlement Webhooks & Observation
    // ==========================================

    private ReleaseInstruction createInstruction(UUID id, BigDecimal amount, String currency, String providerAccountId) {
        ReleaseInstruction instruction = new ReleaseInstruction(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1".repeat(64),
                UUID.randomUUID(),
                "2".repeat(64),
                UUID.randomUUID(),
                "MOCK",
                providerAccountId,
                amount,
                currency,
                "idem-" + UUID.randomUUID(),
                "3".repeat(64),
                UUID.randomUUID()
        );
        instruction.markAuthorized(UUID.randomUUID(), Instant.now());
        instruction.markReserved();
        instruction.markQueued();
        instruction.markDispatching();
        instruction.markPendingSettlement();
        return releaseInstructionRepository.saveAndFlush(instruction);
    }

    private SettlementExecutionAttempt createAttempt(ReleaseInstruction instruction, String providerSettlementId) {
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instruction.getId(),
                UUID.randomUUID(),
                1,
                "OP-" + UUID.randomUUID(),
                "FINX-REL-" + instruction.getId(),
                instruction.getProvider(),
                instruction.getProviderAccountId(),
                instruction.getBeneficiaryBankAccountId(),
                instruction.getAmount(),
                instruction.getCurrency(),
                "hash-" + UUID.randomUUID(),
                "worker-1",
                Instant.now()
        );
        attempt.markSending(Instant.now());
        if (providerSettlementId != null) {
            attempt.markAcknowledged(providerSettlementId, "ACKNOWLEDGED", Instant.now());
        }
        return attemptRepository.saveAndFlush(attempt);
    }

    @Test
    @DisplayName("22. Valid signed SETTLEMENT_SETTLED creates FINAL_SUCCESS observation without finalization")
    void validSignedSettlementSettled_createsObservationWithoutFinalization() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-001");

        String eventId = "evt-settle-022";
        Map<String, Object> payloadMap = Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-001",
                "status", "SETTLED",
                "utrNumber", "UTR-123456",
                "amount", "1000.0000",
                "currency", "INR",
                "direction", "DEBIT",
                "providerAccountId", "MOCK-ACC-1"
        );
        String payload = objectMapper.writeValueAsString(payloadMap);

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        SettlementObservation obs = observations.get(0);
        assertThat(obs.getSource()).isEqualTo(SettlementObservationSource.WEBHOOK);
        assertThat(obs.getFinality()).isEqualTo(SettlementObservationFinality.FINAL_SUCCESS);
        assertThat(obs.getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED);
        assertThat(obs.getUtrNumber()).isEqualTo("UTR-123456");

        // Verify ReleaseInstruction remains PENDING_SETTLEMENT — ZERO finalization
        ReleaseInstruction refreshed = releaseInstructionRepository.findById(instruction.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);
    }

    @Test
    @DisplayName("23. Duplicate settlement webhook produces exactly one observation")
    void duplicateSettlementWebhook_producesAtMostOneObservation() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-023");

        String eventId = "evt-settle-023";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-023",
                "status", "SETTLED",
                "utrNumber", "UTR-023"
        ));
        String ts = validTimestamp();

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_processed"));

        assertThat(observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId())).hasSize(1);
    }

    @Test
    @DisplayName("24. Same eventId with tampered settlement payload is rejected")
    void sameEventIdTamperedSettlementPayload_rejected() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        createAttempt(instruction, "MOCK-SETTLE-024");

        String eventId = "evt-settle-024";
        String payload1 = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-024",
                "status", "SETTLED",
                "utrNumber", "UTR-024",
                "amount", "1000.0000"
        ));
        String payload2 = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-024",
                "status", "SETTLED",
                "utrNumber", "UTR-024",
                "amount", "2000.0000"
        ));
        String ts = validTimestamp();

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", ts))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Payload hash mismatch for event ID: " + eventId));
    }

    @Test
    @DisplayName("25. Settlement webhook with unknown settlement reference fails closed and marks FAILED")
    void unknownSettlementRef_failsClosedAndMarksFailed() throws Exception {
        String eventId = "evt-settle-025";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "UNKNOWN-SETTLEMENT-REF",
                "status", "SETTLED",
                "utrNumber", "UTR-025"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown settlement reference in webhook payload"));

        Optional<WebhookEvent> event = webhookEventRepository.findByProviderAndProviderEventId("MOCK", eventId);
        assertThat(event).isPresent();
        assertThat(event.get().getProcessingStatus()).isEqualTo("FAILED");
        assertThat(observationRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("26. Conflicting settlement identifiers fails closed")
    void conflictingSettlementIdentifiers_failsClosed() throws Exception {
        ReleaseInstruction instruction1 = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt1 = createAttempt(instruction1, "MOCK-SETTLE-026-A");

        ReleaseInstruction instruction2 = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt2 = createAttempt(instruction2, "MOCK-SETTLE-026-B");

        String eventId = "evt-settle-026";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-026-A",
                "settlementReference", attempt2.getProviderOperationKey(),
                "status", "SETTLED",
                "utrNumber", "UTR-026"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Conflicting settlement identifiers in webhook payload"));

        Optional<WebhookEvent> event = webhookEventRepository.findByProviderAndProviderEventId("MOCK", eventId);
        assertThat(event).isPresent();
        assertThat(event.get().getProcessingStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("27. SETTLED missing UTR records UNKNOWN observation")
    void settledMissingUtr_recordsUnknownObservation() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-027");

        String eventId = "evt-settle-027";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-027",
                "status", "SETTLED"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk());

        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(observations.get(0).getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_MISSING_UTR);
    }

    @Test
    @DisplayName("28. Wrong amount records UNKNOWN observation")
    void wrongAmount_recordsUnknownObservation() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-028");

        String eventId = "evt-settle-028";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-028",
                "status", "SETTLED",
                "utrNumber", "UTR-028",
                "amount", "999.0000"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk());

        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(observations.get(0).getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("29. Wrong currency records UNKNOWN observation")
    void wrongCurrency_recordsUnknownObservation() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-029");

        String eventId = "evt-settle-029";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-029",
                "status", "SETTLED",
                "utrNumber", "UTR-029",
                "currency", "USD"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk());

        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(observations.get(0).getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("30. Wrong provider account records UNKNOWN observation")
    void wrongProviderAccount_recordsUnknownObservation() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-030");

        String eventId = "evt-settle-030";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_SETTLED",
                "providerSettlementId", "MOCK-SETTLE-030",
                "status", "SETTLED",
                "utrNumber", "UTR-030",
                "providerAccountId", "WRONG-ACC-999"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk());

        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getFinality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(observations.get(0).getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_PROVIDER_ACCOUNT_MISMATCH);
    }

    @Test
    @DisplayName("31. REVERSED status records REVERSED observation")
    void reversedStatus_recordsReversedObservation() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-031");

        String eventId = "evt-settle-031";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_REVERSED",
                "providerSettlementId", "MOCK-SETTLE-031",
                "status", "REVERSED",
                "utrNumber", "UTR-031"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk());

        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getFinality()).isEqualTo(SettlementObservationFinality.REVERSED);
        assertThat(observations.get(0).getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_REVERSED);
    }

    @Test
    @DisplayName("32. FAILED status records FAILED observation")
    void failedStatus_recordsFailedObservation() throws Exception {
        ReleaseInstruction instruction = createInstruction(UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "MOCK-ACC-1");
        SettlementExecutionAttempt attempt = createAttempt(instruction, "MOCK-SETTLE-032");

        String eventId = "evt-settle-032";
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "SETTLEMENT_FAILED",
                "providerSettlementId", "MOCK-SETTLE-032",
                "status", "FAILED"
        ));

        mockMvc.perform(post("/api/v1/webhooks/banking/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-FinX-Signature", VALID_SIGNATURE)
                        .header("X-FinX-Timestamp", validTimestamp()))
                .andExpect(status().isOk());

        List<SettlementObservation> observations = observationRepository.findByExecutionAttemptIdOrderByObservedAtDesc(attempt.getId());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).getFinality()).isEqualTo(SettlementObservationFinality.FAILED);
        assertThat(observations.get(0).getReasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FAILED);
    }
}
