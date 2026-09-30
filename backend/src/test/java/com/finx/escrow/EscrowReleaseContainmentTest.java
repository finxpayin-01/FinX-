package com.finx.escrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.audit.repository.AuditLogRepository;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.repository.NotificationRepository;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.paymentorchestration.TestEscrowFundingFixture;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({RazorpayTestConfiguration.class, TestEscrowFundingFixture.class, TestEscrowReleaseFixture.class})
class EscrowReleaseContainmentTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DealRepository dealRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private EscrowAccountRepository escrowAccountRepository;
    @Autowired private EscrowLedgerRepository escrowLedgerRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private EscrowService escrowService;
    @Autowired private PaymentOrchestrationService paymentOrchestrationService;

    private User buyer;
    private User seller;
    private User admin;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;

    private String buyerToken;
    private String adminToken;

    @BeforeEach
    void setupData() {
        notificationRepository.deleteAll();
        auditLogRepository.deleteAll();
        escrowLedgerRepository.deleteAll();
        escrowAccountRepository.deleteAll();
        milestoneRepository.deleteAll();
        dealRepository.deleteAll();
        userRepository.deleteAll();

        buyer = new User("Buyer User", "buyer." + UUID.randomUUID() + "@finx.test", "hash", Role.BUYER, UserStatus.ACTIVE);
        buyer = userRepository.saveAndFlush(buyer);
        buyerToken = jwtTokenProvider.generateAccessToken(buyer);

        seller = new User("Seller User", "seller." + UUID.randomUUID() + "@finx.test", "hash", Role.SELLER, UserStatus.ACTIVE);
        seller = userRepository.saveAndFlush(seller);

        admin = new User("Admin User", "admin." + UUID.randomUUID() + "@finx.test", "hash", Role.ADMIN, UserStatus.ACTIVE);
        admin = userRepository.saveAndFlush(admin);
        adminToken = jwtTokenProvider.generateAccessToken(admin);

        deal = new Deal("Test Deal", "Desc", buyer.getId(), seller.getId(), new BigDecimal("50000.00"), "INR", DealStatus.FUNDED);
        deal = dealRepository.saveAndFlush(deal);

        milestone = new Milestone(deal.getId(), "Milestone 1", "Desc", 1, new BigDecimal("20000.00"), "INR", null);
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone = milestoneRepository.saveAndFlush(milestone);

        escrowAccount = new EscrowAccount(deal.getId(), new BigDecimal("50000.00"), "INR");
        escrowAccount = escrowAccountRepository.saveAndFlush(escrowAccount);

        EscrowLedger fundLedger = new EscrowLedger(
                escrowAccount.getId(),
                UUID.randomUUID(),
                milestone.getId(),
                TransactionType.FUND,
                new BigDecimal("50000.00"),
                new BigDecimal("50000.00"),
                "Initial funding"
        );
        escrowLedgerRepository.saveAndFlush(fundLedger);
    }

    private String tokenForRole(Role role) {
        User u = new User("User " + role, role.name().toLowerCase() + "." + UUID.randomUUID() + "@finx.test", "hash", role, UserStatus.ACTIVE);
        u = userRepository.saveAndFlush(u);
        return jwtTokenProvider.generateAccessToken(u);
    }

    // ==========================================
    // 1-5. Role-based callers fail closed (403)
    // ==========================================

    @Test
    @DisplayName("1. BUYER cannot directly release escrow via public endpoint (HTTP 403)")
    void buyerCannotDirectlyReleaseEscrow() throws Exception {
        mockMvc.perform(post("/api/escrow/" + milestone.getId() + "/release")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Buyer direct release\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Direct escrow release is disabled pending settlement orchestration"));
    }

    @Test
    @DisplayName("2. ADMIN cannot directly release escrow via public endpoint (HTTP 403)")
    void adminCannotDirectlyReleaseEscrow() throws Exception {
        mockMvc.perform(post("/api/escrow/" + milestone.getId() + "/release")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Admin direct release\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Direct escrow release is disabled pending settlement orchestration"));
    }

    @Test
    @DisplayName("3. PROJECT_MANAGER cannot directly release escrow via public endpoint (HTTP 403)")
    void projectManagerCannotDirectlyReleaseEscrow() throws Exception {
        String pmToken = tokenForRole(Role.PROJECT_MANAGER);
        mockMvc.perform(post("/api/escrow/" + milestone.getId() + "/release")
                        .header("Authorization", "Bearer " + pmToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"PM release\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Direct escrow release is disabled pending settlement orchestration"));
    }

    @Test
    @DisplayName("4. VENDOR/SELLER cannot directly release escrow via public endpoint (HTTP 403)")
    void sellerCannotDirectlyReleaseEscrow() throws Exception {
        String sellerToken = jwtTokenProvider.generateAccessToken(seller);
        mockMvc.perform(post("/api/escrow/" + milestone.getId() + "/release")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Seller release\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Direct escrow release is disabled pending settlement orchestration"));
    }

    @Test
    @DisplayName("5. FINANCE cannot bypass release rules (HTTP 403)")
    void financeCannotRelease() throws Exception {
        String token = tokenForRole(Role.FINANCE);
        mockMvc.perform(post("/api/escrow/" + milestone.getId() + "/release")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Attempt release\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Direct escrow release is disabled pending settlement orchestration"));
    }

    // ==========================================
    // 6. Unauthenticated caller denied (401/403)
    // ==========================================

    @Test
    @DisplayName("6. Unauthenticated caller denied on release endpoint")
    void unauthenticatedCallerDenied() throws Exception {
        mockMvc.perform(post("/api/escrow/" + milestone.getId() + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"No token\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ==========================================
    // 7. Direct service invocation fails
    // ==========================================

    @Test
    @DisplayName("7. Direct service invocation from an untrusted path fails with AccessDeniedException")
    void directServiceInvocationFails() {
        UserPrincipal principal = UserPrincipal.create(buyer);
        assertThatThrownBy(() -> escrowService.releaseEscrow(milestone.getId(), "Direct call", principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Direct escrow release is disabled pending settlement orchestration");
    }

    // ==========================================
    // 8-13. Financial side effects strictly prevented
    // ==========================================

    @Test
    @DisplayName("8-13. Failed public release does not mutate balance, ledger, milestone, deal, notifications, or audit")
    void failedPublicReleaseDoesNotProduceSideEffects() throws Exception {
        BigDecimal initialBalance = escrowAccountRepository.findByDealId(deal.getId()).get().getBalance();
        long initialLedgerCount = escrowLedgerRepository.count();
        long initialAuditCount = auditLogRepository.count();
        long initialNotificationCount = notificationRepository.count();

        // Attempt release
        mockMvc.perform(post("/api/escrow/" + milestone.getId() + "/release")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Attempt release\"}"))
                .andExpect(status().isForbidden());

        // 8. Escrow balance unchanged
        EscrowAccount refreshedAccount = escrowAccountRepository.findByDealId(deal.getId()).get();
        assertThat(refreshedAccount.getBalance()).isEqualByComparingTo(initialBalance);
        assertThat(refreshedAccount.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);

        // 9. No RELEASE ledger entry created
        assertThat(escrowLedgerRepository.count()).isEqualTo(initialLedgerCount);
        assertThat(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestone.getId(), TransactionType.RELEASE)).isEmpty();

        // 10. Milestone status unchanged (still APPROVED, not COMPLETED)
        Milestone refreshedMilestone = milestoneRepository.findById(milestone.getId()).get();
        assertThat(refreshedMilestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);

        // 11. Deal status unchanged (still FUNDED, not COMPLETED)
        Deal refreshedDeal = dealRepository.findById(deal.getId()).get();
        assertThat(refreshedDeal.getStatus()).isEqualTo(DealStatus.FUNDED);

        // 12. No release notification emitted
        assertThat(notificationRepository.count()).isEqualTo(initialNotificationCount);

        // 13. No ESCROW_RELEASED or DEAL_COMPLETED audit event emitted
        assertThat(auditLogRepository.findAll().stream()
                .noneMatch(a -> "ESCROW_RELEASED".equals(a.getAction()) || "DEAL_COMPLETED".equals(a.getAction()))).isTrue();
    }

    // ==========================================
    // 14-15. Historical reads remain intact
    // ==========================================

    @Test
    @DisplayName("14. Historical escrow account read works for deal party")
    void historicalEscrowAccountReadWorks() throws Exception {
        mockMvc.perform(get("/api/escrow/deal/" + deal.getId())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dealId").value(deal.getId().toString()))
                .andExpect(jsonPath("$.data.balance").value(50000.00));
    }

    @Test
    @DisplayName("15. Historical RELEASE ledger entries remain readable")
    void historicalReleaseEntriesRemainReadable() throws Exception {
        // Create a historical RELEASE entry directly to simulate historical data
        EscrowLedger historicalRelease = new EscrowLedger(
                escrowAccount.getId(),
                null,
                milestone.getId(),
                TransactionType.RELEASE,
                new BigDecimal("20000.00"),
                new BigDecimal("30000.00"),
                "Historical settlement release"
        );
        escrowLedgerRepository.saveAndFlush(historicalRelease);

        mockMvc.perform(get("/api/escrow/ledger/deal/" + deal.getId())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].transactionType").isNotEmpty());
    }

    // ==========================================
    // 16-17. P0-01 and P0-02 containment preservation
    // ==========================================

    @Test
    @DisplayName("16. P0-01 payment confirmation remains blocked at service level")
    void p01PaymentConfirmationRemainsBlocked() {
        assertThatThrownBy(() -> paymentOrchestrationService.confirmPayment(UUID.randomUUID(), null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Payment confirmation is disabled pending trusted bank ingestion");
    }

    @Test
    @DisplayName("17. P0-02 webhook security rejects unauthenticated incoming events")
    void p02WebhookSecurityRejectsUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/banking/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-001\",\"eventType\":\"PING\"}"))
                .andExpect(status().isBadRequest());
    }
}
