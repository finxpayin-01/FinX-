package com.finx.paymentorchestration;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.GlobalExceptionHandler;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.paymentorchestration.controller.PaymentOrchestrationController;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.ConfirmPaymentRequest;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmationContainmentTest {

    @Mock private PaymentInstructionRepository instructions;
    @Mock private PaymentTransactionRepository transactions;
    @Mock private DealRepository deals;
    @Mock private MilestoneRepository milestones;
    @Mock private BankingProvider bank;
    @Mock private LedgerService ledger;
    @Mock private EscrowService escrow;
    @Mock private AgreementService agreements;
    @Mock private AuditService audit;
    @Mock private NotificationService notifications;
    @InjectMocks private PaymentOrchestrationService service;

    @AfterEach
    void noLookupOrFinancialSideEffect() {
        verifyNoInteractions(instructions, transactions, deals, milestones, bank,
                ledger, escrow, agreements, audit, notifications);
    }

    // ==========================================
    // 1. Role-based callers (All roles in enum)
    // ==========================================

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("No system role can assert bank confirmation truth")
    void noRoleCanAssertBankCredit(Role role) {
        UserPrincipal actor = new UserPrincipal(UUID.randomUUID(), "Caller", "caller@example.test",
                "unused", role, UserStatus.ACTIVE, List.of());
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("claimed-utr", "claimed-provider"), actor);
    }

    @Test
    @DisplayName("Authenticated BUYER cannot confirm payment")
    void buyerCallerDeniedBeforeLookup() {
        UserPrincipal buyer = new UserPrincipal(UUID.randomUUID(), "Buyer", "buyer@example.test",
                "unused", Role.BUYER, UserStatus.ACTIVE, List.of());
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("UTR-BUYER-001", "PROV-001"), buyer);
    }

    @Test
    @DisplayName("Authenticated VENDOR / SELLER cannot confirm payment")
    void vendorCallerDeniedBeforeLookup() {
        UserPrincipal vendor = new UserPrincipal(UUID.randomUUID(), "Vendor", "vendor@example.test",
                "unused", Role.SELLER, UserStatus.ACTIVE, List.of());
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("UTR-VENDOR-001", "PROV-001"), vendor);
    }

    @Test
    @DisplayName("PROJECT_MANAGER cannot confirm payment")
    void projectManagerCallerDeniedBeforeLookup() {
        UserPrincipal pm = new UserPrincipal(UUID.randomUUID(), "PM", "pm@example.test",
                "unused", Role.PROJECT_MANAGER, UserStatus.ACTIVE, List.of());
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("UTR-PM-001", "PROV-001"), pm);
    }

    @Test
    @DisplayName("FINANCE role cannot confirm payment")
    void financeCallerDeniedBeforeLookup() {
        UserPrincipal finance = new UserPrincipal(UUID.randomUUID(), "Finance", "finance@example.test",
                "unused", Role.FINANCE, UserStatus.ACTIVE, List.of());
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("UTR-FIN-001", "PROV-001"), finance);
    }

    @Test
    @DisplayName("Privileged ADMIN cannot confirm payment")
    void adminCallerDeniedBeforeLookup() {
        UserPrincipal admin = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@example.test",
                "unused", Role.ADMIN, UserStatus.ACTIVE, List.of());
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("UTR-ADMIN-001", "PROV-001"), admin);
    }

    // ==========================================
    // 2. Caller Contexts: Null, Webhook, References
    // ==========================================

    @Test
    @DisplayName("Null principal (unauthenticated or background) is not trusted ingestion")
    void nullPrincipalIsNotTrustedIngestion() {
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("claimed-utr", "event-id"), null);
    }

    @Test
    @DisplayName("Webhook caller context with null principal is rejected without bypass")
    void webhookCallerContextDeniedBeforeLookup() {
        assertDenied(UUID.randomUUID(), new ConfirmPaymentRequest("UTR-WH-12345", "EVT-BANKING-WH-999"), null);
    }

    @Test
    @DisplayName("Missing or blank confirmation references never generate financial identity")
    void missingOrBlankReferencesNeverGenerateFinancialIdentity() {
        UUID id = UUID.randomUUID();
        assertDenied(id, null, null);
        assertDenied(id, new ConfirmPaymentRequest(null, null), null);
        assertDenied(id, new ConfirmPaymentRequest("", ""), null);
        assertDenied(id, new ConfirmPaymentRequest(" ", " "), null);
    }

    @Test
    @DisplayName("Fabricated confirmation references and provider IDs are rejected")
    void fabricatedConfirmationReferencesRejected() {
        UUID id = UUID.randomUUID();
        assertDenied(id, new ConfirmPaymentRequest("FAKE-UTR-99999", "FABRICATED-PROVIDER-REF"), null);
    }

    // ==========================================
    // 3. Replay and Duplication
    // ==========================================

    @Test
    @DisplayName("Replay of previously successful or existing instruction is denied before lookup")
    void replayOfPreviouslySuccessfulInstructionDeniedBeforeLookup() {
        UUID historicalInstructionId = UUID.randomUUID();
        assertDenied(historicalInstructionId, new ConfirmPaymentRequest("UTR-HISTORICAL-SUCCESS", "EVENT-OLD"), null);
    }

    @Test
    @DisplayName("Repeated requests with identical or varying references remain unconditionally denied")
    void repeatedRequestsWithSameOrDifferentReferencesRemainDenied() {
        UUID id = UUID.randomUUID();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertDenied(id, new ConfirmPaymentRequest("same-utr", "same-event"), null);
            assertDenied(id, new ConfirmPaymentRequest("utr-" + attempt, "event-" + attempt), null);
        }
    }

    // ==========================================
    // 4. Concurrency Test
    // ==========================================

    @Test
    @DisplayName("Concurrent confirmation calls all fail with zero side effects")
    void simultaneousDuplicateAndConflictingRequestsHaveNoSideEffects() throws Exception {
        UUID id = UUID.randomUUID();
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String reference = i % 2 == 0 ? "duplicate" : "conflicting-" + i;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    assertDenied(id, new ConfirmPaymentRequest(reference, reference), null);
                    return null;
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (Future<?> attempt : attempts) {
                attempt.get(10, TimeUnit.SECONDS);
            }
        }
    }

    // ==========================================
    // 5. Controller HTTP 403 Runtime Behavior
    // ==========================================

    @Test
    @DisplayName("Controller endpoint returns HTTP 403 Forbidden for confirmation request")
    void controllerConfirmationEndpointReturnsHttp403() throws Exception {
        PaymentOrchestrationController controller = new PaymentOrchestrationController(service, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UUID instructionId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/payments/instructions/" + instructionId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"utrNumber\":\"UTR-TEST-1234\",\"providerReference\":\"PROV-REF-1234\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Controller endpoint returns HTTP 403 Forbidden even with empty body")
    void controllerConfirmationEndpointReturnsHttp403OnEmptyBody() throws Exception {
        PaymentOrchestrationController controller = new PaymentOrchestrationController(service, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UUID instructionId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/payments/instructions/" + instructionId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    private void assertDenied(UUID id, ConfirmPaymentRequest request, UserPrincipal actor) {
        assertThatThrownBy(() -> service.confirmPayment(id, request, actor))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Payment confirmation is disabled pending trusted bank ingestion");
    }
}
