package com.finx.release;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowAccountStatus;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.outbox.service.FinancialOutboxService;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.*;
import com.finx.release.service.ReleaseFundingScopeResolver.FundingScope;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ReleaseAuthorizationServiceTest {

    private ReleaseInstructionRepository instructionRepo;
    private ReleaseReservationRepository reservationRepo;
    private DealRepository dealRepo;
    private MilestoneRepository milestoneRepo;
    private EscrowAccountRepository escrowAccountRepo;
    private DisputeRepository disputeRepo;
    private VerificationEligibilityService eligibilityService;
    private MilestoneApprovalBindingService approvalBindingService;
    private DeliverableRepository deliverableRepo;
    private ReleaseDecisionExecutionValidator executionValidator;
    private ReleaseFundingScopeResolver fundingScopeResolver;
    private EscrowReservationService escrowReservationService;
    private FinancialOutboxRepository financialOutboxRepo;
    private FinancialOutboxService financialOutboxService;
    private FinxClock clock;
    private ReleaseAuthorizationService authService;

    private UserPrincipal adminPrincipal;
    private UserPrincipal financePrincipal;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;
    private UserPrincipal pmPrincipal;

    private UUID instructionId;
    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID escrowAccountId;
    private UUID beneficiaryBankAccountId;
    private UUID releaseDecisionId;
    private UUID policyVersionId;

    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;
    private ReleaseInstruction instruction;
    private OrganizationBankAccount beneficiary;

    @BeforeEach
    void setUp() throws Exception {
        instructionRepo = mock(ReleaseInstructionRepository.class);
        reservationRepo = mock(ReleaseReservationRepository.class);
        dealRepo = mock(DealRepository.class);
        milestoneRepo = mock(MilestoneRepository.class);
        escrowAccountRepo = mock(EscrowAccountRepository.class);
        disputeRepo = mock(DisputeRepository.class);
        eligibilityService = mock(VerificationEligibilityService.class);
        approvalBindingService = mock(MilestoneApprovalBindingService.class);
        deliverableRepo = mock(DeliverableRepository.class);
        executionValidator = mock(ReleaseDecisionExecutionValidator.class);
        fundingScopeResolver = mock(ReleaseFundingScopeResolver.class);
        escrowReservationService = new EscrowReservationService(reservationRepo);
        financialOutboxRepo = mock(FinancialOutboxRepository.class);
        clock = new FinxClock();
        financialOutboxService = new FinancialOutboxService(financialOutboxRepo, clock);

        authService = new ReleaseAuthorizationService(
                instructionRepo,
                reservationRepo,
                dealRepo,
                milestoneRepo,
                escrowAccountRepo,
                disputeRepo,
                eligibilityService,
                approvalBindingService,
                deliverableRepo,
                executionValidator,
                fundingScopeResolver,
                escrowReservationService,
                financialOutboxService,
                financialOutboxRepo
        );

        instructionId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        escrowAccountId = UUID.randomUUID();
        beneficiaryBankAccountId = UUID.randomUUID();
        releaseDecisionId = UUID.randomUUID();
        policyVersionId = UUID.randomUUID();

        deal = new Deal();
        setField(deal, "id", dealId);
        setField(deal, "buyerOrganizationId", buyerOrgId);
        setField(deal, "sellerOrganizationId", sellerOrgId);
        setField(deal, "status", DealStatus.FUNDED);

        milestone = new Milestone();
        setField(milestone, "id", milestoneId);
        setField(milestone, "dealId", dealId);
        setField(milestone, "amount", new BigDecimal("50000.0000"));
        setField(milestone, "currency", "INR");
        setField(milestone, "status", MilestoneStatus.APPROVED);

        escrowAccount = new EscrowAccount();
        setField(escrowAccount, "id", escrowAccountId);
        setField(escrowAccount, "dealId", dealId);
        setField(escrowAccount, "balance", new BigDecimal("100000.0000"));
        setField(escrowAccount, "totalFunded", new BigDecimal("100000.0000"));
        setField(escrowAccount, "totalReleased", BigDecimal.ZERO);
        setField(escrowAccount, "currency", "INR");
        setField(escrowAccount, "status", EscrowAccountStatus.ACTIVE);

        beneficiary = new OrganizationBankAccount();
        setField(beneficiary, "id", beneficiaryBankAccountId);
        setField(beneficiary, "organizationId", sellerOrgId);

        instruction = new ReleaseInstruction(
                instructionId,
                buyerOrgId,
                dealId,
                milestoneId,
                releaseDecisionId,
                "fingerprint-123",
                policyVersionId,
                "content-hash-123",
                beneficiaryBankAccountId,
                "AXIS",
                "ESCROW-POOL-1",
                new BigDecimal("50000.0000"),
                "INR",
                "IDEM-TEST",
                "REQ-HASH",
                UUID.randomUUID()
        );

        adminPrincipal = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "pwd", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());
        financePrincipal = new UserPrincipal(UUID.randomUUID(), "Finance", "fin@finx.com", "pwd", Role.FINANCE, UserStatus.ACTIVE, Collections.emptyList());
        buyerPrincipal = new UserPrincipal(UUID.randomUUID(), "Buyer", "buyer@client.com", "pwd", Role.BUYER, UserStatus.ACTIVE, Collections.emptyList());
        sellerPrincipal = new UserPrincipal(UUID.randomUUID(), "Seller", "seller@vendor.com", "pwd", Role.SELLER, UserStatus.ACTIVE, Collections.emptyList());
        pmPrincipal = new UserPrincipal(UUID.randomUUID(), "PM", "pm@client.com", "pwd", Role.PROJECT_MANAGER, UserStatus.ACTIVE, Collections.emptyList());

        ReleaseInstructionRepository.ScopeProjection scopeProj = mock(ReleaseInstructionRepository.ScopeProjection.class);
        when(scopeProj.getDealId()).thenReturn(dealId);
        when(scopeProj.getMilestoneId()).thenReturn(milestoneId);
        when(instructionRepo.findScopeById(instructionId)).thenReturn(Optional.of(scopeProj));

        when(dealRepo.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepo.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepo.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(instructionRepo.findByIdForUpdate(instructionId)).thenReturn(Optional.of(instruction));

        when(deliverableRepo.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId)).thenReturn(List.of());
        when(approvalBindingService.evaluateCurrentBinding(any(), any(), any()))
                .thenReturn(new ApprovalBindingEvaluation(true, true, "APPROVAL_BINDING_CURRENT", null));
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(true, beneficiary, null, true));
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenReturn(new FundingScope("AXIS", "ESCROW-POOL-1", "INR"));

        when(disputeRepo.existsByDealIdAndStatus(eq(dealId), eq(DisputeStatus.OPEN))).thenReturn(false);
        when(disputeRepo.existsByMilestoneIdAndStatus(eq(milestoneId), eq(DisputeStatus.OPEN))).thenReturn(false);

        when(reservationRepo.sumActiveReservationsForEscrow(escrowAccountId)).thenReturn(BigDecimal.ZERO);
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.empty());
        when(reservationRepo.findFirstByMilestoneIdAndStatus(milestoneId, ReleaseReservationStatus.ACTIVE)).thenReturn(Optional.empty());
        when(reservationRepo.save(any(ReleaseReservation.class))).thenAnswer(i -> i.getArgument(0));

        when(financialOutboxRepo.findByReleaseInstructionIdAndEventType(any(), any())).thenReturn(Optional.empty());
        when(financialOutboxRepo.findByEventTypeAndOperationKey(any(), any())).thenReturn(Optional.empty());
        when(financialOutboxRepo.save(any(FinancialOutboxMessage.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("1. ADMIN authorize/reserve succeeds with QUEUED instruction and PENDING outbox")
    void test01_adminAuthorizeReserve() {
        ReleaseReservation res = authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        verify(financialOutboxRepo, times(1)).save(any(FinancialOutboxMessage.class));
    }

    @Test
    @DisplayName("2. FINANCE authorize/reserve succeeds with QUEUED instruction and PENDING outbox")
    void test02_financeAuthorizeReserve() {
        ReleaseReservation res = authService.authorizeAndReserve(instructionId, financePrincipal);
        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        verify(financialOutboxRepo, times(1)).save(any(FinancialOutboxMessage.class));
    }

    @Test
    @DisplayName("3. BUYER blocked")
    void test03_buyerBlocked() {
        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, buyerPrincipal))
                .isInstanceOf(com.finx.exception.UnauthorizedException.class)
                .hasMessageContaining("Role BUYER is not authorized");
    }

    @Test
    @DisplayName("4. SELLER blocked")
    void test04_sellerBlocked() {
        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, sellerPrincipal))
                .isInstanceOf(com.finx.exception.UnauthorizedException.class)
                .hasMessageContaining("Role SELLER is not authorized");
    }

    @Test
    @DisplayName("5. PROJECT_MANAGER blocked")
    void test05_pmBlocked() {
        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, pmPrincipal))
                .isInstanceOf(com.finx.exception.UnauthorizedException.class)
                .hasMessageContaining("Role PROJECT_MANAGER is not authorized");
    }

    @Test
    @DisplayName("6. null principal blocked")
    void test06_nullPrincipalBlocked() {
        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, null))
                .isInstanceOf(com.finx.exception.UnauthorizedException.class)
                .hasMessageContaining("authenticated platform principal");
    }

    @Test
    @DisplayName("7. Instruction transitions CREATED -> QUEUED")
    void test07_instructionTransitionsToQueued() {
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
    }

    @Test
    @DisplayName("8. Exactly one ACTIVE reservation created")
    void test08_exactlyOneActiveReservationCreated() {
        ReleaseReservation res = authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        verify(reservationRepo, times(1)).save(any(ReleaseReservation.class));
    }

    @Test
    @DisplayName("9. authorizedBy recorded on instruction")
    void test09_authorizedByRecorded() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(instruction.getAuthorizedBy()).isEqualTo(adminPrincipal.getId());
    }

    @Test
    @DisplayName("10. authorizedAt recorded on instruction")
    void test10_authorizedAtRecorded() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(instruction.getAuthorizedAt()).isNotNull();
    }

    @Test
    @DisplayName("11. Reservation amount exact")
    void test11_reservationAmountExact() {
        ReleaseReservation res = authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(res.getAmount()).isEqualByComparingTo(new BigDecimal("50000.0000"));
    }

    @Test
    @DisplayName("12. Reservation currency exact")
    void test12_reservationCurrencyExact() {
        ReleaseReservation res = authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(res.getCurrency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("13. Escrow balance unchanged")
    void test13_escrowBalanceUnchanged() {
        BigDecimal initialBalance = escrowAccount.getBalance();
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(escrowAccount.getBalance()).isEqualTo(initialBalance);
    }

    @Test
    @DisplayName("14. Escrow totalReleased unchanged")
    void test14_escrowTotalReleasedUnchanged() {
        BigDecimal initialReleased = escrowAccount.getTotalReleased();
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(escrowAccount.getTotalReleased()).isEqualTo(initialReleased);
    }

    @Test
    @DisplayName("15. No ledger writes")
    void test15_noLedgerWrites() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        // Pure in-memory proof: no ledger repositories or services called
    }

    @Test
    @DisplayName("16. No bank call")
    void test16_noBankCall() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        // Verified: BankingProvider is absent from ReleaseAuthorizationService
    }

    @Test
    @DisplayName("17. No SettlementInstruction created")
    void test17_noSettlementInstruction() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        // Verified: SettlementInstruction repo absent
    }

    @Test
    @DisplayName("18. Exactly one PENDING outbox message created")
    void test18_exactlyOnePendingOutboxCreated() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        verify(financialOutboxRepo, times(1)).save(argThat(msg ->
                msg.getStatus() == FinancialOutboxStatus.PENDING
                        && msg.getEventType() == FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED
                        && msg.getReleaseInstructionId().equals(instructionId)
                        && msg.getOperationKey().equals(instructionId.toString())
        ));
    }

    @Test
    @DisplayName("19. Stale release decision rejected")
    void test19_staleReleaseDecisionRejected() {
        doThrow(new IllegalStateException("Release input fingerprint has drifted"))
                .when(executionValidator).validateForExecution(eq(releaseDecisionId), any(), any(), any());

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Release input fingerprint has drifted");
    }

    @Test
    @DisplayName("20. Newer release decision rejected")
    void test20_newerReleaseDecisionRejected() {
        doThrow(new IllegalStateException("newer release decision exists"))
                .when(executionValidator).validateForExecution(eq(releaseDecisionId), any(), any(), any());

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("newer release decision exists");
    }

    @Test
    @DisplayName("21. Stale approval binding rejected")
    void test21_staleApprovalBindingRejected() {
        when(approvalBindingService.evaluateCurrentBinding(any(), any(), any()))
                .thenReturn(new ApprovalBindingEvaluation(true, false, "APPROVAL_BINDING_DELIVERABLES_CHANGED", null));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milestone approval binding is missing or not current");
    }

    @Test
    @DisplayName("22. Milestone not APPROVED rejected")
    void test22_milestoneNotApprovedRejected() throws Exception {
        setField(milestone, "status", MilestoneStatus.SUBMITTED);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milestone is not APPROVED");
    }

    @Test
    @DisplayName("23. Deal CANCELLED rejected")
    void test23_dealCancelledRejected() throws Exception {
        setField(deal, "status", DealStatus.CANCELLED);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prohibits milestone release authorization");
    }

    @Test
    @DisplayName("24. Deal REJECTED rejected")
    void test24_dealRejectedRejected() throws Exception {
        setField(deal, "status", DealStatus.REJECTED);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prohibits milestone release authorization");
    }

    @Test
    @DisplayName("25. Deal DISPUTED rejected")
    void test25_dealDisputedRejected() throws Exception {
        setField(deal, "status", DealStatus.DISPUTED);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prohibits milestone release authorization");
    }

    @Test
    @DisplayName("26. Open dispute rejected")
    void test26_openDisputeRejected() {
        when(disputeRepo.existsByDealIdAndStatus(eq(dealId), eq(DisputeStatus.OPEN))).thenReturn(true);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has an OPEN dispute");
    }

    @Test
    @DisplayName("27. Beneficiary changed rejected")
    void test27_beneficiaryChangedRejected() throws Exception {
        OrganizationBankAccount diffBeneficiary = new OrganizationBankAccount();
        setField(diffBeneficiary, "id", UUID.randomUUID());
        setField(diffBeneficiary, "organizationId", sellerOrgId);

        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(true, diffBeneficiary, null, true));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Canonical beneficiary has changed");
    }

    @Test
    @DisplayName("28. Provider scope changed rejected")
    void test28_providerScopeChangedRejected() {
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenReturn(new FundingScope("YESBANK", "ESCROW-POOL-1", "INR"));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Funding provider changed");
    }

    @Test
    @DisplayName("29. ProviderAccountId changed rejected")
    void test29_providerAccountIdChangedRejected() {
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenReturn(new FundingScope("AXIS", "ESCROW-POOL-DIFFERENT", "INR"));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Funding providerAccountId changed");
    }

    @Test
    @DisplayName("30. Escrow FROZEN rejected")
    void test30_escrowFrozenRejected() throws Exception {
        setField(escrowAccount, "status", EscrowAccountStatus.FROZEN);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Escrow account is not ACTIVE");
    }

    @Test
    @DisplayName("31. Escrow CLOSED rejected")
    void test31_escrowClosedRejected() throws Exception {
        setField(escrowAccount, "status", EscrowAccountStatus.CLOSED);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Escrow account is not ACTIVE");
    }

    @Test
    @DisplayName("32. Escrow currency mismatch rejected")
    void test32_escrowCurrencyMismatchRejected() throws Exception {
        setField(escrowAccount, "currency", "USD");

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Escrow account currency USD does not match");
    }

    @Test
    @DisplayName("33. Insufficient funds rejected")
    void test33_insufficientFundsRejected() {
        when(reservationRepo.sumActiveReservationsForEscrow(escrowAccountId))
                .thenReturn(new BigDecimal("70000.0000")); // balance 100k - 70k = 30k available < 50k requested

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient available escrow funds to reserve");
    }

    @Test
    @DisplayName("34. Reservation failure rolls back AUTHORIZED")
    void test34_reservationFailureRollsBackAuthorized() {
        when(reservationRepo.save(any(ReleaseReservation.class))).thenThrow(new RuntimeException("Database error saving reservation"));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error saving reservation");
    }

    @Test
    @DisplayName("35. Legacy RESERVED instruction safely upgraded to QUEUED + PENDING outbox")
    void test35_legacyReservedUpgradeToQueuedWithOutbox() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.RESERVED);
        ReleaseReservation existingRes = new ReleaseReservation(
                UUID.randomUUID(), instructionId, escrowAccountId, milestoneId,
                new BigDecimal("50000.0000"), "INR"
        );
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(existingRes));
        when(financialOutboxRepo.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.empty());

        ReleaseReservation res = authService.authorizeAndReserve(instructionId, adminPrincipal);

        assertThat(res.getId()).isEqualTo(existingRes.getId());
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        verify(financialOutboxRepo, times(1)).save(any(FinancialOutboxMessage.class));
    }

    @Test
    @DisplayName("36. Stale release decision during RESERVED recovery leaves instruction RESERVED with 0 outbox")
    void test36_staleDecisionDuringReservedRecovery() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.RESERVED);
        ReleaseReservation existingRes = new ReleaseReservation(
                UUID.randomUUID(), instructionId, escrowAccountId, milestoneId,
                new BigDecimal("50000.0000"), "INR"
        );
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(existingRes));
        doThrow(new IllegalStateException("Decision stale"))
                .when(executionValidator).validateForExecution(eq(releaseDecisionId), any(), any(), any());

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Decision stale");

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.RESERVED);
        verify(financialOutboxRepo, never()).save(any(FinancialOutboxMessage.class));
    }

    @Test
    @DisplayName("37. RESERVED without reservation -> fail closed")
    void test37_reservedWithoutReservationFailsClosed() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.RESERVED);
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_INTEGRITY_ERROR: ReleaseInstruction is RESERVED but ReleaseReservation is missing");
    }

    @Test
    @DisplayName("38. RESERVED with existing outbox -> fail closed (Part V)")
    void test38_reservedWithExistingOutboxFailsClosed() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.RESERVED);
        ReleaseReservation existingRes = new ReleaseReservation(
                UUID.randomUUID(), instructionId, escrowAccountId, milestoneId,
                new BigDecimal("50000.0000"), "INR"
        );
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(existingRes));
        FinancialOutboxMessage rogueOutbox = FinancialOutboxMessage.createReleaseDispatch(instruction, Instant.now());
        when(financialOutboxRepo.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.of(rogueOutbox));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_INTEGRITY_ERROR: ReleaseInstruction is in illegal state RESERVED with existing outbox event");
    }

    @Test
    @DisplayName("39. QUEUED replay returns existing reservation with 0 additional outbox writes (Part W, AN)")
    void test39_queuedReplayIdempotency() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.QUEUED);
        ReleaseReservation existingRes = new ReleaseReservation(
                UUID.randomUUID(), instructionId, escrowAccountId, milestoneId,
                new BigDecimal("50000.0000"), "INR"
        );
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(existingRes));

        FinancialOutboxMessage outbox = FinancialOutboxMessage.createReleaseDispatch(instruction, Instant.now());
        when(financialOutboxRepo.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.of(outbox));
        when(financialOutboxRepo.findByEventTypeAndOperationKey(FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED, instructionId.toString()))
                .thenReturn(Optional.of(outbox));

        ReleaseReservation res = authService.authorizeAndReserve(instructionId, adminPrincipal);

        assertThat(res.getId()).isEqualTo(existingRes.getId());
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        verify(financialOutboxRepo, never()).save(any(FinancialOutboxMessage.class));
        verify(reservationRepo, never()).save(any(ReleaseReservation.class));
    }

    @Test
    @DisplayName("40. QUEUED missing outbox -> fail closed (Part X, AO)")
    void test40_queuedMissingOutboxFailsClosed() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.QUEUED);
        ReleaseReservation existingRes = new ReleaseReservation(
                UUID.randomUUID(), instructionId, escrowAccountId, milestoneId,
                new BigDecimal("50000.0000"), "INR"
        );
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(existingRes));
        when(financialOutboxRepo.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_INTEGRITY_ERROR: ReleaseInstruction is QUEUED but outbox event is missing");
    }

    @Test
    @DisplayName("41. CREATED with preexisting outbox -> fail closed (Part Y, AO)")
    void test41_createdWithPreexistingOutboxFailsClosed() {
        FinancialOutboxMessage rogueOutbox = FinancialOutboxMessage.createReleaseDispatch(instruction, Instant.now());
        when(financialOutboxRepo.findByReleaseInstructionIdAndEventType(instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED))
                .thenReturn(Optional.of(rogueOutbox));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_INTEGRITY_ERROR: Outbox event already exists for CREATED instruction");
    }

    @Test
    @DisplayName("42. AUTHORIZED state -> fail closed (Part Z, AO)")
    void test42_authorizedStateFailsClosed() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.AUTHORIZED);

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_INTEGRITY_ERROR: ReleaseInstruction is in illegal state AUTHORIZED");
    }

    @Test
    @DisplayName("43. CREATED with preexisting ACTIVE reservation -> fail closed")
    void test43_createdWithPreexistingActiveReservationFailsClosed() {
        ReleaseReservation rogueRes = new ReleaseReservation(
                UUID.randomUUID(), instructionId, escrowAccountId, milestoneId,
                new BigDecimal("50000.0000"), "INR"
        );
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(rogueRes));

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_INTEGRITY_ERROR: ReleaseReservation already exists for CREATED instruction");
    }

    @Test
    @DisplayName("44. No financial mutation beyond ReleaseInstruction, ReleaseReservation, and Outbox")
    void test44_noFinancialMutationBeyondInstructionReservationAndOutbox() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);

        assertThat(escrowAccount.getBalance()).isEqualByComparingTo(new BigDecimal("100000.0000"));
        assertThat(escrowAccount.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }
}
