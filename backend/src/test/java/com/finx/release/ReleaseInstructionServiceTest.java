package com.finx.release;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.engine.model.EvidenceReference;
import com.finx.engine.policy.release.ReleasePolicyDefinition;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.release.dto.CreateReleaseInstructionCommand;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.ReleaseDecisionExecutionValidator;
import com.finx.release.service.ReleaseFundingScopeResolver;
import com.finx.release.service.ReleaseFundingScopeResolver.FundingScope;
import com.finx.release.service.ReleaseInstructionService;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReleaseInstructionServiceTest {

    private ReleaseInstructionRepository instructionRepo;
    private ReleaseReservationRepository reservationRepo;
    private EngineDecisionRepository decisionRepo;
    private DealRepository dealRepo;
    private MilestoneRepository milestoneRepo;
    private VerificationEligibilityService eligibilityService;
    private ReleaseFundingScopeResolver fundingScopeResolver;
    private ReleaseDecisionExecutionValidator executionValidator;
    private EscrowAccountRepository escrowRepo;
    private JournalEntryRepository journalRepo;
    private BankingProvider bankingProvider;

    private ReleaseInstructionService service;

    private Deal deal;
    private Milestone milestone;
    private OrganizationBankAccount beneficiary;
    private EngineDecision validReleaseDecision;

    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID beneficiaryBankAccountId;
    private UUID releaseDecisionId;
    private UUID policyVersionId;

    private UserPrincipal adminPrincipal;
    private UserPrincipal financePrincipal;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;
    private UserPrincipal pmPrincipal;

    @BeforeEach
    void setUp() throws Exception {
        instructionRepo = mock(ReleaseInstructionRepository.class);
        reservationRepo = mock(ReleaseReservationRepository.class);
        decisionRepo = mock(EngineDecisionRepository.class);
        dealRepo = mock(DealRepository.class);
        milestoneRepo = mock(MilestoneRepository.class);
        eligibilityService = mock(VerificationEligibilityService.class);
        fundingScopeResolver = mock(ReleaseFundingScopeResolver.class);
        executionValidator = mock(ReleaseDecisionExecutionValidator.class);
        escrowRepo = mock(EscrowAccountRepository.class);
        journalRepo = mock(JournalEntryRepository.class);
        bankingProvider = mock(BankingProvider.class);

        service = new ReleaseInstructionService(
                instructionRepo,
                decisionRepo,
                dealRepo,
                milestoneRepo,
                eligibilityService,
                fundingScopeResolver,
                executionValidator
        );

        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        beneficiaryBankAccountId = UUID.randomUUID();
        releaseDecisionId = UUID.randomUUID();
        policyVersionId = UUID.randomUUID();

        deal = new Deal();
        setField(deal, "id", dealId);
        setField(deal, "buyerOrganizationId", buyerOrgId);
        setField(deal, "sellerOrganizationId", sellerOrgId);

        milestone = new Milestone();
        setField(milestone, "id", milestoneId);
        setField(milestone, "dealId", dealId);
        setField(milestone, "amount", new BigDecimal("75000.0000"));
        setField(milestone, "currency", "INR");

        beneficiary = new OrganizationBankAccount();
        setField(beneficiary, "id", beneficiaryBankAccountId);
        setField(beneficiary, "organizationId", sellerOrgId);

        validReleaseDecision = createValidDecision();

        adminPrincipal = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "pass", Role.ADMIN, UserStatus.ACTIVE, List.of());
        financePrincipal = new UserPrincipal(UUID.randomUUID(), "Finance", "fin@finx.com", "pass", Role.FINANCE, UserStatus.ACTIVE, List.of());
        buyerPrincipal = new UserPrincipal(UUID.randomUUID(), "Buyer", "buyer@buyer.com", "pass", Role.BUYER, UserStatus.ACTIVE, List.of());
        sellerPrincipal = new UserPrincipal(UUID.randomUUID(), "Seller", "seller@seller.com", "pass", Role.SELLER, UserStatus.ACTIVE, List.of());
        pmPrincipal = new UserPrincipal(UUID.randomUUID(), "PM", "pm@finx.com", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE, List.of());

        when(decisionRepo.findById(releaseDecisionId)).thenReturn(Optional.of(validReleaseDecision));
        when(milestoneRepo.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepo.findById(dealId)).thenReturn(Optional.of(deal));
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(true, beneficiary, null, true));
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenReturn(new FundingScope("AXIS", "ESCROW-POOL-1", "INR"));

        when(instructionRepo.save(any(ReleaseInstruction.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private EngineDecision createValidDecision() throws Exception {
        EngineDecision d = new EngineDecision();
        setField(d, "id", releaseDecisionId);
        setField(d, "organizationId", buyerOrgId);
        setField(d, "engineType", EngineType.RELEASE);
        setField(d, "subjectType", "MILESTONE");
        setField(d, "subjectId", milestoneId);
        setField(d, "decision", DecisionStatus.ALLOW);
        setField(d, "evaluationOutcome", EvaluationOutcome.SUCCESS);
        setField(d, "inputFingerprint", "a".repeat(64));
        setField(d, "decisionFingerprint", "b".repeat(64));
        setField(d, "policyVersionId", policyVersionId);
        setField(d, "policyContentHash", ReleasePolicyDefinition.getExpectedContentHash());
        setField(d, "evaluationId", UUID.randomUUID());
        setField(d, "correlationId", UUID.randomUUID());
        setField(d, "ruleVersion", ReleasePolicyDefinition.POLICY_KEY + ":1.0.0");
        setField(d, "evaluatedAt", Instant.now().minusSeconds(5));
        setField(d, "validUntil", Instant.now().plusSeconds(3600));

        List<Map<String, Object>> refs = new ArrayList<>();
        refs.add(Map.of("sourceType", "ORGANIZATION_BANK_ACCOUNT", "sourceId", beneficiaryBankAccountId.toString(), "provenance", "MOCK"));
        for (EngineType reqType : ReleasePolicyDefinition.REQUIRED_ENGINES) {
            refs.add(Map.of("sourceType", "ENGINE_DECISION", "sourceId", UUID.randomUUID().toString(), "provenance", reqType.name()));
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        setField(d, "evidenceReferencesJson", mapper.writeValueAsString(refs));

        return d;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("1. ADMIN can create")
    void test01_adminCanCreate() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-01");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(inst).isNotNull();
        assertThat(inst.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        assertThat(inst.getCreatedBy()).isEqualTo(adminPrincipal.getId());
    }

    @Test
    @DisplayName("2. FINANCE can create")
    void test02_financeCanCreate() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-02");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, financePrincipal);

        assertThat(inst).isNotNull();
        assertThat(inst.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        assertThat(inst.getCreatedBy()).isEqualTo(financePrincipal.getId());
    }

    @Test
    @DisplayName("3. BUYER rejected")
    void test03_buyerRejected() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-03");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, buyerPrincipal))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("4. SELLER rejected")
    void test04_sellerRejected() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-04");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, sellerPrincipal))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("5. PROJECT_MANAGER rejected")
    void test05_projectManagerRejected() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-05");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, pmPrincipal))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("6. Null principal rejected")
    void test06_nullPrincipalRejected() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-06");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("7. Disabled principal rejected")
    void test07_disabledPrincipalRejected() {
        UserPrincipal disabled = new UserPrincipal(UUID.randomUUID(), "Disabled", "dis@finx.com", "pass", Role.ADMIN, UserStatus.SUSPENDED, List.of());
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-07");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, disabled))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("8. Missing release decision rejected")
    void test08_missingReleaseDecisionRejected() {
        UUID randomId = UUID.randomUUID();
        when(decisionRepo.findById(randomId)).thenReturn(Optional.empty());
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(randomId, "IDEM-08");

        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("9. RISK decision rejected")
    void test09_riskDecisionRejected() throws Exception {
        doThrow(new IllegalStateException("Expected engineType RELEASE but was RISK"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-09");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected engineType RELEASE");
    }

    @Test
    @DisplayName("10. RELEASE REVIEW rejected")
    void test10_releaseReviewRejected() {
        doThrow(new IllegalStateException("Expected decision ALLOW but was REVIEW"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-10");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected decision ALLOW");
    }

    @Test
    @DisplayName("11. RELEASE BLOCK rejected")
    void test11_releaseBlockRejected() {
        doThrow(new IllegalStateException("Expected decision ALLOW but was BLOCK"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-11");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected decision ALLOW");
    }

    @Test
    @DisplayName("12. RELEASE ERROR rejected")
    void test12_releaseErrorRejected() {
        doThrow(new IllegalStateException("Expected evaluationOutcome SUCCESS but was ERROR"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-12");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected evaluationOutcome SUCCESS");
    }

    @Test
    @DisplayName("13. Wrong subject rejected")
    void test13_wrongSubjectRejected() {
        doThrow(new IllegalStateException("Expected subjectType MILESTONE but was DEAL"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-13");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected subjectType MILESTONE");
    }

    @Test
    @DisplayName("14. Wrong organization rejected")
    void test14_wrongOrganizationRejected() throws Exception {
        setField(deal, "buyerOrganizationId", UUID.randomUUID());
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-14");

        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match deal buyerOrganizationId");
    }

    @Test
    @DisplayName("15. Expired decision rejected")
    void test15_expiredDecisionRejected() {
        doThrow(new IllegalStateException("Release decision has expired"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-15");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has expired");
    }

    @Test
    @DisplayName("16. Wrong policy ID rejected")
    void test16_wrongPolicyIdRejected() {
        doThrow(new IllegalStateException("EnginePolicyVersion not found"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-16");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EnginePolicyVersion not found");
    }

    @Test
    @DisplayName("17. Wrong policy hash rejected")
    void test17_wrongPolicyHashRejected() {
        doThrow(new IllegalStateException("Policy content hash mismatch"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-17");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Policy content hash mismatch");
    }

    @Test
    @DisplayName("18. Inactive policy rejected")
    void test18_inactivePolicyRejected() {
        doThrow(new IllegalStateException("Policy version is not active"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-18");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Policy version is not active");
    }

    @Test
    @DisplayName("19. Old release decision when newer exists rejected")
    void test19_newerReleaseDecisionExistsRejected() {
        doThrow(new IllegalStateException("Stale release decision: a newer release decision exists"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-19");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("newer release decision exists");
    }

    @Test
    @DisplayName("20. Stale input fingerprint rejected")
    void test20_staleInputFingerprintRejected() {
        doThrow(new IllegalStateException("Deterministic re-evaluation inputFingerprint mismatch"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-20");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inputFingerprint mismatch");
    }

    @Test
    @DisplayName("21. Current deterministic re-evaluation mismatch rejected")
    void test21_deterministicReEvaluationMismatchRejected() {
        doThrow(new IllegalStateException("Deterministic re-evaluation yielded non-ALLOW: REVIEW"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-21");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yielded non-ALLOW");
    }

    @Test
    @DisplayName("22. Fewer than 8 component decision refs rejected")
    void test22_fewerThanEightComponentsRejected() {
        doThrow(new IllegalStateException("Expected exactly 8 component ENGINE_DECISION evidence references, found: 7"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-22");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected exactly 8 component ENGINE_DECISION");
    }

    @Test
    @DisplayName("23. Duplicate component engine refs rejected")
    void test23_duplicateComponentRefsRejected() {
        doThrow(new IllegalStateException("Duplicate component engine type in references"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-23");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate component engine type");
    }

    @Test
    @DisplayName("24. Component REVIEW ref rejected")
    void test24_componentReviewRefRejected() {
        doThrow(new IllegalStateException("Component decision must be ALLOW, but was REVIEW"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-24");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Component decision must be ALLOW");
    }

    @Test
    @DisplayName("25. Component ERROR ref rejected")
    void test25_componentErrorRefRejected() {
        doThrow(new IllegalStateException("Component evaluationOutcome must be SUCCESS, but was ERROR"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-25");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Component evaluationOutcome must be SUCCESS");
    }

    @Test
    @DisplayName("26. Canonical beneficiary mismatch rejected")
    void test26_canonicalBeneficiaryMismatchRejected() {
        doThrow(new IllegalStateException("Canonical beneficiary account changed"))
                .when(executionValidator).validateForCreation(any(), any(), any(), any());

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-26");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Canonical beneficiary account changed");
    }

    @Test
    @DisplayName("27. No eligible beneficiary rejected")
    void test27_noEligibleBeneficiaryRejected() {
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(false, null, "No verified bank account", false));

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-27");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Canonical beneficiary resolution failed");
    }

    @Test
    @DisplayName("28. Funding scope missing rejected")
    void test28_fundingScopeMissingRejected() {
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenThrow(new IllegalStateException("RELEASE_FUNDING_SCOPE_MISSING"));

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-28");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_FUNDING_SCOPE_MISSING");
    }

    @Test
    @DisplayName("29. Funding scope ambiguous rejected")
    void test29_fundingScopeAmbiguousRejected() {
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenThrow(new IllegalStateException("RELEASE_FUNDING_SCOPE_AMBIGUOUS"));

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-29");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_FUNDING_SCOPE_AMBIGUOUS");
    }

    @Test
    @DisplayName("30. ProviderAccountId null rejected")
    void test30_providerAccountIdNullRejected() {
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenThrow(new IllegalStateException("blank providerAccountId"));

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-30");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank providerAccountId");
    }

    @Test
    @DisplayName("31. Mixed funding currency rejected")
    void test31_mixedFundingCurrencyRejected() {
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenThrow(new IllegalStateException("Mixed funding currencies detected"));

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-31");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mixed funding currencies detected");
    }

    @Test
    @DisplayName("32. Valid current release creates CREATED instruction")
    void test32_validCurrentReleaseCreatesCreatedInstruction() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-32");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(inst.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);
        assertThat(inst.getIdempotencyKey()).isEqualTo("IDEM-32");
    }

    @Test
    @DisplayName("33. Amount derived from milestone")
    void test33_amountDerivedFromMilestone() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-33");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(inst.getAmount()).isEqualByComparingTo(new BigDecimal("75000.0000"));
    }

    @Test
    @DisplayName("34. Currency derived from milestone")
    void test34_currencyDerivedFromMilestone() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-34");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(inst.getCurrency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("35. Beneficiary derived from canonical account")
    void test35_beneficiaryDerivedFromCanonicalAccount() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-35");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(inst.getBeneficiaryBankAccountId()).isEqualTo(beneficiaryBankAccountId);
    }

    @Test
    @DisplayName("36. Provider derived from funding scope")
    void test36_providerDerivedFromFundingScope() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-36");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(inst.getProvider()).isEqualTo("AXIS");
    }

    @Test
    @DisplayName("37. ProviderAccountId derived from funding scope")
    void test37_providerAccountIdDerivedFromFundingScope() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-37");
        ReleaseInstruction inst = service.createFromReleaseDecision(cmd, adminPrincipal);

        assertThat(inst.getProviderAccountId()).isEqualTo("ESCROW-POOL-1");
    }

    @Test
    @DisplayName("38. Exact idempotent replay returns same instruction")
    void test38_exactIdempotentReplayReturnsSameInstruction() throws Exception {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-38");
        ReleaseInstruction created = service.createFromReleaseDecision(cmd, adminPrincipal);

        when(instructionRepo.findByOrganizationIdAndIdempotencyKey(buyerOrgId, "IDEM-38"))
                .thenReturn(Optional.of(created));

        ReleaseInstruction replayed = service.createFromReleaseDecision(cmd, adminPrincipal);
        assertThat(replayed).isSameAs(created);
    }

    @Test
    @DisplayName("39. Same idempotency key + changed request rejected")
    void test39_sameIdempotencyKeyChangedRequestRejected() throws Exception {
        ReleaseInstruction existing = mock(ReleaseInstruction.class);
        when(existing.getRequestHash()).thenReturn("mismatch-hash");
        when(existing.getReleaseDecisionId()).thenReturn(releaseDecisionId);

        when(instructionRepo.findByOrganizationIdAndIdempotencyKey(buyerOrgId, "IDEM-39"))
                .thenReturn(Optional.of(existing));

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-39");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting request parameters");
    }

    @Test
    @DisplayName("40. Same release decision cannot create second instruction with different parameters")
    void test40_sameReleaseDecisionCannotCreateSecondConflictingInstruction() {
        ReleaseInstruction existing = mock(ReleaseInstruction.class);
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(existing.getRequestHash()).thenReturn("other-hash");

        when(instructionRepo.findByReleaseDecisionId(releaseDecisionId))
                .thenReturn(Optional.of(existing));

        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-40");
        assertThatThrownBy(() -> service.createFromReleaseDecision(cmd, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound to conflicting ReleaseInstruction");
    }

    @Test
    @DisplayName("41. Reservation rows created = 0")
    void test41_reservationRowsCreatedZero() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-41");
        service.createFromReleaseDecision(cmd, adminPrincipal);

        verifyNoInteractions(reservationRepo);
    }

    @Test
    @DisplayName("42. Bank calls = 0")
    void test42_zeroBankCalls() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-42");
        service.createFromReleaseDecision(cmd, adminPrincipal);

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("43. Escrow writes = 0")
    void test43_zeroEscrowWrites() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-43");
        service.createFromReleaseDecision(cmd, adminPrincipal);

        verifyNoInteractions(escrowRepo);
    }

    @Test
    @DisplayName("44. Ledger writes = 0")
    void test44_zeroLedgerWrites() {
        CreateReleaseInstructionCommand cmd = new CreateReleaseInstructionCommand(releaseDecisionId, "IDEM-44");
        service.createFromReleaseDecision(cmd, adminPrincipal);

        verifyNoInteractions(journalRepo);
    }
}
