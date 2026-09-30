package com.finx.payment;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.payment.config.PaymentRailPolicy;
import com.finx.payment.config.RazorpayProperties;
import com.finx.payment.dto.request.CreateOrderRequest;
import com.finx.payment.dto.request.VerifyPaymentRequest;
import com.finx.payment.dto.response.CreateOrderResponse;
import com.finx.payment.dto.response.PaymentResponse;
import com.finx.payment.entity.Payment;
import com.finx.payment.entity.PaymentStatus;
import com.finx.payment.repository.PaymentRepository;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private DealRepository dealRepository;

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private RazorpayService razorpayService;

    @Mock
    private EscrowService escrowService;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private PaymentService paymentService;
    private PaymentService legacyPaymentService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID dealId;
    private UUID milestoneId;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;
    private UserPrincipal buyerPrincipal;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository,
                dealRepository,
                milestoneRepository,
                razorpayService,
                escrowService,
                auditService,
                notificationService,
                new PaymentRailPolicy("BANK")
        );

        legacyPaymentService = new PaymentService(
                paymentRepository,
                dealRepository,
                milestoneRepository,
                razorpayService,
                escrowService,
                auditService,
                notificationService,
                new PaymentRailPolicy("RAZORPAY")
        );

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();

        deal = new Deal("Payment Deal", "Desc", buyerId, sellerId, BigDecimal.valueOf(50000), "INR", DealStatus.ACCEPTED);
        deal.setId(dealId);

        milestone = new Milestone(dealId, "Phase 1", "Desc", 1, BigDecimal.valueOf(10000), "INR", null);
        milestone.setId(milestoneId);

        escrowAccount = new EscrowAccount(dealId, BigDecimal.ZERO, "INR");
        escrowAccount.setId(UUID.randomUUID());

        User buyerUser = new User("Buyer", "buyer@finx.test", "pass", Role.BUYER, com.finx.common.enums.UserStatus.ACTIVE);
        buyerUser.setId(buyerId);
        buyerPrincipal = UserPrincipal.create(buyerUser);
    }

    @Test
    @DisplayName("In BANK mode, create payment order fails closed with AccessDeniedException")
    void createPaymentOrder_bankModeDenied() {
        CreateOrderRequest req = new CreateOrderRequest(dealId, milestoneId);
        assertThatThrownBy(() -> paymentService.createPaymentOrder(req, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("BANK mode");

        verify(razorpayService, never()).createOrder(any(), any(), any());
    }

    @Test
    @DisplayName("Historical RAZORPAY mode: Create Razorpay payment order for milestone")
    void createPaymentOrder_historicalRazorpaySuccess() {
        CreateOrderRequest req = new CreateOrderRequest(dealId, milestoneId);

        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowService.ensureEscrowAccount(dealId, "INR")).thenReturn(escrowAccount);
        when(paymentRepository.findByMilestoneIdAndStatus(milestoneId, PaymentStatus.SUCCESS)).thenReturn(Optional.empty());
        when(razorpayService.createOrder(any(), any(), any())).thenReturn("order_test_12345");
        when(razorpayService.getPublicKey()).thenReturn("rzp_test_key");
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        CreateOrderResponse res = legacyPaymentService.createPaymentOrder(req, buyerPrincipal);

        assertThat(res.getOrderId()).isEqualTo("order_test_12345");
        assertThat(res.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        verify(escrowService).ensureEscrowAccount(dealId, "INR");
        verify(auditService).logEvent(eq(buyerId), eq("PAYMENT_CREATED"), eq("PAYMENT"), any(), any());
    }

    @Test
    @DisplayName("In BANK mode, manual verify payment fails closed with AccessDeniedException")
    void verifyPayment_bankModeDenied() {
        VerifyPaymentRequest req = new VerifyPaymentRequest(UUID.randomUUID(), "order_test_12345", "pay_test_999", "sig_valid");

        assertThatThrownBy(() -> paymentService.verifyPayment(req, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("BANK mode");

        verify(razorpayService, never()).verifyPaymentSignature(any(), any(), any());
        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Historical RAZORPAY mode: Verify valid payment signature and fund escrow account")
    void verifyPayment_historicalRazorpaySuccess() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_test_12345", null);
        payment.setId(UUID.randomUUID());

        VerifyPaymentRequest req = new VerifyPaymentRequest(payment.getId(), "order_test_12345", "pay_test_999", "sig_valid");

        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(razorpayService.verifyPaymentSignature("order_test_12345", "pay_test_999", "sig_valid")).thenReturn(true);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        PaymentResponse res = legacyPaymentService.verifyPayment(req, buyerPrincipal);

        assertThat(res.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(escrowService).fundEscrow(eq(dealId), eq(milestoneId), eq(payment.getId()), eq(BigDecimal.valueOf(10000)), eq("INR"), eq(buyerId));
        verify(auditService).logEvent(eq(buyerId), eq("PAYMENT_VERIFIED"), eq("PAYMENT"), any(), any());
    }

    @Test
    @DisplayName("Reject verification on fraudulent payment signature in legacy mode")
    void verifyPayment_invalidSignature() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_test_12345", null);
        payment.setId(UUID.randomUUID());

        VerifyPaymentRequest req = new VerifyPaymentRequest(payment.getId(), "order_test_12345", "pay_test_999", "fraudulent_sig");

        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(razorpayService.verifyPaymentSignature("order_test_12345", "pay_test_999", "fraudulent_sig")).thenReturn(false);

        assertThatThrownBy(() -> legacyPaymentService.verifyPayment(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid payment signature");

        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Reject a valid signature submitted for a different Razorpay order in legacy mode")
    void verifyPayment_rejectsMismatchedOrder() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_test_12345", null);
        payment.setId(UUID.randomUUID());
        VerifyPaymentRequest req = new VerifyPaymentRequest(payment.getId(), "order_test_other", "pay_test_999", "sig_valid");

        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> legacyPaymentService.verifyPayment(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not match");

        verify(razorpayService, never()).verifyPaymentSignature(any(), any(), any());
        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Process valid payment.captured webhook and fund escrow")
    void processWebhook_captured_success() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_webhook_123", null);
        payment.setId(UUID.randomUUID());

        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_wbk_1\",\"order_id\":\"order_webhook_123\",\"status\":\"captured\"}}}}";
        String signature = "valid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(paymentRepository.findByProviderOrderIdForUpdate("order_webhook_123")).thenReturn(Optional.of(payment));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        java.util.Map<String, Object> result = paymentService.processWebhook(payload, signature);

        assertThat(result.get("status")).isEqualTo("success");
        verify(escrowService).fundEscrow(eq(dealId), eq(milestoneId), eq(payment.getId()), eq(BigDecimal.valueOf(10000)), eq("INR"), eq(buyerId));
        verify(auditService).logEvent(eq(buyerId), eq("PAYMENT_VERIFIED"), eq("PAYMENT"), any(), any());
    }

    @Test
    @DisplayName("Idempotent webhook: already processed payment returns already_processed without funding again")
    void processWebhook_duplicate_idempotent() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_webhook_dup", null);
        payment.setId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.SUCCESS);

        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_wbk_dup\",\"order_id\":\"order_webhook_dup\",\"status\":\"captured\"}}}}";
        String signature = "valid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(paymentRepository.findByProviderOrderIdForUpdate("order_webhook_dup")).thenReturn(Optional.of(payment));

        java.util.Map<String, Object> result = paymentService.processWebhook(payload, signature);

        assertThat(result.get("status")).isEqualTo("already_processed");
        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Reject webhook with invalid signature")
    void processWebhook_invalidSignature() {
        String payload = "{\"event\":\"payment.captured\"}";
        String signature = "invalid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.processWebhook(payload, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid webhook signature");

        verify(paymentRepository, never()).findByProviderOrderIdForUpdate(any());
    }

    @Test
    @DisplayName("In BANK mode, webhook with unknown order is rejected with BadRequestException")
    void processWebhook_unknownOrder_rejectedInBankMode() {
        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_wbk_unknown\",\"order_id\":\"order_unknown_999\",\"status\":\"captured\"}}}}";
        String signature = "valid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(paymentRepository.findByProviderOrderIdForUpdate("order_unknown_999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.processWebhook(payload, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unknown order");

        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("In BANK mode, webhook missing order_id is rejected with BadRequestException")
    void processWebhook_missingOrder_rejectedInBankMode() {
        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_wbk_missing\",\"status\":\"captured\"}}}}";
        String signature = "valid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);

        assertThatThrownBy(() -> paymentService.processWebhook(payload, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("order_id is required");

        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Retrieve payment configuration correctly")
    void getPaymentConfig_returnsAccurateStatus() {
        RazorpayProperties props = new RazorpayProperties();
        props.setCurrency("INR");
        props.setSandboxMode(true);
        when(razorpayService.isConfigured()).thenReturn(true);
        when(razorpayService.isKeyIdPresent()).thenReturn(true);
        when(razorpayService.isKeySecretPresent()).thenReturn(true);
        when(razorpayService.isWebhookSecretPresent()).thenReturn(true);
        when(razorpayService.getKeyMode()).thenReturn("TEST");
        when(razorpayService.getProperties()).thenReturn(props);
        when(razorpayService.getPublicKeySafe()).thenReturn("rzp_test_123");

        java.util.Map<String, Object> config = paymentService.getPaymentConfig();

        assertThat(config.get("configured")).isEqualTo(true);
        assertThat(config.get("keyIdPresent")).isEqualTo(true);
        assertThat(config.get("keySecretPresent")).isEqualTo(true);
        assertThat(config.get("mode")).isEqualTo("TEST");
        assertThat(config.get("currency")).isEqualTo("INR");
        assertThat(config.get("sandboxMode")).isEqualTo(true);
        assertThat(config.get("keyId")).isEqualTo("rzp_test_123");
    }

    @Test
    @DisplayName("Reject payment order initiation if user is not the corporate buyer (legacy mode)")
    void createPaymentOrder_wrongBuyerDenied() {
        CreateOrderRequest req = new CreateOrderRequest(dealId, milestoneId);
        User otherUser = new User("Other", "other@finx.test", "pass", Role.BUYER, com.finx.common.enums.UserStatus.ACTIVE);
        otherUser.setId(UUID.randomUUID());
        UserPrincipal otherPrincipal = UserPrincipal.create(otherUser);

        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));

        assertThatThrownBy(() -> legacyPaymentService.createPaymentOrder(req, otherPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Only the corporate buyer can initiate payment");
    }

    @Test
    @DisplayName("Reject payment order if no vendor is assigned to the deal (legacy mode)")
    void createPaymentOrder_noVendorAssignedDenied() {
        deal.setSellerId(null);
        CreateOrderRequest req = new CreateOrderRequest(dealId, milestoneId);

        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));

        assertThatThrownBy(() -> legacyPaymentService.createPaymentOrder(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No vendor is assigned to this project");
    }

    @Test
    @DisplayName("Reject payment order if milestone is already completed (legacy mode)")
    void createPaymentOrder_alreadyCompletedMilestoneDenied() {
        milestone.setStatus(com.finx.milestone.entity.MilestoneStatus.COMPLETED);
        CreateOrderRequest req = new CreateOrderRequest(dealId, milestoneId);

        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));

        assertThatThrownBy(() -> legacyPaymentService.createPaymentOrder(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been completed");
    }

    @Test
    @DisplayName("Process payment.failed webhook: sets payment status FAILED and does not credit escrow")
    void processWebhook_failedPayment_creditsZero() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_webhook_fail", null);
        payment.setId(UUID.randomUUID());

        String payload = "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_failed_1\",\"order_id\":\"order_webhook_fail\",\"status\":\"failed\",\"currency\":\"INR\",\"amount\":1000000}}}}";
        String signature = "valid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(paymentRepository.findByProviderOrderIdForUpdate("order_webhook_fail")).thenReturn(Optional.of(payment));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        java.util.Map<String, Object> result = paymentService.processWebhook(payload, signature);

        assertThat(result.get("status")).isEqualTo("failed");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Reject webhook when payment currency is not INR")
    void processWebhook_currencyMismatch_rejected() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_webhook_usd", null);
        payment.setId(UUID.randomUUID());

        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_usd_1\",\"order_id\":\"order_webhook_usd\",\"status\":\"captured\",\"currency\":\"USD\",\"amount\":1000000}}}}";
        String signature = "valid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(paymentRepository.findByProviderOrderIdForUpdate("order_webhook_usd")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.processWebhook(payload, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid payment currency: expected INR");

        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Reject webhook when amount does not match payment record")
    void processWebhook_amountMismatch_rejected() {
        Payment payment = new Payment(dealId, milestoneId, buyerId, BigDecimal.valueOf(10000), "INR", "order_webhook_amt", null);
        payment.setId(UUID.randomUUID());

        // 500000 paise = 5000 INR, but payment expects 1000000 paise = 10000 INR
        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_amt_1\",\"order_id\":\"order_webhook_amt\",\"status\":\"captured\",\"currency\":\"INR\",\"amount\":500000}}}}";
        String signature = "valid_sig";

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(paymentRepository.findByProviderOrderIdForUpdate("order_webhook_amt")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.processWebhook(payload, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Amount mismatch");

        verify(escrowService, never()).fundEscrow(any(), any(), any(), any(), any(), any());
    }
}
