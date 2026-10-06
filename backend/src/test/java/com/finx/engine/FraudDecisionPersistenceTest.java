package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.*;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.engine.adapter.FraudDecisionAdapter;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import com.finx.engine.policy.fraud.FraudPolicyDefinition;
import com.finx.engine.service.EngineDecisionService;
import com.finx.engine.service.EnginePolicyVersionService;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceCheck;
import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceCheckType;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.*;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(isolation = Isolation.REPEATABLE_READ)
class FraudDecisionPersistenceTest {

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FraudDecisionAdapter fraudAdapter;
    @Autowired private EngineDecisionService decisionService;
    @Autowired private EnginePolicyVersionService policyVersionService;
    @MockBean private BankingProvider banking;

    private User buyer;
    private User seller;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private Deal deal;
    private Milestone milestone;
    private Invoice invoice;
    private OrganizationBankAccount sellerBank;
    private EnginePolicyVersion policyVersion;
    private EvaluationContext context;

    private static final List<String> UNCHANGED_TABLES = List.of(
            "settlement_instructions",
            "payment_instructions",
            "payment_transactions",
            "journal_entries",
            "journal_lines",
            "escrow_accounts",
            "escrow_ledger",
            "milestones",
            "deliverables",
            "invoices",
            "invoice_checks",
            "invoice_reviews",
            "invoice_assessments",
            "deal_agreements",
            "risk_reviews",
            "risk_scores",
            "organizations",
            "organization_bank_accounts"
    );

    @BeforeEach
    void setUp() {
        buyer = new User("Buyer User", UUID.randomUUID() + "@test.invalid", "pass", Role.BUYER, UserStatus.ACTIVE);
        em.persist(buyer);

        seller = new User("Seller User", UUID.randomUUID() + "@test.invalid", "pass", Role.SELLER, UserStatus.ACTIVE);
        em.persist(seller);

        buyerOrg = new Organization("Buyer Org", "Buyer Legal", EntityType.PRIVATE_LIMITED, null, null, null, null, buyer.getId());
        em.persist(buyerOrg);

        sellerOrg = new Organization("Seller Org", "Seller Legal", EntityType.PRIVATE_LIMITED, null, null, null, null, seller.getId());
        em.persist(sellerOrg);

        OrganizationMember buyerMember = new OrganizationMember(buyerOrg.getId(), buyer.getId(), OrgRole.OWNER);
        buyerMember.setStatus(MemberStatus.ACTIVE);
        em.persist(buyerMember);

        deal = new Deal("Fraud Persistence Deal", "", buyer.getId(), seller.getId(), new BigDecimal("10000.00"), "INR", DealStatus.IN_PROGRESS);
        deal.setBuyerOrganizationId(buyerOrg.getId());
        deal.setSellerOrganizationId(sellerOrg.getId());
        em.persist(deal);

        milestone = new Milestone(deal.getId(), "Milestone 1", "Delivery of modules", 1, new BigDecimal("10000.00"), "INR", Instant.now().plusSeconds(86400));
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setInvoiceRequired(true);
        em.persist(milestone);

        sellerBank = new OrganizationBankAccount(sellerOrg.getId(), "Seller Legal", "****1234", "test-hash", "HDFC0001234", "Test", "CURRENT", true);
        sellerBank.setEncryptedAccountNumber("test-cipher");
        sellerBank.setVerificationStatus(BankVerificationStatus.VERIFIED);
        sellerBank.setNameMatchStatus("EXACT_MATCH");
        sellerBank.setVerifiedAt(Instant.now());
        em.persist(sellerBank);

        invoice = new Invoice();
        invoice.setDealId(deal.getId());
        invoice.setMilestoneId(milestone.getId());
        invoice.setBuyerOrganizationId(buyerOrg.getId());
        invoice.setVendorOrganizationId(sellerOrg.getId());
        invoice.setInvoiceNumber("INV-FP-001");
        invoice.setNormalizedInvoiceNumber("INVFP001");
        invoice.setTotalAmount(new BigDecimal("10000.00"));
        invoice.setFileName("invoice.pdf");
        invoice.setFileUrl("private/invoices/inv.pdf");
        invoice.setDocumentHash("f".repeat(64));
        invoice.setCreatedBy(seller.getId());
        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY);
        invoice.setInvoiceDate(LocalDate.now());
        em.persist(invoice);

