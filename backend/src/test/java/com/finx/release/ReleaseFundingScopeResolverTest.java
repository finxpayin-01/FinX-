package com.finx.release;

import com.finx.deal.entity.Deal;
import com.finx.milestone.entity.Milestone;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.release.service.ReleaseFundingScopeResolver;
import com.finx.release.service.ReleaseFundingScopeResolver.FundingScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReleaseFundingScopeResolverTest {

    private PaymentInstructionRepository instructionRepo;
    private PaymentTransactionRepository transactionRepo;
    private ReleaseFundingScopeResolver resolver;

    private Deal deal;
    private Milestone milestone;
    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerOrgId;

    @BeforeEach
    void setUp() throws Exception {
        instructionRepo = mock(PaymentInstructionRepository.class);
        transactionRepo = mock(PaymentTransactionRepository.class);
        resolver = new ReleaseFundingScopeResolver(instructionRepo, transactionRepo);

        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();

        deal = new Deal();
        setField(deal, "id", dealId);
        setField(deal, "buyerOrganizationId", buyerOrgId);

        milestone = new Milestone();
        setField(milestone, "id", milestoneId);
        setField(milestone, "dealId", dealId);
        setField(milestone, "currency", "INR");
        setField(milestone, "amount", new BigDecimal("10000.0000"));
    }

    private PaymentInstruction createInstruction(UUID msId, UUID bOrgId, String provider, String providerAccountId, String currency) throws Exception {
        PaymentInstruction pi = new PaymentInstruction();
        setField(pi, "id", UUID.randomUUID());
        setField(pi, "dealId", dealId);
        setField(pi, "milestoneId", msId);
        setField(pi, "buyerOrganizationId", bOrgId);
        setField(pi, "provider", provider);
        setField(pi, "providerAccountId", providerAccountId);
        setField(pi, "currency", currency);
        setField(pi, "virtualAccountNumber", "VA-99999");
        return pi;
    }

    private PaymentTransaction createTransaction(UUID piId, String status, String provider) throws Exception {
        PaymentTransaction pt = new PaymentTransaction();
        setField(pt, "id", UUID.randomUUID());
        setField(pt, "paymentInstructionId", piId);
        setField(pt, "status", status);
        setField(pt, "provider", provider);
        return pt;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("1. One successful milestone instruction -> scope resolved")
    void test01_oneSuccessfulMilestoneInstruction() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-12345", "INR");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        FundingScope scope = resolver.resolveFundingScope(deal, milestone);

        assertThat(scope.provider()).isEqualTo("AXIS");
        assertThat(scope.providerAccountId()).isEqualTo("ACC-12345");
        assertThat(scope.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("2. Whole-deal successful funding (milestoneId null) -> scope resolved")
    void test02_wholeDealSuccessfulFunding() throws Exception {
        PaymentInstruction pi = createInstruction(null, buyerOrgId, "ICICI", "POOL-999", "INR");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", "ICICI");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        FundingScope scope = resolver.resolveFundingScope(deal, milestone);

        assertThat(scope.provider()).isEqualTo("ICICI");
        assertThat(scope.providerAccountId()).isEqualTo("POOL-999");
        assertThat(scope.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("3. Unrelated milestone instruction ignored")
    void test03_unrelatedMilestoneInstructionIgnored() throws Exception {
        UUID otherMsId = UUID.randomUUID();
        PaymentInstruction piUnrelated = createInstruction(otherMsId, buyerOrgId, "HDFC", "ACC-OTHER", "INR");
        PaymentTransaction ptUnrelated = createTransaction(piUnrelated.getId(), "SUCCESS", "HDFC");

        PaymentInstruction piTarget = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-TARGET", "INR");
        PaymentTransaction ptTarget = createTransaction(piTarget.getId(), "SUCCESS", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(piUnrelated, piTarget));
        when(transactionRepo.findByPaymentInstructionId(piUnrelated.getId())).thenReturn(List.of(ptUnrelated));
        when(transactionRepo.findByPaymentInstructionId(piTarget.getId())).thenReturn(List.of(ptTarget));

        FundingScope scope = resolver.resolveFundingScope(deal, milestone);

        assertThat(scope.provider()).isEqualTo("AXIS");
        assertThat(scope.providerAccountId()).isEqualTo("ACC-TARGET");
    }

    @Test
    @DisplayName("4. Foreign buyer organization ignored")
    void test04_foreignBuyerOrganizationIgnored() throws Exception {
        UUID foreignOrg = UUID.randomUUID();
        PaymentInstruction piForeign = createInstruction(milestoneId, foreignOrg, "AXIS", "ACC-FOREIGN", "INR");
        PaymentTransaction ptForeign = createTransaction(piForeign.getId(), "SUCCESS", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(piForeign));
        when(transactionRepo.findByPaymentInstructionId(piForeign.getId())).thenReturn(List.of(ptForeign));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_FUNDING_SCOPE_MISSING");
    }

    @Test
    @DisplayName("5. Failed payment transaction does not establish scope")
    void test05_failedPaymentTransactionDoesNotEstablishScope() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-1", "INR");
        PaymentTransaction ptFailed = createTransaction(pi.getId(), "FAILED", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(ptFailed));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_FUNDING_SCOPE_MISSING");
    }

    @Test
    @DisplayName("6. No SUCCESS transaction -> missing")
    void test06_noSuccessTransactionMissing() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-1", "INR");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_FUNDING_SCOPE_MISSING");
    }

    @Test
    @DisplayName("7. Null provider -> fail")
    void test07_nullProviderFail() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, null, "ACC-1", "INR");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", null);

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank provider");
    }

    @Test
    @DisplayName("8. Null providerAccountId -> fail")
    void test08_nullProviderAccountIdFail() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "AXIS", null, "INR");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank providerAccountId");
    }

    @Test
    @DisplayName("9. Two instructions same provider/account -> one logical scope")
    void test09_twoInstructionsSameProviderAccount() throws Exception {
        PaymentInstruction pi1 = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-SAME", "INR");
        PaymentTransaction pt1 = createTransaction(pi1.getId(), "SUCCESS", "AXIS");

        PaymentInstruction pi2 = createInstruction(null, buyerOrgId, "AXIS", "ACC-SAME", "INR");
        PaymentTransaction pt2 = createTransaction(pi2.getId(), "SUCCESS", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi1, pi2));
        when(transactionRepo.findByPaymentInstructionId(pi1.getId())).thenReturn(List.of(pt1));
        when(transactionRepo.findByPaymentInstructionId(pi2.getId())).thenReturn(List.of(pt2));

        FundingScope scope = resolver.resolveFundingScope(deal, milestone);

        assertThat(scope.provider()).isEqualTo("AXIS");
        assertThat(scope.providerAccountId()).isEqualTo("ACC-SAME");
    }

    @Test
    @DisplayName("10. Two provider accounts -> ambiguous")
    void test10_twoProviderAccountsAmbiguous() throws Exception {
        PaymentInstruction pi1 = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-1", "INR");
        PaymentTransaction pt1 = createTransaction(pi1.getId(), "SUCCESS", "AXIS");

        PaymentInstruction pi2 = createInstruction(null, buyerOrgId, "AXIS", "ACC-2", "INR");
        PaymentTransaction pt2 = createTransaction(pi2.getId(), "SUCCESS", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi1, pi2));
        when(transactionRepo.findByPaymentInstructionId(pi1.getId())).thenReturn(List.of(pt1));
        when(transactionRepo.findByPaymentInstructionId(pi2.getId())).thenReturn(List.of(pt2));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_FUNDING_SCOPE_AMBIGUOUS");
    }

    @Test
    @DisplayName("11. Two providers -> ambiguous")
    void test11_twoProvidersAmbiguous() throws Exception {
        PaymentInstruction pi1 = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-1", "INR");
        PaymentTransaction pt1 = createTransaction(pi1.getId(), "SUCCESS", "AXIS");

        PaymentInstruction pi2 = createInstruction(null, buyerOrgId, "ICICI", "ACC-1", "INR");
        PaymentTransaction pt2 = createTransaction(pi2.getId(), "SUCCESS", "ICICI");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi1, pi2));
        when(transactionRepo.findByPaymentInstructionId(pi1.getId())).thenReturn(List.of(pt1));
        when(transactionRepo.findByPaymentInstructionId(pi2.getId())).thenReturn(List.of(pt2));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE_FUNDING_SCOPE_AMBIGUOUS");
    }

    @Test
    @DisplayName("12. Transaction provider mismatch -> fail")
    void test12_transactionProviderMismatchFail() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-1", "INR");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", "KOTAK");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match PaymentInstruction provider");
    }

    @Test
    @DisplayName("13. Currency mismatch -> fail")
    void test13_currencyMismatchFail() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "AXIS", "ACC-1", "USD");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", "AXIS");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        assertThatThrownBy(() -> resolver.resolveFundingScope(deal, milestone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible with milestone currency");
    }

    @Test
    @DisplayName("14. Beneficiary account is never used as providerAccountId")
    void test14_beneficiaryAccountNeverUsedAsProviderAccount() throws Exception {
        // Source providerAccountId resolved from PaymentInstruction is strictly FinX nodal/escrow scope
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "MOCK", "FINX-ESCROW-POOL-01", "INR");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", "MOCK");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        FundingScope scope = resolver.resolveFundingScope(deal, milestone);

        assertThat(scope.providerAccountId()).isEqualTo("FINX-ESCROW-POOL-01");
    }

    @Test
    @DisplayName("15. virtualAccountNumber is never used as providerAccountId")
    void test15_virtualAccountNumberNeverUsedAsProviderAccountId() throws Exception {
        PaymentInstruction pi = createInstruction(milestoneId, buyerOrgId, "MOCK", "FINX-NODAL-1020", "INR");
        PaymentTransaction pt = createTransaction(pi.getId(), "SUCCESS", "MOCK");

        when(instructionRepo.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(transactionRepo.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));

        FundingScope scope = resolver.resolveFundingScope(deal, milestone);

        assertThat(scope.providerAccountId()).isNotEqualTo("VA-99999");
        assertThat(scope.providerAccountId()).isEqualTo("FINX-NODAL-1020");
    }

    @Test
    @DisplayName("16. No BankingProvider dependency in ReleaseFundingScopeResolver")
    void test16_noBankingProviderDependency() {
        for (Field f : ReleaseFundingScopeResolver.class.getDeclaredFields()) {
            assertThat(f.getType().getName()).doesNotContain("BankingProvider");
        }
    }
}
