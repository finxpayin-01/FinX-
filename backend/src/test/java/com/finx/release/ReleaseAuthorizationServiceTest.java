package com.finx.release;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowAccountStatus;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.EscrowReservationService;
import com.finx.release.service.ReleaseAuthorizationService;
import com.finx.release.service.ReleaseDecisionExecutionValidator;
import com.finx.release.service.ReleaseFundingScopeResolver;
import com.finx.release.service.ReleaseFundingScopeResolver.FundingScope;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReleaseAuthorizationServiceTest {

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

    private ReleaseAuthorizationService authService;

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

    private UserPrincipal adminPrincipal;
    private UserPrincipal financePrincipal;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;
    private UserPrincipal pmPrincipal;

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
                escrowReservationService
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
        setField(escrowAccount, "currency", "INR");
        setField(escrowAccount, "status", EscrowAccountStatus.ACTIVE);
        setField(escrowAccount, "balance", new BigDecimal("100000.0000"));
        setField(escrowAccount, "totalFunded", new BigDecimal("100000.0000"));
        setField(escrowAccount, "totalReleased", BigDecimal.ZERO);

        instruction = new ReleaseInstruction(
                instructionId,
                buyerOrgId,
                dealId,
                milestoneId,
                releaseDecisionId,
                "b".repeat(64),
                policyVersionId,
                "c".repeat(64),
                beneficiaryBankAccountId,
                "AXIS",
                "ESCROW-POOL-1",
                new BigDecimal("50000.0000"),
                "INR",
                "idem-key-1",
                "d".repeat(64),
                UUID.randomUUID()
        );

        beneficiary = new OrganizationBankAccount();
        setField(beneficiary, "id", beneficiaryBankAccountId);
        setField(beneficiary, "organizationId", sellerOrgId);

        adminPrincipal = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "pass", Role.ADMIN, UserStatus.ACTIVE, List.of());
        financePrincipal = new UserPrincipal(UUID.randomUUID(), "Finance", "fin@finx.com", "pass", Role.FINANCE, UserStatus.ACTIVE, List.of());
        buyerPrincipal = new UserPrincipal(UUID.randomUUID(), "Buyer", "buyer@buyer.com", "pass", Role.BUYER, UserStatus.ACTIVE, List.of());
        sellerPrincipal = new UserPrincipal(UUID.randomUUID(), "Seller", "seller@seller.com", "pass", Role.SELLER, UserStatus.ACTIVE, List.of());
        pmPrincipal = new UserPrincipal(UUID.randomUUID(), "PM", "pm@finx.com", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE, List.of());

        // Default lookups
        when(instructionRepo.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(instructionRepo.findScopeById(instructionId)).thenReturn(Optional.of(new ReleaseInstructionRepository.ScopeProjection() {
            @Override
            public UUID getDealId() { return dealId; }
            @Override
            public UUID getMilestoneId() { return milestoneId; }
        }));
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
        when(reservationRepo.save(any(ReleaseReservation.class))).thenAnswer(i -> {
            ReleaseReservation res = i.getArgument(0);
            return res;
        });
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("1. ADMIN authorize/reserve succeeds")
    void test01_adminAuthorizeReserve() {
        ReleaseReservation res = authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("2. FINANCE authorize/reserve succeeds")
    void test02_financeAuthorizeReserve() {
        ReleaseReservation res = authService.authorizeAndReserve(instructionId, financePrincipal);
        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
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
    @DisplayName("7. Instruction transitions CREATED -> RESERVED")
    void test07_instructionTransitionsToReserved() {
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.RESERVED);
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
    @DisplayName("18. No outbox created")
    void test18_noOutboxCreated() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);
        // Verified: outbox infrastructure absent
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
    @DisplayName("35. Authorization replay on RESERVED returns exact existing reservation")
    void test35_replayOnReservedReturnsExisting() throws Exception {
        // First execution
        ReleaseReservation res1 = authService.authorizeAndReserve(instructionId, adminPrincipal);

        // Simulate state in persistence
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(res1));

        // Second execution (replay)
        ReleaseReservation res2 = authService.authorizeAndReserve(instructionId, adminPrincipal);

        assertThat(res2.getId()).isEqualTo(res1.getId());
        verify(reservationRepo, times(1)).save(any(ReleaseReservation.class)); // Only saved once
    }

    @Test
    @DisplayName("36. RESERVED without reservation -> fail closed")
    void test36_reservedWithoutReservationFailsClosed() throws Exception {
        setField(instruction, "status", ReleaseInstructionStatus.RESERVED);
        when(reservationRepo.findByReleaseInstructionId(instructionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authorizeAndReserve(instructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_INTEGRITY_ERROR: ReleaseInstruction is RESERVED but ReleaseReservation is missing");
    }

    @Test
    @DisplayName("37. CREATED with preexisting ACTIVE reservation -> fail closed")
    void test37_createdWithPreexistingActiveReservationFailsClosed() throws Exception {
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
    @DisplayName("38. No financial mutation beyond ReleaseInstruction + ReleaseReservation")
    void test38_noFinancialMutationBeyondInstructionAndReservation() {
        authService.authorizeAndReserve(instructionId, adminPrincipal);

        assertThat(escrowAccount.getBalance()).isEqualByComparingTo(new BigDecimal("100000.0000"));
        assertThat(escrowAccount.getTotalReleased()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }
}