        InvoiceCheck checkHash = new InvoiceCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED", "Doc ok", 10, 10, null);
        em.persist(checkHash);

        InvoiceCheck checkGst = new InvoiceCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED", "GST ok", 10, 10, null);
        em.persist(checkGst);

        InvoiceCheck checkPan = new InvoiceCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED", "PAN ok", 10, 10, null);
        em.persist(checkPan);

        InvoiceCheck checkDate = new InvoiceCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_WITHIN_BOUNDS", "Date ok", 10, 10, null);
        em.persist(checkDate);

        // Ensure Fraud Policy v1.0.1 is registered and activated
        var existingPolicy = policyVersionService.getPlatformPolicy(EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION);
        if (existingPolicy.isPresent()) {
            policyVersion = existingPolicy.get();
        } else {
            policyVersion = policyVersionService.registerPolicyVersion(
                    null,
                    PolicyScope.PLATFORM,
                    EngineType.FRAUD,
                    FraudPolicyDefinition.POLICY_KEY,
                    FraudPolicyDefinition.POLICY_VERSION,
                    FraudPolicyDefinition.getExpectedContentHash(),
                    "1.0",
                    FraudPolicyDefinition.DESCRIPTION,
                    FraudPolicyDefinition.getCanonicalPolicyContent(),
                    null
            );
        }

        em.flush();

        context = new EvaluationContext(
                UUID.randomUUID(),
                buyerOrg.getId(),
                UUID.randomUUID(),
                "MILESTONE",
                milestone.getId(),
                Instant.now()
        );

        clearInvocations(banking);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : UNCHANGED_TABLES) {
            result.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        }
        return result;
    }

    @Test
    @DisplayName("1. Legitimate milestone FRAUD decision persists with exact policy binding and zero side effects")
    void testFraudDecisionPersistsWithExactPolicyBindingAndZeroSideEffects() {
        var before = snapshot();
        long releaseBefore = jdbc.queryForObject("select count(*) from engine_decisions where engine_type = 'RELEASE'", Long.class);

        RecordEngineDecisionCommand cmd = fraudAdapter.evaluateForCaller(deal.getId(), context, UserPrincipal.create(buyer));

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.policyVersionId()).isEqualTo(policyVersion.getId());
        assertThat(cmd.policyContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());
        assertThat(cmd.ruleVersion()).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE:1.0.1");
        assertThat(cmd.score()).isNull();

        EngineDecision saved = decisionService.recordDecision(cmd);
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEngineType()).isEqualTo(EngineType.FRAUD);
        assertThat(saved.getSubjectType()).isEqualTo("MILESTONE");
        assertThat(saved.getSubjectId()).isEqualTo(milestone.getId());
        assertThat(saved.getOrganizationId()).isEqualTo(buyerOrg.getId());
        assertThat(saved.getPolicyVersionId()).isEqualTo(policyVersion.getId());
        assertThat(saved.getPolicyContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());
        assertThat(saved.getScore()).isNull();
        assertThat(saved.getRuleVersion()).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE:1.0.1");

        // Idempotency: replaying same evaluation returns same persisted decision
        EngineDecision replayed = decisionService.recordDecision(fraudAdapter.evaluate(context));
        assertThat(replayed.getId()).isEqualTo(saved.getId());

        em.flush();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from engine_decisions where engine_type = 'RELEASE'", Long.class)).isEqualTo(releaseBefore);

        verifyNoInteractions(banking);
    }

    @Test
    @DisplayName("2. Unrelated caller is denied before FRAUD evaluation")
    void testUnrelatedCallerDenied() {
        User otherUser = new User("Other", "other@test.invalid", "pass", Role.BUYER, UserStatus.ACTIVE);
        em.persist(otherUser);
        em.flush();

        UserPrincipal unrelated = UserPrincipal.create(otherUser);
        assertThatThrownBy(() -> fraudAdapter.evaluateForCaller(deal.getId(), context, unrelated))
                .isInstanceOf(com.finx.exception.UnauthorizedException.class);
    }
}
