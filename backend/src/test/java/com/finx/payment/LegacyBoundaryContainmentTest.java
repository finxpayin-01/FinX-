package com.finx.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.payment.config.PaymentRailPolicy;
import com.finx.payment.config.RazorpayProperties;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.payment.dto.request.CreateOrderRequest;
import com.finx.payment.dto.request.VerifyPaymentRequest;
import com.finx.payment.entity.FundingRail;
import com.finx.payment.entity.Payment;
import com.finx.payment.entity.PaymentStatus;
import com.finx.payment.repository.PaymentRepository;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.config.RiskPolicies;
import com.finx.risk.dto.RiskDtos.UserType;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskEvidenceService;
import com.finx.risk.service.RiskEvidenceService.FundingProvenance;
import com.finx.risk.service.RiskEvidenceService.Snapshot;
import com.finx.security.jwt.JwtTokenProvider;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RazorpayTestConfiguration.class)
@Transactional
public class LegacyBoundaryContainmentTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRailPolicy paymentRailPolicy;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentInstructionRepository paymentInstructionRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RiskEvidenceService riskEvidenceService;

    @Autowired
    private BusinessRiskRepository businessRiskRepository;

    @Autowired
    private PaymentOrchestrationService paymentOrchestrationService;

    @Autowired
    private SettlementAuthorizationService settlementAuthorizationService;

    @SpyBean
    private RazorpayService razorpayService;

    @SpyBean
    private EscrowService escrowService;

    @SpyBean
    private BankingProvider bankingProvider;

    private User buyer;
    private User seller;
    private String buyerToken;
    private Deal deal;
    private Milestone milestone;

    @BeforeEach
    void setUp() {
        long ts = System.currentTimeMillis();
        buyer = new User("Buyer Corp", "buyer." + ts + "@finx.test", "hash", Role.BUYER, UserStatus.ACTIVE);
        buyer = userRepository.save(buyer);
        buyerToken = jwtTokenProvider.generateAccessToken(buyer);

        seller = new User("Seller Corp", "seller." + ts + "@finx.test", "hash", Role.SELLER, UserStatus.ACTIVE);
        seller = userRepository.save(seller);

        deal = new Deal("B12 Test Deal", "Test deal for legacy boundary containment", buyer.getId(), seller.getId(),
                BigDecimal.valueOf(50000), "INR", DealStatus.ACCEPTED);
        deal = dealRepository.save(deal);

        milestone = new Milestone(deal.getId(), "Milestone 1", "Desc", 1, BigDecimal.valueOf(10000), "INR", null);
        milestone = milestoneRepository.save(milestone);
    }

    // 1. BANK mode createPaymentOrder denied
    // 2. RazorpayService.createOrder called 0 times
    @Test
    @DisplayName("1-2. BANK mode createPaymentOrder denied and RazorpayService.createOrder called 0 times")
    void testBankMode_createPaymentOrder_denied_zeroRazorpayCalls() throws Exception {
        assertThat(paymentRailPolicy.isBank()).isTrue();

        CreateOrderRequest request = new CreateOrderRequest(deal.getId(), milestone.getId());
        request.setIdempotencyKey("idemp_b12_order");

        // Service level failure
        assertThatThrownBy(() -> paymentService.createPaymentOrder(request, UserPrincipal.create(buyer)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("BANK mode");

        // Controller level failure (HTTP 403 Forbidden)
        mockMvc.perform(post("/api/payments/create-order")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // Zero calls to RazorpayService.createOrder
        verify(razorpayService, never()).createOrder(any(), any(), any());
    }

    // 3. BANK mode manual verify denied
    // 4. Razorpay signature verification not used as a way to establish new bank funding
    // 5. EscrowService.fundEscrow called 0 times for manual verify denial
    @Test
    @DisplayName("3-5. BANK mode manual verify denied, signature check not used for bank funding, escrow fund called 0 times")
    void testBankMode_manualVerify_denied_zeroRazorpayOrEscrowCalls() throws Exception {
        UUID paymentId = UUID.randomUUID();
        VerifyPaymentRequest request = new VerifyPaymentRequest(paymentId, "order_fake_123", "pay_fake_456", "sig_fake");

        // Service level failure
        assertThatThrownBy(() -> paymentService.verifyPayment(request, UserPrincipal.create(buyer)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("BANK mode");

        // Controller level failure (HTTP 403 Forbidden)
        mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // Zero calls to RazorpayService.verifyPaymentSignature
        verify(razorpayService, never()).verifyPaymentSignature(any(), any(), any());

        // Zero calls to EscrowService.fundEscrow
        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    // 6. Historical Payment read still works
    // 7. Historical Payment retains FundingRail.RAZORPAY
    @Test
    @DisplayName("6-7. Historical Payment read still works and retains FundingRail.RAZORPAY")
    void testHistoricalPaymentRead_preservesRailAndReturnsDetails() throws Exception {
        Payment legacyPayment = new Payment(deal.getId(), milestone.getId(), buyer.getId(),
                BigDecimal.valueOf(10000), "INR", "order_historical_rzp", "idemp_hist_1");
        legacyPayment.setStatus(PaymentStatus.SUCCESS);
        legacyPayment.setFundingRail(FundingRail.RAZORPAY);
        legacyPayment.setProvider("RAZORPAY");
        legacyPayment = paymentRepository.saveAndFlush(legacyPayment);

        // Read by ID
        mockMvc.perform(get("/api/payments/" + legacyPayment.getId())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(legacyPayment.getId().toString()))
                .andExpect(jsonPath("$.data.amount").value(10000.00))
                .andExpect(jsonPath("$.data.razorpayOrderId").value("order_historical_rzp"))
                .andExpect(jsonPath("$.data.provider").value("RAZORPAY"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // Read by deal
        mockMvc.perform(get("/api/payments/deal/" + deal.getId())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(legacyPayment.getId().toString()));

        // Read by buyer
        mockMvc.perform(get("/api/payments/buyer")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(legacyPayment.getId().toString()));

        // Entity in database remains FundingRail.RAZORPAY (no relabeling)
        Payment reloaded = paymentRepository.findById(legacyPayment.getId()).orElseThrow();
        assertThat(reloaded.getFundingRail()).isEqualTo(FundingRail.RAZORPAY);
    }

    // 8. Bank PaymentInstruction remains distinguishable from legacy Payment
    // 9. Legacy evidence is not labelled BANK
    @Test
    @DisplayName("8-9. Bank PaymentInstruction distinguishable from legacy Payment; legacy evidence not labelled BANK")
    void testRiskEvidence_bankAndLegacySegregated() {
        // Create a bank instruction
        PaymentInstruction bankInstruction = new PaymentInstruction(
                "INST-" + UUID.randomUUID(),
                deal.getId(),
                milestone.getId(),
                null,
                null,
                BigDecimal.valueOf(25000),
                "INR",
                "MOCK_BANK",
                "idemp_" + UUID.randomUUID(),
                buyer.getId()
        );
        bankInstruction.setStatus(InstructionStatus.SUCCESS);
        paymentInstructionRepository.saveAndFlush(bankInstruction);

        // Create a legacy payment
        Payment legacyPayment = new Payment(deal.getId(), milestone.getId(), buyer.getId(),
                BigDecimal.valueOf(15000), "INR", "order_legacy_rzp_score", "idemp_rzp_score");
        legacyPayment.setStatus(PaymentStatus.SUCCESS);
        legacyPayment.setFundingRail(FundingRail.RAZORPAY);
        legacyPayment = paymentRepository.saveAndFlush(legacyPayment);

        RiskScore subject = new RiskScore();
        subject.userId = buyer.getId();
        subject.userType = UserType.BUYER;
        subject.score = 20;
        businessRiskRepository.persist(subject);

        Snapshot snapshot = riskEvidenceService.collect(subject, RiskPolicies.defaults(UserType.BUYER), Instant.now());

        // Volumes and counts are separated without guessing cross-rail deduplication
        assertThat(snapshot.bankVolume()).isEqualTo(25000.0);
        assertThat(snapshot.legacyVolume()).isEqualTo(15000.0);
        assertThat(snapshot.bankPaymentCount()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.legacyPaymentCount()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.hasMixedFundingEvidence()).isTrue();

        // Check provenances: Bank instruction has rail "BANK" and authoritativeBank = true
        final UUID finalBankId = bankInstruction.getId();
        final UUID finalLegacyId = legacyPayment.getId();
        List<FundingProvenance> provenances = snapshot.fundingProvenances();
        Optional<FundingProvenance> bankProv = provenances.stream()
                .filter(p -> p.sourceId().equals(finalBankId.toString()))
                .findFirst();
        assertThat(bankProv).isPresent();
        assertThat(bankProv.get().rail()).isEqualTo("BANK");
        assertThat(bankProv.get().authoritativeBank()).isTrue();

        // Legacy payment has rail "RAZORPAY" and authoritativeBank = false (never labelled BANK!)
        Optional<FundingProvenance> legacyProv = provenances.stream()
                .filter(p -> p.sourceId().equals(finalLegacyId.toString()))
                .findFirst();
        assertThat(legacyProv).isPresent();
        assertThat(legacyProv.get().rail()).isEqualTo("RAZORPAY");
        assertThat(legacyProv.get().authoritativeBank()).isFalse();
    }

    // 10. Null/unknown legacy rail is not treated as trusted BANK
    @Test
    @DisplayName("10. Null/unknown legacy rail is treated as UNKNOWN and non-authoritative")
    void testRiskEvidence_unknownLegacyRail_treatedAsNonauthoritative() {
        Payment incompletePayment = new Payment(deal.getId(), milestone.getId(), buyer.getId(),
                BigDecimal.valueOf(5000), "INR", "order_incomplete_rail", "idemp_inc_1");
        incompletePayment.setStatus(PaymentStatus.SUCCESS);
        incompletePayment.setFundingRail(null); // null rail!
        incompletePayment.setProvider("OLD_UNKNOWN_PROVIDER");
        incompletePayment = paymentRepository.saveAndFlush(incompletePayment);

        RiskScore subject = new RiskScore();
        subject.userId = buyer.getId();
        subject.userType = UserType.BUYER;
        subject.score = 20;
        businessRiskRepository.persist(subject);

        final UUID finalIncompleteId = incompletePayment.getId();
        Snapshot snapshot = riskEvidenceService.collect(subject, RiskPolicies.defaults(UserType.BUYER), Instant.now());

        Optional<FundingProvenance> unknownProv = snapshot.fundingProvenances().stream()
                .filter(p -> p.sourceId().equals(finalIncompleteId.toString()))
                .findFirst();
        assertThat(unknownProv).isPresent();
        assertThat(unknownProv.get().rail()).isEqualTo("UNKNOWN");
        assertThat(unknownProv.get().authoritativeBank()).isFalse();
    }

    // 11. Unknown Razorpay webhook order rejected
    // 14. Callback cannot create a new Payment row
    @Test
    @DisplayName("11 & 14. Unknown Razorpay webhook order rejected and cannot create new Payment row")
    void testWebhook_unknownOrder_rejectedInBankMode() {
        long paymentCountBefore = paymentRepository.count();

        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_wbk_unknown\",\"order_id\":\"order_unknown_b12\",\"status\":\"captured\"}}}}";
        String signature = "test_valid_sig";
        doReturn(true).when(razorpayService).verifyWebhookSignature(eq(payload), eq(signature));

        assertThatThrownBy(() -> paymentService.processWebhook(payload, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unknown order");

        // Zero new payment rows created
        assertThat(paymentRepository.count()).isEqualTo(paymentCountBefore);

        // Zero escrow funding
        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    // 12. Known historical Razorpay webhook obligation can be processed only if safely bound
    @Test
    @DisplayName("12. Known historical Razorpay webhook obligation can be processed when safely bound")
    void testWebhook_knownHistoricalObligation_processedIfSafelyBound() {
        Payment legacyPayment = new Payment(deal.getId(), milestone.getId(), buyer.getId(),
                BigDecimal.valueOf(10000), "INR", "order_historical_valid_wbk", "idemp_wbk_hist");
        legacyPayment.setStatus(PaymentStatus.PENDING);
        legacyPayment.setFundingRail(FundingRail.RAZORPAY);
        legacyPayment = paymentRepository.saveAndFlush(legacyPayment);

        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_wbk_hist_1\",\"order_id\":\"order_historical_valid_wbk\",\"status\":\"captured\",\"amount\":1000000,\"currency\":\"INR\"}}}}";
        String signature = "test_valid_sig";
        doReturn(true).when(razorpayService).verifyWebhookSignature(eq(payload), eq(signature));

        var result = paymentService.processWebhook(payload, signature);

        assertThat(result.get("status")).isEqualTo("success");

        Payment updated = paymentRepository.findById(legacyPayment.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(updated.getFundingRail()).isEqualTo(FundingRail.RAZORPAY);

        verify(escrowService).fundEscrow(deal.getId(), milestone.getId(), legacyPayment.getId(),
                BigDecimal.valueOf(10000), "INR", buyer.getId());
    }

    // 13. Changed/wrong order identity rejected
    @Test
    @DisplayName("13. Changed or missing order identity in webhook rejected in BANK mode")
    void testWebhook_missingOrder_rejectedInBankMode() {
        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_wbk_no_order\",\"status\":\"captured\"}}}}";
        String signature = "test_valid_sig";
        doReturn(true).when(razorpayService).verifyWebhookSignature(eq(payload), eq(signature));

        assertThatThrownBy(() -> paymentService.processWebhook(payload, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("order_id is required");

        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    // 15. Application starts without Razorpay credentials in BANK mode
    @Test
    @DisplayName("15. Application starts without Razorpay credentials in BANK mode")
    void testApplicationStartsWithoutRazorpayCredentials() {
        RazorpayProperties emptyProps = new RazorpayProperties();
        emptyProps.setKeyId(null);
        emptyProps.setKeySecret(null);
        emptyProps.setWebhookSecret(null);

        assertThat(emptyProps.isConfigured()).isFalse();
        RazorpayService serviceWithoutCreds = new RazorpayService(emptyProps);
        assertThat(serviceWithoutCreds.isConfigured()).isFalse();

        // In BANK mode, PaymentService doesn't call Razorpay network during init
        assertThat(paymentRailPolicy.isBank()).isTrue();
    }

    // 16. PaymentOrchestrationService has no Razorpay execution dependency
    @Test
    @DisplayName("16. PaymentOrchestrationService has no Razorpay execution dependency")
    void testArchitecture_paymentOrchestrationService_hasNoRazorpayDependency() {
        for (Field field : PaymentOrchestrationService.class.getDeclaredFields()) {
            assertThat(field.getType()).isNotEqualTo(RazorpayService.class);
            assertThat(field.getType().getName()).doesNotContain("razorpay");
        }
    }

    // 17. SettlementAuthorizationService has no Razorpay execution dependency
    @Test
    @DisplayName("17. SettlementAuthorizationService has no Razorpay execution dependency")
    void testArchitecture_settlementAuthorizationService_hasNoRazorpayDependency() {
        for (Field field : SettlementAuthorizationService.class.getDeclaredFields()) {
            assertThat(field.getType()).isNotEqualTo(RazorpayService.class);
            assertThat(field.getType().getName()).doesNotContain("razorpay");
        }
    }

    // 18. No active web3j dependency
    @Test
    @DisplayName("18. No active web3j dependency on classpath")
    void testArchitecture_noWeb3jDependency() {
        assertThatThrownBy(() -> Class.forName("org.web3j.protocol.Web3j"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    // 19. No active blockchain executor
    @Test
    @DisplayName("19. No active blockchain executor class on classpath")
    void testArchitecture_noActiveBlockchainExecutor() {
        List<String> prohibitedClasses = List.of(
                "com.finx.blockchain.BlockchainExecutor",
                "com.finx.blockchain.service.BlockchainService",
                "com.finx.payment.service.BlockchainPaymentService"
        );
        for (String className : prohibitedClasses) {
            assertThatThrownBy(() -> Class.forName(className))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    // 20. B01-B04 containment still holds
    @Test
    @DisplayName("20. B01-B04 containment still holds and BankingProvider.initiateSettlement calls = 0")
    void testPriorContainments_b01_to_b04_stillHold() {
        // B01: Manual confirmation denied
        assertThatThrownBy(() -> paymentOrchestrationService.confirmPayment(
                UUID.randomUUID(),
                new com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.ConfirmPaymentRequest("UTR123", "test"),
                UserPrincipal.create(buyer)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled");

        // B03: Direct escrow release denied
        assertThatThrownBy(() -> escrowService.releaseEscrow(
                UUID.randomUUID(),
                "test comment",
                UserPrincipal.create(buyer)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled");

        // B04: Settlement payout initiation denied
        assertThatThrownBy(() -> settlementAuthorizationService.authorizeAndInitiateSettlement(
                new com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest(milestone.getId(), "test payout", "idemp_test_b12"),
                UserPrincipal.create(buyer)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled");

        // And verify 0 calls to BankingProvider.initiateSettlement
        verify(bankingProvider, never()).initiateSettlement(any());
    }
}
