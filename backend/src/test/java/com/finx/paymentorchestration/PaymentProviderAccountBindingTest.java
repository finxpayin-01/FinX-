package com.finx.paymentorchestration;

import com.finx.agreement.service.AgreementService;
import com.finx.audit.service.AuditService;
import com.finx.banking.dto.BankStatementQuery;
import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.CreateInstructionRequest;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PaymentInstructionResponse;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class PaymentProviderAccountBindingTest {

    @Mock private PaymentInstructionRepository instructionRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private SettlementInstructionRepository settlementRepository;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private BankingProvider bankingProvider;
    @Mock private LedgerService ledgerService;
    @Mock private EscrowService escrowService;
    @Mock private AgreementService agreementService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private DealAccessService dealAccessService;

    private PaymentOrchestrationService orchestrationService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID dealId;
    private UUID milestoneId;
    private Deal testDeal;
    private Milestone testMilestone;
    private UserPrincipal buyerPrincipal;

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
                Role.BUYER, com.finx.common.enums.UserStatus.ACTIVE,
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
        testMilestone.setStatus(com.finx.milestone.entity.MilestoneStatus.APPROVED);
        testMilestone.setAmount(new BigDecimal("50000.0000"));

        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(testDeal));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(testDeal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(testMilestone));
        when(milestoneRepository.findByDealIdOrderBySequenceAsc(dealId)).thenReturn(List.of(testMilestone));
        when(agreementService.isAgreementFullySigned(dealId)).thenReturn(true);
        when(bankingProvider.getProviderName()).thenReturn("MOCK");
        when(instructionRepository.findByDealIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(instructionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(instructionRepository.save(any(PaymentInstruction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orchestrationService = new PaymentOrchestrationService(
                instructionRepository,
                transactionRepository,
                dealRepository,
                milestoneRepository,
                bankingProvider,
                ledgerService,
                escrowService,
                agreementService,
                auditService,
                notificationService,
                dealAccessService
        );
    }

    @Test
    @DisplayName("Part P: MockBankingProvider contract test for providerAccountId consistency and VA distinction")
    void testMockBankingProviderAccountScopeContract() {
        MockBankingProvider mockProvider = new MockBankingProvider("test-secret");

        // 1. PaymentInstructionResult returns MOCK_ESCROW_ACCOUNT
        PaymentInstructionRequest payReq = new PaymentInstructionRequest(
                dealId, milestoneId, "PI-REF-001", BigDecimal.valueOf(50000), "INR", "Buyer Corp", "Vendor Corp");
        PaymentInstructionResult payRes = mockProvider.createPaymentInstruction(payReq);

        assertThat(payRes.providerAccountId()).isEqualTo(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        assertThat(payRes.virtualAccountNumber()).isNotBlank();
        // Crucial: providerAccountId != virtualAccountNumber
        assertThat(payRes.providerAccountId()).isNotEqualTo(payRes.virtualAccountNumber());

        // 2. SettlementResult returns MOCK_ESCROW_ACCOUNT
        SettlementRequest settlReq = new SettlementRequest(
                "SETTL-REF-001", dealId, milestoneId, BigDecimal.valueOf(50000), "INR",
                "Vendor Corp", "1234567890", "FINX0001001", "IDEMP-SETTL-001");
        SettlementResult settlRes = mockProvider.initiateSettlement(settlReq);

        assertThat(settlRes.providerAccountId()).isEqualTo(MockBankingProvider.MOCK_ESCROW_ACCOUNT);

        // 3. fetchStatementPage uses same providerAccountId
        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(Instant.now().minusSeconds(3600))
                .windowEnd(Instant.now())
                .pageSize(50)
                .build();
        var page = mockProvider.fetchStatementPage(query);
        assertThat(page.getProviderAccountId()).isEqualTo(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
    }

    @Test
    @DisplayName("Part Q: Payment creation binds providerAccountId from BankingProvider to PaymentInstruction")
    void testPaymentCreationBindsProviderAccountId() {
        PaymentInstructionResult mockResult = new PaymentInstructionResult(
                "MOCK-INS-001", "FINX999888777", "FINX0001001", "CREATED",
                "https://pay.mock", "{}", MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        when(bankingProvider.createPaymentInstruction(any())).thenReturn(mockResult);

        CreateInstructionRequest request = new CreateInstructionRequest(dealId, milestoneId, "IDEMP-KEY-001");
        PaymentInstructionResponse response = orchestrationService.createPaymentInstruction(request, buyerPrincipal);

        assertThat(response).isNotNull();

        ArgumentCaptor<PaymentInstruction> captor = ArgumentCaptor.forClass(PaymentInstruction.class);
        verify(instructionRepository).save(captor.capture());

        PaymentInstruction saved = captor.getValue();
        assertThat(saved.getProviderAccountId()).isEqualTo(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        assertThat(saved.getVirtualAccountNumber()).isEqualTo("FINX999888777");
        assertThat(saved.getProviderAccountId()).isNotEqualTo(saved.getVirtualAccountNumber());
    }

    @Test
    @DisplayName("Part Q & S: Missing providerAccountId causes fail-closed without side effects or fallback to VA")
    void testMissingProviderAccountIdFailsClosed() {
        // Banking provider returns result with null providerAccountId
        PaymentInstructionResult badResult = new PaymentInstructionResult(
                "MOCK-INS-002", "FINX111222333", "FINX0001001", "CREATED",
                "https://pay.mock", "{}", null);
        when(bankingProvider.createPaymentInstruction(any())).thenReturn(badResult);

        CreateInstructionRequest request = new CreateInstructionRequest(dealId, milestoneId, "IDEMP-KEY-BAD-001");

        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(request, buyerPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerAccountId");

        // Assert 0 financial writes and 0 instruction saves
        verify(instructionRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verify(ledgerService, never()).postJournalEntry(any());
        verify(escrowService, never()).fundEscrowForInstruction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Part Q: Blank providerAccountId causes fail-closed")
    void testBlankProviderAccountIdFailsClosed() {
        PaymentInstructionResult blankResult = new PaymentInstructionResult(
                "MOCK-INS-003", "FINX111222333", "FINX0001001", "CREATED",
                "https://pay.mock", "{}", "   ");
        when(bankingProvider.createPaymentInstruction(any())).thenReturn(blankResult);

        CreateInstructionRequest request = new CreateInstructionRequest(dealId, milestoneId, "IDEMP-KEY-BLANK");

        assertThatThrownBy(() -> orchestrationService.createPaymentInstruction(request, buyerPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerAccountId");

        verify(instructionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Part R: SettlementInstruction entity supports providerAccountId read/write")
    void testSettlementInstructionProviderAccountIdSupport() {
        SettlementInstruction instruction = new SettlementInstruction();
        assertThat(instruction.getProviderAccountId()).isNull();

        instruction.setProviderAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
        assertThat(instruction.getProviderAccountId()).isEqualTo(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
    }
}
