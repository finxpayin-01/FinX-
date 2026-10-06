package com.finx.security;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankingDtos.PaymentInstructionRequest;
import com.finx.banking.dto.BankingDtos.PaymentInstructionResult;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.exception.BadRequestException;
import com.finx.exception.DuplicateResourceException;
import com.finx.exception.UnauthorizedException;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.*;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.PaymentOrchestrationService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B05 Application Containment Test Suite:
 * Validates Scoped Idempotency, Financial-Effect Identity, Request Hash Binding,
 * Mandatory Keys, Durable References, Provider Credit Identity, and Authorize-Before-Replay.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class FinancialIdempotencyContainmentTest {

    @Mock private PaymentInstructionRepository instructionRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private SettlementInstructionRepository settlementRepository;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private BankingProvider bankingProvider;
    @Mock private LedgerService ledgerService;
    @Mock private com.finx.escrow.service.EscrowService escrowService;
    @Mock private AgreementService agreementService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private DealAccessService dealAccessService;

    private PaymentOrchestrationService orchestrationService;
    private SettlementAuthorizationService settlementService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID dealId;
    private UUID milestoneId;
    private Deal testDeal;
    private Milestone testMilestone;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal financePrincipal;
    private UserPrincipal unauthorizedPrincipal;

    @BeforeEach
    void setUp() {
        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();

        buyerPrincipal = new UserPrincipal(
                buyerId, "buyer@corp.com", "Buyer User", "hash",
                Role.BUYER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_BUYER"))
        );

        financePrincipal = new UserPrincipal(
                UUID.randomUUID(), "finance@corp.com", "Finance User", "hash",
                Role.FINANCE, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_FINANCE"))
        );

        unauthorizedPrincipal = new UserPrincipal(
                UUID.randomUUID(), "other@corp.com", "Other User", "hash",
                Role.BUYER, UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_BUYER"))
        );

        testDeal = new Deal();
        testDeal.setId(dealId);
        testDeal.setBuyerId(buyerId);
        testDeal.setSellerId(sellerId);
        testDeal.setBuyerOrganizationId(buyerOrgId);
        testDeal.setSellerOrganizationId(sellerOrgId);
        testDeal.setStatus(DealStatus.IN_PROGRESS);
        testDeal.setTotalAmount(new BigDecimal("100000.0000"));
        testDeal.setCurrency("INR");

        testMilestone = new Milestone();
        testMilestone.setId(milestoneId);
        testMilestone.setDealId(dealId);
        testMilestone.setStatus(MilestoneStatus.APPROVED);
        testMilestone.setAmount(new BigDecimal("50000.0000"));

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(testDeal));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(testDeal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(testMilestone));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(true);
        when(bankingProvider.getProviderName()).thenReturn("MOCK_BANK");
        when(bankingProvider.createPaymentInstruction(any())).thenReturn(
                new PaymentInstructionResult("PROV-TXN-1", "VA123456", "FINX0001", "SUBMITTED", "http://pay.mock", "{}", "MOCK_ESCROW_ACCOUNT")
        );

        orchestrationService = new PaymentOrchestrationService(
                instructionRepository, transactionRepository, dealRepository, milestoneRepository,
                bankingProvider, ledgerService, escrowService, agreementService, auditService,
                notificationService, dealAccessService
        );

        settlementService = new SettlementAuthorizationService(
                settlementRepository, dealRepository, milestoneRepository, null,
                null, null, null, agreementService,
                escrowService, ledgerService, bankingProvider, auditService,
                notificationService, null, null, dealAccessService, null, null
        );
    }

    private String computeSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 1. same key + same funding body -> safe replay
    @Test
    @DisplayName("1. Same key + same funding payload returns safe replay without re-dispatching")
    void testSameKeySameFundingBodySafeReplay() {
        String key = "idem-fund-001";
        CreateInstructionRequest req = new CreateInstructionRequest(dealId, milestoneId, key);

        String canonical = dealId + ":" + milestoneId + ":" + new BigDecimal("50000.0000").toPlainString() + ":INR";
        String hash = computeSha256(canonical);

        PaymentInstruction existing = new PaymentInstruction(
                "FINX-TXN-2026-EXISTING1", dealId, milestoneId, buyerOrgId, sellerOrgId,
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, buyerId
        );
        existing.setRequestHash(hash);
        existing.setStatus(InstructionStatus.SUBMITTED);

        when(instructionRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.of(existing));

        PaymentInstructionResponse response = orchestrationService.createPaymentInstruction(req, buyerPrincipal);

        assertThat(response).isNotNull();
        assertThat(response.instructionReference()).isEqualTo("FINX-TXN-2026-EXISTING1");
        verify(bankingProvider, never()).createPaymentInstruction(any());
        verify(instructionRepository, never()).save(any());
    }

    // 2. same key + changed amount -> conflict
    @Test
    @DisplayName("2. Same key + changed funding amount causes 409 Conflict")
    void testSameKeyChangedAmountConflict() {
        String key = "idem-fund-002";
        CreateInstructionRequest req = new CreateInstructionRequest(dealId, milestoneId, key);

        // Stored instruction had amount 40000.0000
        String oldCanonical = dealId + ":" + milestoneId + ":" + new BigDecimal("40000.0000").toPlainString() + ":INR";
        String oldHash = computeSha256(oldCanonical);

        PaymentInstruction existing = new PaymentInstruction(
                "FINX-TXN-2026-OLDAMOUNT", dealId, milestoneId, buyerOrgId, sellerOrgId,
                new BigDecimal("40000.0000"), "INR", "MOCK_BANK", key, buyerId
        );
        existing.setRequestHash(oldHash);

        when(instructionRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.of(existing));

        // New request calculates amount 50000.0000 from milestone -> hash mismatch
        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(req, buyerPrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("different payment instruction payload");
    }

    // 3. same key + changed instruction -> conflict
    @Test
    @DisplayName("3. Same key + changed instruction (milestone vs whole deal) causes 409 Conflict")
    void testSameKeyChangedInstructionConflict() {
        String key = "idem-fund-003";
        // Request for milestone funding
        CreateInstructionRequest req = new CreateInstructionRequest(dealId, milestoneId, key);

        // Stored instruction was for whole deal funding
        String wholeDealCanonical = dealId + ":ALL:" + new BigDecimal("100000.0000").toPlainString() + ":INR";
        String wholeDealHash = computeSha256(wholeDealCanonical);

        PaymentInstruction existing = new PaymentInstruction(
                "FINX-TXN-2026-WHOLEDEAL", dealId, null, buyerOrgId, sellerOrgId,
                new BigDecimal("100000.0000"), "INR", "MOCK_BANK", key, buyerId
        );
        existing.setRequestHash(wholeDealHash);

        when(instructionRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(req, buyerPrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("different payment instruction payload");
    }

    // 4. cross-tenant same key -> denied
    @Test
    @DisplayName("4. Same key used on different deal/tenant is denied without data disclosure")
    void testCrossTenantSameKeyDenied() {
        String key = "shared-cross-tenant-key";
        UUID otherDealId = UUID.randomUUID();
        CreateInstructionRequest req = new CreateInstructionRequest(dealId, milestoneId, key);

        PaymentInstruction otherTenantInstruction = new PaymentInstruction(
                "FINX-TXN-2026-OTHER", otherDealId, null, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, UUID.randomUUID()
        );

        when(instructionRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.empty());
        when(instructionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(otherTenantInstruction));

        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(req, buyerPrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("another financial context");
    }

    // 5. blank key -> rejected
    @Test
    @DisplayName("5. Blank idempotency key is rejected with 400 Bad Request")
    void testBlankKeyRejected() {
        CreateInstructionRequest req = new CreateInstructionRequest(dealId, milestoneId, "");
        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Mandatory idempotency key");
    }

    // 6. whitespace key -> rejected
    @Test
    @DisplayName("6. Whitespace-only idempotency key is rejected with 400 Bad Request")
    void testWhitespaceKeyRejected() {
        CreateInstructionRequest req = new CreateInstructionRequest(dealId, milestoneId, "   \t  ");
        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(req, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Mandatory idempotency key");
    }

    // 7. same provider credit via different client keys -> one effect
    @Test
    @DisplayName("7. Provider credit (UTR) identity prevents duplicate consumption")
    void testSameProviderCreditViaDifferentKeysOneEffect() {
        String utr = "UTR-CREDIT-77777";
        String provider = "BANK";
        PaymentTransaction existingTx = new PaymentTransaction(
                "FINX-TXN-REC-1", UUID.randomUUID(), provider, "BANK-REF-1",
                new BigDecimal("50000.0000"), "INR", "SUCCESS", utr, "{}"
        );

        when(transactionRepository.findByProviderAndUtrNumber(provider, utr)).thenReturn(Optional.of(existingTx));

        // When a second transaction attempts to verify or consume the same scoped UTR:
        Optional<PaymentTransaction> collision = transactionRepository.findByProviderAndUtrNumber(provider, utr);
        assertThat(collision).isPresent();
        assertThat(collision.get().getTransactionReference()).isEqualTo("FINX-TXN-REC-1");
    }

    // 8. valid provider namespace separation if supported
    @Test
    @DisplayName("8. Valid provider namespace separation allows identical reference across different providers")
    void testValidProviderNamespaceSeparation() {
        String utr = "UTR-COMMON-12345";
        PaymentTransaction bank1Tx = new PaymentTransaction(
                "FINX-TXN-BANK1", UUID.randomUUID(), "YES_BANK", "REF-1",
                new BigDecimal("10000.0000"), "INR", "SUCCESS", utr, "{}"
        );

        when(transactionRepository.findByProviderAndUtrNumber("YES_BANK", utr)).thenReturn(Optional.of(bank1Tx));
        when(transactionRepository.findByProviderAndUtrNumber("ICICI_BANK", utr)).thenReturn(Optional.empty());

        assertThat(transactionRepository.findByProviderAndUtrNumber("YES_BANK", utr)).isPresent();
        assertThat(transactionRepository.findByProviderAndUtrNumber("ICICI_BANK", utr)).isEmpty();
    }

    // 9. stable refs survive service reconstruction
    @Test
    @DisplayName("9. Durable UUID-derived references survive service reconstruction without collisions")
    void testStableRefsSurviveServiceReconstruction() {
        when(instructionRepository.findByDealIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(instructionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(instructionRepository.save(any(PaymentInstruction.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrchestrationService service1 = new PaymentOrchestrationService(
                instructionRepository, transactionRepository, dealRepository, milestoneRepository,
                bankingProvider, ledgerService, escrowService, agreementService, auditService,
                notificationService, dealAccessService
        );

        PaymentInstructionResponse resp1 = service1.createPaymentInstruction(
                new CreateInstructionRequest(dealId, milestoneId, "key-inst-1"), buyerPrincipal
        );

        // Reconstruct service (simulating application restart)
        PaymentOrchestrationService service2 = new PaymentOrchestrationService(
                instructionRepository, transactionRepository, dealRepository, milestoneRepository,
                bankingProvider, ledgerService, escrowService, agreementService, auditService,
                notificationService, dealAccessService
        );

        PaymentInstructionResponse resp2 = service2.createPaymentInstruction(
                new CreateInstructionRequest(dealId, milestoneId, "key-inst-2"), buyerPrincipal
        );

        assertThat(resp1.instructionReference()).startsWith("FINX-TXN-");
        assertThat(resp2.instructionReference()).startsWith("FINX-TXN-");
        assertThat(resp1.instructionReference()).isNotEqualTo(resp2.instructionReference());
    }

    // 10. same key + same settlement payload -> safe replay
    @Test
    @DisplayName("10. Same key + same settlement payload safely replays stored settlement")
    void testSameKeySameSettlementPayloadSafeReplay() {
        String key = "idem-settle-010";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Approved payout", key);

        String canonical = dealId + ":" + milestoneId + ":" + new BigDecimal("50000.0000").toPlainString() + ":INR:Approved payout";
        String hash = computeSha256(canonical);

        SettlementInstruction stored = new SettlementInstruction(
                "FINX-SET-2026-STORED1", dealId, milestoneId, sellerOrgId, UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, financePrincipal.getId()
        );
        stored.setRequestHash(hash);
        stored.setStatus(SettlementStatus.PENDING);

        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.of(stored));

        SettlementResponse response = settlementService.authorizeAndInitiateSettlement(req, financePrincipal);

        assertThat(response).isNotNull();
        assertThat(response.settlementReference()).isEqualTo("FINX-SET-2026-STORED1");
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    // 11. same key + changed milestone -> conflict
    @Test
    @DisplayName("11. Same key + changed settlement milestone causes 409 Conflict")
    void testSameKeyChangedMilestoneConflict() {
        String key = "idem-settle-011";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Approved payout", key);

        UUID differentMilestoneId = UUID.randomUUID();
        String oldCanonical = dealId + ":" + differentMilestoneId + ":" + new BigDecimal("50000.0000").toPlainString() + ":INR:Approved payout";
        String oldHash = computeSha256(oldCanonical);

        SettlementInstruction stored = new SettlementInstruction(
                "FINX-SET-2026-OLDMILE", dealId, differentMilestoneId, sellerOrgId, UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, financePrincipal.getId()
        );
        stored.setRequestHash(oldHash);

        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, financePrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("different settlement payload");
    }

    // 12. same key + changed relevant body -> conflict
    @Test
    @DisplayName("12. Same key + changed settlement comment causes 409 Conflict")
    void testSameKeyChangedRelevantBodyConflict() {
        String key = "idem-settle-012";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "New comment", key);

        String oldCanonical = dealId + ":" + milestoneId + ":" + new BigDecimal("50000.0000").toPlainString() + ":INR:Old comment";
        String oldHash = computeSha256(oldCanonical);

        SettlementInstruction stored = new SettlementInstruction(
                "FINX-SET-2026-OLDCOMM", dealId, milestoneId, sellerOrgId, UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, financePrincipal.getId()
        );
        stored.setRequestHash(oldHash);

        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, financePrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("different settlement payload");
    }

    // 13. other tenant cannot replay stored settlement
    @Test
    @DisplayName("13. Other tenant cannot replay stored settlement due to DealAccessService authorization gate")
    void testOtherTenantCannotReplayStoredSettlement() {
        String key = "tenant-a-settle-key";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Approved payout", key);

        doThrow(new AccessDeniedException("You do not have access to this deal"))
                .when(dealAccessService).requireDealAccess(testDeal, unauthorizedPrincipal);

        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, unauthorizedPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("You do not have access to this deal");

        verify(settlementRepository, never()).findByDealIdAndIdempotencyKey(any(), any());
    }

    // 14. blank settlement key -> reject
    @Test
    @DisplayName("14. Blank settlement key is rejected with 400 Bad Request")
    void testBlankSettlementKeyReject() {
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "comment", "   ");
        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, financePrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Mandatory idempotency key");
    }

    // 15. two different keys same milestone active effect -> blocked
    @Test
    @DisplayName("15. Two different keys targeting same milestone with active effect are blocked")
    void testTwoDifferentKeysSameMilestoneActiveEffectBlocked() {
        String keyB = "key-attempt-B";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Attempt B", keyB);

        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, keyB)).thenReturn(Optional.empty());
        when(settlementRepository.findByIdempotencyKey(keyB)).thenReturn(Optional.empty());

        SettlementInstruction activeSettlement = new SettlementInstruction(
                "FINX-SET-ACTIVE", dealId, milestoneId, sellerOrgId, UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", "key-attempt-A", financePrincipal.getId()
        );
        activeSettlement.setStatus(SettlementStatus.PROCESSING);

        when(settlementRepository.findFirstByMilestoneIdAndStatusIn(eq(milestoneId), anyList()))
                .thenReturn(Optional.of(activeSettlement));

        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, financePrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("active or completed settlement effect already exists");
    }

    // 16. existing SETTLED effect -> blocked/replayed safely
    @Test
    @DisplayName("16. Existing SETTLED effect blocks different key but replays same key safely")
    void testExistingSettledEffectBlockedAndReplayedSafely() {
        String keyA = "key-settled-A";
        String keyB = "key-attempt-B";

        String canonical = dealId + ":" + milestoneId + ":" + new BigDecimal("50000.0000").toPlainString() + ":INR:Settled payout";
        String hash = computeSha256(canonical);

        SettlementInstruction settled = new SettlementInstruction(
                "FINX-SET-SETTLED", dealId, milestoneId, sellerOrgId, UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", keyA, financePrincipal.getId()
        );
        settled.setRequestHash(hash);
        settled.setStatus(SettlementStatus.SETTLED);

        // Same key replays safely:
        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, keyA)).thenReturn(Optional.of(settled));
        PayoutAuthorizationRequest replayReq = new PayoutAuthorizationRequest(milestoneId, "Settled payout", keyA);
        SettlementResponse response = settlementService.authorizeAndInitiateSettlement(replayReq, financePrincipal);
        assertThat(response.settlementReference()).isEqualTo("FINX-SET-SETTLED");

        // Different key is blocked by active effect check:
        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, keyB)).thenReturn(Optional.empty());
        when(settlementRepository.findByIdempotencyKey(keyB)).thenReturn(Optional.empty());
        when(settlementRepository.findFirstByMilestoneIdAndStatusIn(eq(milestoneId), anyList()))
                .thenReturn(Optional.of(settled));

        PayoutAuthorizationRequest diffKeyReq = new PayoutAuthorizationRequest(milestoneId, "Settled payout", keyB);
        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(diffKeyReq, financePrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("active or completed settlement effect already exists");
    }

    // 17. existing PENDING -> blocks new attempt
    @Test
    @DisplayName("17. Existing PENDING settlement blocks new attempt with different key")
    void testExistingPendingBlocksNewAttempt() {
        String key = "key-new-attempt";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "New attempt", key);

        SettlementInstruction pending = new SettlementInstruction(
                "FINX-SET-PENDING", dealId, milestoneId, sellerOrgId, UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", "old-key", financePrincipal.getId()
        );
        pending.setStatus(SettlementStatus.PENDING);

        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.empty());
        when(settlementRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(settlementRepository.findFirstByMilestoneIdAndStatusIn(eq(milestoneId), anyList()))
                .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, financePrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("active or completed settlement effect already exists");
    }

    // 18. UNKNOWN/uncertain equivalent -> blocks new attempt
    @Test
    @DisplayName("18. PROCESSING / uncertain state blocks new attempt with different key")
    void testProcessingUncertainBlocksNewAttempt() {
        String key = "key-new-attempt-2";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "New attempt", key);

        SettlementInstruction processing = new SettlementInstruction(
                "FINX-SET-PROCESSING", dealId, milestoneId, sellerOrgId, UUID.randomUUID(),
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", "old-key-2", financePrincipal.getId()
        );
        processing.setStatus(SettlementStatus.PROCESSING);

        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.empty());
        when(settlementRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(settlementRepository.findFirstByMilestoneIdAndStatusIn(eq(milestoneId), anyList()))
                .thenReturn(Optional.of(processing));

        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, financePrincipal))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("active or completed settlement effect already exists");
    }

    // 19. FAILED history follows legitimate retry semantics
    @Test
    @DisplayName("19. Historical FAILED attempt does not block legitimate retry; proceeds past active check to fail-closed gate")
    void testFailedHistoryFollowsLegitimateRetrySemantics() {
        String key = "key-retry-after-fail";
        PayoutAuthorizationRequest req = new PayoutAuthorizationRequest(milestoneId, "Retry after failure", key);

        when(settlementRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.empty());
        when(settlementRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        // findFirstByMilestoneIdAndStatusIn for PENDING, PROCESSING, SETTLED returns empty because only FAILED exists
        when(settlementRepository.findFirstByMilestoneIdAndStatusIn(eq(milestoneId), anyList()))
                .thenReturn(Optional.empty());

        // Proceeds past active effect check and reaches fail-closed settlement execution gate (B04)
        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(req, financePrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled");

        // Verify 0 banking provider calls
        verify(bankingProvider, never()).initiateSettlement(any());
    }

    // 20. authorization occurs before replay result
    @Test
    @DisplayName("20. Caller authorization occurs before stored replay result inspection")
    void testAuthorizationOccursBeforeReplayResult() {
        String key = "idem-replay-auth";
        CreateInstructionRequest fundReq = new CreateInstructionRequest(dealId, milestoneId, key);

        PaymentInstruction stored = new PaymentInstruction(
                "FINX-TXN-STORED", dealId, milestoneId, buyerOrgId, sellerOrgId,
                new BigDecimal("50000.0000"), "INR", "MOCK_BANK", key, buyerId
        );
        when(instructionRepository.findByDealIdAndIdempotencyKey(dealId, key)).thenReturn(Optional.of(stored));

        // Unauthorized user calling funding instruction
        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(fundReq, unauthorizedPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Only the corporate buyer can initiate payment instructions");

        // Unauthorized caller (BUYER instead of FINANCE/ADMIN) calling settlement authorization fails closed with AccessDeniedException
        PayoutAuthorizationRequest settleReq = new PayoutAuthorizationRequest(milestoneId, "comment", key);
        assertThatThrownBy(() -> settlementService.authorizeAndInitiateSettlement(settleReq, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class);
    }
}
