package com.finx.paymentorchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.audit.service.AuditService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.TestEscrowReleaseFixture;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.security.jwt.JwtTokenProvider;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({RazorpayTestConfiguration.class, TestEscrowFundingFixture.class, TestEscrowReleaseFixture.class, TestSettlementFixture.class})
class SettlementExecutionContainmentTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DealRepository dealRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private EscrowAccountRepository escrowAccountRepository;
    @Autowired private EscrowLedgerRepository escrowLedgerRepository;
    @Autowired private SettlementInstructionRepository settlementRepository;
    @Autowired private SettlementAuthorizationService settlementAuthorizationService;
    @Autowired private PaymentOrchestrationService paymentOrchestrationService;
    @Autowired private EscrowService escrowService;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private TestSettlementFixture testSettlementFixture;

    @SpyBean private BankingProvider bankingProvider;
    @SpyBean private LedgerService ledgerService;
    @SpyBean private NotificationService notificationService;
    @SpyBean private AuditService auditService;

    private User buyer;
    private User seller;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;

    @BeforeEach
    void setUp() {
        buyer = createTestUser("buyer_" + UUID.randomUUID().toString().substring(0, 8), Role.BUYER);
        seller = createTestUser("seller_" + UUID.randomUUID().toString().substring(0, 8), Role.SELLER);

        deal = new Deal();
        deal.setTitle("B04 Containment Deal");
        deal.setDescription("Verifying combined payout execution containment");
        deal.setTotalAmount(new BigDecimal("100000.00"));
        deal.setCurrency("INR");
        deal.setBuyerId(buyer.getId());
        deal.setSellerId(seller.getId());
        deal.setStatus(DealStatus.FUNDED);
        deal = dealRepository.saveAndFlush(deal);

        milestone = new Milestone();
        milestone.setDealId(deal.getId());
        milestone.setTitle("Deliverable 1");
        milestone.setDescription("Milestone work");
        milestone.setAmount(new BigDecimal("50000.00"));
        milestone.setSequence(1);
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone = milestoneRepository.saveAndFlush(milestone);

        escrowAccount = new EscrowAccount();
        escrowAccount.setDealId(deal.getId());
        escrowAccount.setCurrency("INR");
        escrowAccount.setBalance(new BigDecimal("100000.00"));
        escrowAccount.setTotalFunded(new BigDecimal("100000.00"));
        escrowAccount.setTotalReleased(BigDecimal.ZERO);
        escrowAccount = escrowAccountRepository.saveAndFlush(escrowAccount);
    }

    private User createTestUser(String username, Role role) {
        User user = new User();
        user.setEmail(username + "@finx.com");
        user.setName("Test " + username);
        user.setPasswordHash("hashed_secret");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private UserPrincipal toPrincipal(User user) {
        return new UserPrincipal(user.getId(), user.getName(), user.getEmail(), user.getPasswordHash(),
                user.getRole(), user.getStatus(), List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(user.getRole().getAuthority())));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"BUYER", "ADMIN", "FINANCE", "PROJECT_MANAGER", "SELLER"})
    @DisplayName("Public routes POST /settlements and /settlements/authorize fail closed with 403 Forbidden for all roles")
    void allRolesCannotAuthorizeSettlementViaPublicEndpoints(Role role) throws Exception {
        User caller = createTestUser("caller_" + UUID.randomUUID().toString().substring(0, 8), role);
        String token = tokenProvider.generateAccessToken(toPrincipal(caller));

        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(
                milestone.getId(), "Authorized by test", "IDEMP-" + UUID.randomUUID()
        );

        // Test POST /api/v1/settlements
        mockMvc.perform(post("/api/v1/settlements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Settlement payout execution is disabled pending settlement orchestration redesign"));

        // Test POST /api/v1/settlements/authorize
        mockMvc.perform(post("/api/v1/settlements/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Settlement payout execution is disabled pending settlement orchestration redesign"));

        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("Unauthenticated caller cannot execute settlement payout (401 or 403)")
    void unauthenticatedCallerCannotExecuteSettlement() throws Exception {
        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(
                milestone.getId(), "Unauth attempt", "IDEMP-UNAUTH"
        );

        mockMvc.perform(post("/api/v1/settlements/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(401, 403);
                });

        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"BUYER", "ADMIN", "FINANCE", "PROJECT_MANAGER", "SELLER"})
    @DisplayName("Direct service call to authorizeAndInitiateSettlement fails closed with AccessDeniedException for all roles")
    void directServiceCallFailsClosedForAllRoles(Role role) {
        User caller = createTestUser("direct_" + UUID.randomUUID().toString().substring(0, 8), role);
        UserPrincipal principal = toPrincipal(caller);
        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(
                milestone.getId(), "Direct service attempt", "IDEMP-DIRECT"
        );

        assertThatThrownBy(() -> settlementAuthorizationService.authorizeAndInitiateSettlement(request, principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("Direct service call with null principal fails closed with AccessDeniedException")
    void directServiceCallWithNullPrincipalFailsClosed() {
        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(
                milestone.getId(), "Null principal attempt", "IDEMP-NULL"
        );

        assertThatThrownBy(() -> settlementAuthorizationService.authorizeAndInitiateSettlement(request, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled pending settlement orchestration redesign");

        verify(bankingProvider, never()).initiateSettlement(any());
    }

    @Test
    @DisplayName("Failed settlement execution causes zero financial side-effects (no instruction, ledger, escrow, milestone, audit, notification)")
    void failedSettlementExecutionCausesZeroSideEffects() throws Exception {
        long instructionsBefore = settlementRepository.count();
        long ledgersBefore = escrowLedgerRepository.count();
        BigDecimal balanceBefore = escrowAccountRepository.findByDealId(deal.getId()).orElseThrow().getBalance();

        String token = tokenProvider.generateAccessToken(toPrincipal(buyer));
        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(
                milestone.getId(), "Checking zero side-effects", "IDEMP-ZERO-EFFECTS"
        );

        mockMvc.perform(post("/api/v1/settlements/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 1. Banking provider called 0 times
        verify(bankingProvider, never()).initiateSettlement(any());

        // 2. No SettlementInstruction created
        assertThat(settlementRepository.count()).isEqualTo(instructionsBefore);

        // 3. No double-entry ledger mutation
        verify(ledgerService, never()).postJournalEntry(any());

        // 4. No escrow balance mutation or RELEASE entry
        EscrowAccount currentAccount = escrowAccountRepository.findByDealId(deal.getId()).orElseThrow();
        assertThat(currentAccount.getBalance()).isEqualByComparingTo(balanceBefore);
        assertThat(currentAccount.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(escrowLedgerRepository.count()).isEqualTo(ledgersBefore);

        // 5. Milestone not completed
        Milestone currentMilestone = milestoneRepository.findById(milestone.getId()).orElseThrow();
        assertThat(currentMilestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);

        // 6. Deal not completed
        Deal currentDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(currentDeal.getStatus()).isEqualTo(DealStatus.FUNDED);

        // 7. No payout-disbursed notification
        verify(notificationService, never()).sendNotification(any(), eq("Settlement Disbursed"), any(), any());

        // 8. No SETTLEMENT_COMPLETED audit event
        verify(auditService, never()).logEvent(any(), eq("SETTLEMENT_COMPLETED"), any(), any(), any());
    }

    @Test
    @DisplayName("Repeated, same idempotency key, and different idempotency key calls all fail closed with 0 bank executions")
    void duplicateAndVaryingKeyCallsFailClosedWithZeroSideEffects() throws Exception {
        String token = tokenProvider.generateAccessToken(toPrincipal(buyer));

        PayoutAuthorizationRequest req1 = new PayoutAuthorizationRequest(milestone.getId(), "Attempt 1", "KEY-SAME");
        PayoutAuthorizationRequest req2 = new PayoutAuthorizationRequest(milestone.getId(), "Attempt 2", "KEY-SAME");
        PayoutAuthorizationRequest req3 = new PayoutAuthorizationRequest(milestone.getId(), "Attempt 3", "KEY-DIFF-1");
        PayoutAuthorizationRequest req4 = new PayoutAuthorizationRequest(milestone.getId(), "Attempt 4", "KEY-DIFF-2");

        long instructionsBefore = settlementRepository.count();
        for (PayoutAuthorizationRequest req : List.of(req1, req2, req3, req4)) {
            mockMvc.perform(post("/api/v1/settlements/authorize")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }

        verify(bankingProvider, never()).initiateSettlement(any());
        assertThat(settlementRepository.count()).isEqualTo(instructionsBefore);
    }

    @Test
    @DisplayName("Concurrent settlement execution requests across threads all fail closed with zero bank calls")
    void concurrentSettlementRequestsFailClosed() throws Exception {
        long instructionsBefore = settlementRepository.count();
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    UserPrincipal principal = toPrincipal(buyer);
                    PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(
                            milestone.getId(), "Concurrent " + index, "KEY-CONCURRENT-" + index
                    );
                    settlementAuthorizationService.authorizeAndInitiateSettlement(req, principal);
                    return 200;
                } catch (AccessDeniedException e) {
                    return 403;
                } finally {
                    latch.countDown();
                }
            }));
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(403);
        }

        verify(bankingProvider, never()).initiateSettlement(any());
        assertThat(settlementRepository.count()).isEqualTo(instructionsBefore);
    }

    @Test
    @DisplayName("Historical settlement reads GET /api/v1/settlements/deal/{dealId} remain functional")
    void historicalSettlementReadsRemainFunctional() throws Exception {
        // Seed historical settlement record via test-only fixture
        SettlementInstruction seeded = testSettlementFixture.simulateSettlement(
                deal.getId(), milestone.getId(), buyer.getId(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50000.00"), "INR", "UTR-HIST-12345"
        );

        String token = tokenProvider.generateAccessToken(toPrincipal(buyer));

        mockMvc.perform(get("/api/v1/settlements/deal/" + deal.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].settlementReference").value(seeded.getSettlementReference()))
                .andExpect(jsonPath("$.data[0].status").value("SETTLED"))
                .andExpect(jsonPath("$.data[0].utrNumber").value("UTR-HIST-12345"));
    }

    @Test
    @DisplayName("P0-01 payment confirmation remains disabled (403 Forbidden)")
    void p01PaymentConfirmationRemainsDisabled() throws Exception {
        assertThatThrownBy(() -> paymentOrchestrationService.confirmPayment(UUID.randomUUID(), null, toPrincipal(buyer)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Payment confirmation is disabled pending trusted bank ingestion");
    }

    @Test
    @DisplayName("P0-03 direct escrow release remains disabled (403 Forbidden)")
    void p03DirectEscrowReleaseRemainsDisabled() throws Exception {
        assertThatThrownBy(() -> escrowService.releaseEscrow(milestone.getId(), "Direct release", toPrincipal(buyer)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Direct escrow release is disabled pending settlement orchestration");
    }
}
