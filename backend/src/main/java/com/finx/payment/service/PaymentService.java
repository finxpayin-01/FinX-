package com.finx.payment.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.payment.dto.request.CreateOrderRequest;
import com.finx.payment.dto.request.VerifyPaymentRequest;
import com.finx.payment.dto.response.CreateOrderResponse;
import com.finx.payment.dto.response.PaymentResponse;
import com.finx.payment.config.PaymentRailPolicy;
import com.finx.payment.entity.FundingRail;
import com.finx.payment.entity.Payment;
import com.finx.payment.entity.PaymentStatus;
import com.finx.payment.repository.PaymentRepository;
import com.finx.security.service.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final EscrowService escrowService;
    private final RazorpayService razorpayService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final PaymentRailPolicy paymentRailPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentService(PaymentRepository paymentRepository,
                          DealRepository dealRepository,
                          MilestoneRepository milestoneRepository,
                          RazorpayService razorpayService,
                          EscrowService escrowService,
                          AuditService auditService,
                          NotificationService notificationService,
                          PaymentRailPolicy paymentRailPolicy) {
        this.paymentRepository = paymentRepository;
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.razorpayService = razorpayService;
        this.escrowService = escrowService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.paymentRailPolicy = paymentRailPolicy != null ? paymentRailPolicy : new PaymentRailPolicy("BANK");
    }

    public PaymentService(PaymentRepository paymentRepository,
                          DealRepository dealRepository,
                          MilestoneRepository milestoneRepository,
                          RazorpayService razorpayService,
                          EscrowService escrowService,
                          AuditService auditService,
                          NotificationService notificationService) {
        this(paymentRepository, dealRepository, milestoneRepository, razorpayService, escrowService, auditService, notificationService, new PaymentRailPolicy("BANK"));
    }

    @Transactional
    public CreateOrderResponse createPaymentOrder(CreateOrderRequest request, UserPrincipal currentUser) {
        if (paymentRailPolicy != null && paymentRailPolicy.isBank()) {
            throw new AccessDeniedException("Razorpay payment order creation is disabled in BANK mode. Use bank payment orchestration.");
        }

        Deal deal = dealRepository.findByIdForUpdate(request.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", request.getDealId()));

        Milestone milestone = milestoneRepository.findByIdForUpdate(request.getMilestoneId())
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", request.getMilestoneId()));

        if (!milestone.getDealId().equals(deal.getId())) {
            throw new BadRequestException("Milestone does not belong to the specified deal");
        }

        if (currentUser.getRole() != Role.ADMIN && !deal.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer can initiate payment for this deal");
        }

        if (deal.getSellerId() == null) {
            throw new BadRequestException("Cannot initiate payment: No vendor is assigned to this project.");
        }

        if (deal.getStatus() == DealStatus.CANCELLED || deal.getStatus() == DealStatus.REJECTED || deal.getStatus() == DealStatus.COMPLETED) {
            throw new BadRequestException("Cannot initiate payment for a " + deal.getStatus() + " deal.");
        }

        if (milestone.getStatus() == MilestoneStatus.COMPLETED) {
            throw new BadRequestException("This milestone has already been completed and released.");
        }

        if (milestone.getAmount() == null || milestone.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Milestone amount must be greater than zero.");
        }

        // Ensure escrow account exists before initiating payment order
        EscrowAccount escrowAccount = escrowService.ensureEscrowAccount(deal.getId(), deal.getCurrency());
        if (escrowAccount == null) {
            throw new BadRequestException("Could not initialize internal escrow account for this deal.");
        }

        // Check if already funded / successful payment exists for this milestone
        Optional<Payment> existingSuccess = paymentRepository.findByMilestoneIdAndStatus(milestone.getId(), PaymentStatus.SUCCESS);
        if (existingSuccess.isPresent()) {
            throw new BadRequestException("This milestone has already been successfully funded. Payment ID: " + existingSuccess.get().getId());
        }

        // Check idempotency key if supplied
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            Optional<Payment> existingIdempotent = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existingIdempotent.isPresent()) {
                Payment p = existingIdempotent.get();
                if (!p.getBuyerId().equals(currentUser.getId()) ||
                        !p.getDealId().equals(deal.getId()) ||
                        !p.getMilestoneId().equals(milestone.getId())) {
                    throw new BadRequestException("This idempotency key is already associated with a different payment.");
                }
                return new CreateOrderResponse(
                        razorpayService.getPublicKey(),
                        p.getProviderOrderId(),
                        p.getAmount(),
                        p.getCurrency(),
                        p.getId(),
                        deal.getTitle(),
                        milestone.getTitle()
                );
            }
        }

        Optional<Payment> existingPending = paymentRepository.findByMilestoneIdAndStatus(milestone.getId(), PaymentStatus.PENDING);
        if (existingPending.isPresent()) {
            Payment p = existingPending.get();
            return new CreateOrderResponse(
                    razorpayService.getPublicKey(),
                    p.getProviderOrderId(),
                    p.getAmount(),
                    p.getCurrency(),
                    p.getId(),
                    deal.getTitle(),
                    milestone.getTitle()
            );
        }

        String receipt = "rcpt_" + milestone.getId().toString().substring(0, 8);
        String razorpayOrderId = razorpayService.createOrder(milestone.getAmount(), milestone.getCurrency(), receipt);

        Payment payment = new Payment(
                deal.getId(),
                milestone.getId(),
                currentUser.getId(),
                milestone.getAmount(),
                milestone.getCurrency(),
                razorpayOrderId,
                request.getIdempotencyKey()
        );

        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        auditService.logEvent(
                currentUser.getId(),
                "PAYMENT_CREATED",
                "PAYMENT",
                savedPayment.getId().toString(),
                "Created payment order: " + razorpayOrderId + " for milestone: " + milestone.getTitle() + " (" + milestone.getAmount() + " " + milestone.getCurrency() + ")"
        );

        log.info("Payment order initiated: paymentId={} orderId={} amount={}", savedPayment.getId(), razorpayOrderId, milestone.getAmount());

        return new CreateOrderResponse(
                razorpayService.getPublicKey(),
                razorpayOrderId,
                savedPayment.getAmount(),
                savedPayment.getCurrency(),
                savedPayment.getId(),
                deal.getTitle(),
                milestone.getTitle()
        );
    }

    @Transactional
    public PaymentResponse verifyPayment(VerifyPaymentRequest request, UserPrincipal currentUser) {
        if (paymentRailPolicy != null && paymentRailPolicy.isBank()) {
            throw new AccessDeniedException("Razorpay payment verification is disabled in BANK mode. Use bank payment orchestration.");
        }

        Payment payment = paymentRepository.findByIdForUpdate(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", request.getPaymentId()));

        if (currentUser != null && currentUser.getRole() != Role.ADMIN && !payment.getBuyerId().equals(currentUser.getId())) {
            throw new UnauthorizedException("Only the corporate buyer who initiated this payment can verify it");
        }

        if (!payment.getProviderOrderId().equals(request.getRazorpayOrderId())) {
            throw new BadRequestException("Payment verification order does not match the initiated payment order.");
        }

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.warn("Payment {} was already verified and marked SUCCESS", payment.getId());
            return PaymentResponse.fromEntity(payment);
        }

        boolean isValid = razorpayService.verifyPaymentSignature(
                payment.getProviderOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature()
        );

        if (!isValid) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            throw new BadRequestException("Invalid payment signature. Payment verification rejected.");
        }

        payment.setProviderOrderId(request.getRazorpayOrderId());
        payment.setProviderPaymentId(request.getRazorpayPaymentId());
        payment.setProviderSignature(request.getRazorpaySignature());
        payment.setStatus(PaymentStatus.SUCCESS);

        Payment verifiedPayment = paymentRepository.saveAndFlush(payment);

        UUID actorId = currentUser != null ? currentUser.getId() : verifiedPayment.getBuyerId();

        // Atomically fund escrow ledger and update deal escrow balance
        escrowService.fundEscrow(
                verifiedPayment.getDealId(),
                verifiedPayment.getMilestoneId(),
                verifiedPayment.getId(),
                verifiedPayment.getAmount(),
                verifiedPayment.getCurrency(),
                actorId
        );

        auditService.logEvent(
                actorId,
                "PAYMENT_VERIFIED",
                "PAYMENT",
                verifiedPayment.getId().toString(),
                "Verified payment of " + verifiedPayment.getAmount() + " " + verifiedPayment.getCurrency() + " (Razorpay ID: " + request.getRazorpayPaymentId() + ")"
        );

        Deal deal = dealRepository.findById(verifiedPayment.getDealId()).orElse(null);
        if (deal != null) {
            notificationService.sendNotification(
                    deal.getSellerId(),
                    "Milestone Payment Received",
                    "Payment of " + verifiedPayment.getAmount() + " " + verifiedPayment.getCurrency() + " verified and deposited into escrow.",
                    "/vendor/projects/" + deal.getId()
            );
        }

        log.info("Payment verified successfully: paymentId={} razorpayPaymentId={}", verifiedPayment.getId(), request.getRazorpayPaymentId());
        return PaymentResponse.fromEntity(verifiedPayment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId, UserPrincipal currentUser) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId));

        validatePaymentAccess(payment, currentUser);
        return PaymentResponse.fromEntity(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForDeal(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        if (currentUser.getRole() != Role.ADMIN) {
            boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
            if (!isParty) {
                throw new UnauthorizedException("You are not authorized to view payments for this deal");
            }
        }

        return paymentRepository.findByDealIdOrderByCreatedAtDesc(dealId).stream()
                .map(PaymentResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForBuyer(UUID buyerId) {
        return paymentRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream()
                .map(PaymentResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> processWebhook(String rawPayload, String signature) {
        if (!razorpayService.verifyWebhookSignature(rawPayload, signature)) {
            log.warn("Webhook rejected: Invalid cryptographic signature");
            throw new BadRequestException("Invalid webhook signature");
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawPayload);
            String event = root.path("event").asText("");
            log.info("Received verified Razorpay webhook event: {}", event);

            JsonNode paymentNode = root.path("payload").path("payment").path("entity");
            String orderId = paymentNode.path("order_id").asText(null);
            String paymentId = paymentNode.path("id").asText(null);
            String status = paymentNode.path("status").asText("");

            if (orderId == null || orderId.isBlank()) {
                if (paymentRailPolicy != null && paymentRailPolicy.isBank()) {
                    log.warn("Webhook rejected in BANK mode: missing order_id");
                    throw new BadRequestException("Razorpay webhook rejected in BANK mode: order_id is required to bind to an existing legacy payment obligation.");
                }
                log.info("Webhook event {} does not contain order_id, acknowledging receipt", event);
                return Map.of("status", "acknowledged", "event", event);
            }

            Optional<Payment> paymentOpt = paymentRepository.findByProviderOrderIdForUpdate(orderId);
            if (paymentOpt.isEmpty()) {
                log.warn("Webhook received for unknown orderId: {}", orderId);
                if (paymentRailPolicy != null && paymentRailPolicy.isBank()) {
                    throw new BadRequestException("Razorpay webhook rejected in BANK mode: unknown order " + orderId + ". New Razorpay payment obligations are not permitted.");
                }
                return Map.of("status", "order_not_found", "orderId", orderId);
            }

            Payment payment = paymentOpt.get();

            if (paymentRailPolicy != null && paymentRailPolicy.isBank()) {
                boolean isLegacy = (payment.getFundingRail() == FundingRail.RAZORPAY) || "RAZORPAY".equalsIgnoreCase(payment.getProvider());
                if (!isLegacy) {
                    log.warn("Webhook rejected in BANK mode: payment {} is not a legacy Razorpay payment (fundingRail={}, provider={})",
                            payment.getId(), payment.getFundingRail(), payment.getProvider());
                    throw new BadRequestException("Razorpay webhook rejected: payment is not a legacy Razorpay payment obligation.");
                }
                if (!orderId.equals(payment.getProviderOrderId())) {
                    log.warn("Webhook rejected in BANK mode: orderId mismatch (orderId={}, paymentProviderOrderId={})",
                            orderId, payment.getProviderOrderId());
                    throw new BadRequestException("Razorpay webhook rejected: provider order ID mismatch.");
                }
            }

            String currency = paymentNode.path("currency").asText("");
            long amountPaise = paymentNode.path("amount").asLong(0);

            // Validate currency: must be INR
            if (currency != null && !currency.isBlank() && !"INR".equalsIgnoreCase(currency)) {
                log.warn("Webhook rejected: unsupported currency {} for payment {}", currency, payment.getId());
                throw new BadRequestException("Invalid payment currency: expected INR");
            }

            // Validate amount matching: paise must match payment amount * 100
            if (amountPaise > 0) {
                BigDecimal expectedPaise = payment.getAmount().multiply(BigDecimal.valueOf(100));
                if (expectedPaise.compareTo(BigDecimal.valueOf(amountPaise)) != 0) {
                    log.warn("Webhook rejected: amount mismatch. Expected paise: {}, received: {}", expectedPaise, amountPaise);
                    throw new BadRequestException("Amount mismatch between webhook and payment record");
                }
            }

            // Idempotency: If payment already completed/success, do not fund or process again!
            if (payment.getStatus() == PaymentStatus.SUCCESS) {
                log.info("Webhook duplicate ignored: payment {} already marked SUCCESS", payment.getId());
                return Map.of("status", "already_processed", "paymentId", payment.getId());
            }

            if ("payment.captured".equals(event) || "order.paid".equals(event) || "captured".equalsIgnoreCase(status)) {
                payment.setProviderPaymentId(paymentId != null ? paymentId : "pay_webhook_" + orderId);
                payment.setStatus(PaymentStatus.SUCCESS);
                Payment saved = paymentRepository.saveAndFlush(payment);

                // Fund escrow atomically
                escrowService.fundEscrow(
                        saved.getDealId(),
                        saved.getMilestoneId(),
                        saved.getId(),
                        saved.getAmount(),
                        saved.getCurrency(),
                        saved.getBuyerId()
                );

                auditService.logEvent(
                        saved.getBuyerId(),
                        "PAYMENT_VERIFIED",
                        "PAYMENT",
                        saved.getId().toString(),
                        "Payment verified via Razorpay webhook for order " + orderId + " (paymentId: " + paymentId + ")"
                );

                Deal deal = dealRepository.findById(saved.getDealId()).orElse(null);
                if (deal != null) {
                    notificationService.sendNotification(
                            deal.getSellerId(),
                            "Milestone Payment Received (Webhook)",
                            "Payment of " + saved.getAmount() + " " + saved.getCurrency() + " verified via webhook and deposited into escrow.",
                            "/vendor/projects/" + deal.getId()
                    );
                }

                log.info("Payment {} successfully marked SUCCESS and escrow funded via webhook", saved.getId());
                return Map.of("status", "success", "paymentId", saved.getId());
            } else if ("payment.failed".equals(event) || "failed".equalsIgnoreCase(status)) {
                payment.setStatus(PaymentStatus.FAILED);
                if (paymentId != null) {
                    payment.setProviderPaymentId(paymentId);
                }
                paymentRepository.saveAndFlush(payment);

                auditService.logEvent(
                        payment.getBuyerId(),
                        "PAYMENT_FAILED",
                        "PAYMENT",
                        payment.getId().toString(),
                        "Payment failed via Razorpay webhook for order " + orderId
                );

                log.info("Payment {} marked FAILED via webhook", payment.getId());
                return Map.of("status", "failed", "paymentId", payment.getId());
            }

            return Map.of("status", "unhandled_event", "event", event);
        } catch (BadRequestException bre) {
            throw bre;
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook: {}", e.getMessage(), e);
            throw new BadRequestException("Failed to process webhook payload: " + e.getMessage());
        }
    }

    private void validatePaymentAccess(Payment payment, UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ADMIN) {
            return;
        }
        Deal deal = dealRepository.findById(payment.getDealId()).orElse(null);
        if (deal != null) {
            boolean isParty = deal.getBuyerId().equals(currentUser.getId()) || deal.getSellerId().equals(currentUser.getId());
            if (isParty) {
                return;
            }
        }
        throw new UnauthorizedException("You do not have access to view this payment");
    }

    public Map<String, Object> getPaymentConfig() {
        return Map.of(
                "configured", razorpayService.isConfigured(),
                "keyIdPresent", razorpayService.isKeyIdPresent(),
                "keySecretPresent", razorpayService.isKeySecretPresent(),
                "webhookSecretPresent", razorpayService.isWebhookSecretPresent(),
                "mode", razorpayService.getKeyMode(),
                "currency", razorpayService.getProperties().getCurrency() != null ? razorpayService.getProperties().getCurrency() : "INR",
                "sandboxMode", razorpayService.getProperties().isSandboxMode(),
                "keyId", razorpayService.isConfigured() ? razorpayService.getPublicKeySafe() : ""
        );
    }
}
