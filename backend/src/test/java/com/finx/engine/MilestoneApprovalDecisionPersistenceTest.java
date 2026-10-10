package com.finx.engine;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.*;
import com.finx.deal.entity.*;
import com.finx.dispute.entity.Dispute;
import com.finx.engine.adapter.*;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.*;
import com.finx.engine.service.EngineDecisionService;
import com.finx.escrow.entity.*;
import com.finx.invoice.entity.*;
import com.finx.invoice.enums.*;
import com.finx.milestone.entity.*;
import com.finx.org.entity.*;
import com.finx.org.enums.OrgEnums.*;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.entity.*;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(isolation = Isolation.REPEATABLE_READ)
class MilestoneApprovalDecisionPersistenceTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MilestoneRulesDecisionAdapter rules;
    @Autowired ApprovalDecisionAdapter approval;
    @Autowired EngineDecisionService decisions;
    @MockBean BankingProvider banking;
    Deal deal;
    Milestone milestone;
    EscrowAccount escrow;
    User buyer;
    OrganizationMember member;
    EvaluationContext context;
    private static final List<String> UNCHANGED_TABLES = List.of("settlement_instructions", "payment_instructions",
            "payment_transactions", "journal_entries", "journal_lines", "escrow_accounts", "escrow_ledger", "milestones",
            "deliverables", "invoices", "invoice_reviews", "invoice_assessments", "deal_agreements", "risk_reviews",
            "risk_scores", "risk_score_history", "risk_events", "organization_bank_accounts", "milestone_approval_bindings");

    @BeforeEach void fixture() {
        buyer = new User("Buyer", UUID.randomUUID() + "@test.invalid", "test", Role.BUYER, UserStatus.ACTIVE); em.persist(buyer);
        User seller = new User("Seller", UUID.randomUUID() + "@test.invalid", "test", Role.SELLER, UserStatus.ACTIVE); em.persist(seller);
        Organization buyerOrg = organization("Buyer", buyer.getId()); Organization sellerOrg = organization("Seller", seller.getId());
        member = new OrganizationMember(buyerOrg.getId(), buyer.getId(), OrgRole.OWNER); member.setStatus(MemberStatus.ACTIVE); em.persist(member);
        deal = new Deal("Adapter persistence", "", buyer.getId(), seller.getId(), new BigDecimal("100"), "INR", DealStatus.IN_PROGRESS);
        deal.setBuyerOrganizationId(buyerOrg.getId()); deal.setSellerOrganizationId(sellerOrg.getId()); em.persist(deal);
        milestone = new Milestone(deal.getId(), "Milestone", "", 1, new BigDecimal("100"), "INR", Instant.now().plusSeconds(3600));
        milestone.setStatus(MilestoneStatus.APPROVED); milestone.setInvoiceRequired(true); em.persist(milestone);
        DealAgreement agreement = new DealAgreement(deal.getId(), 1, "Agreement", "terms", "a".repeat(64));
        agreement.setStatus(AgreementStatus.SIGNED); em.persist(agreement);
        Deliverable deliverable = new Deliverable(milestone.getId(), seller.getId(), "evidence", "private/reference", "");
        deliverable.setStatus(DeliverableStatus.APPROVED); deliverable.setReviewedBy(buyer.getId()); em.persist(deliverable);
        escrow = new EscrowAccount(deal.getId(), new BigDecimal("100"), "INR"); em.persist(escrow);
        PaymentInstruction instruction = new PaymentInstruction("TEST-" + UUID.randomUUID(), deal.getId(), milestone.getId(),
                buyerOrg.getId(), sellerOrg.getId(), new BigDecimal("100"), "INR", "MOCK", UUID.randomUUID().toString(), buyer.getId());
        instruction.setStatus(InstructionStatus.SUCCESS); em.persist(instruction);
        em.persist(new EscrowLedger(escrow.getId(), null, instruction.getId(), milestone.getId(), TransactionType.FUND,
                new BigDecimal("100"), new BigDecimal("100"), "Confirmed test funding"));
        OrganizationBankAccount bank = new OrganizationBankAccount(sellerOrg.getId(), "Seller", "****1234", "test-hash", "TEST0000001", "Test", "Test", true);
        bank.setEncryptedAccountNumber("test-fixture-ciphertext"); bank.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bank.setVerifiedAt(Instant.now()); bank.setNameMatchStatus("MATCH"); bank.setProvider("MOCK"); em.persist(bank);
        Invoice invoice = new Invoice(); invoice.setDealId(deal.getId()); invoice.setMilestoneId(milestone.getId());
        invoice.setBuyerOrganizationId(buyerOrg.getId()); invoice.setVendorOrganizationId(sellerOrg.getId());
        invoice.setInvoiceNumber("test"); invoice.setNormalizedInvoiceNumber("TEST"); invoice.setTotalAmount(new BigDecimal("100"));
        invoice.setFileName("test.pdf"); invoice.setFileUrl("private/reference"); invoice.setDocumentHash("b".repeat(64));
        invoice.setCreatedBy(seller.getId()); invoice.setStatus(InvoiceStatus.APPROVED); invoice.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY); em.persist(invoice);
        InvoiceAssessment assessment = new InvoiceAssessment(); assessment.invoiceId = invoice.getId(); assessment.fingerprint = "c".repeat(64);
        assessment.resultJson = "{}"; assessment.policyJson = "{}"; em.persist(assessment); em.flush();
        InvoiceReview review = new InvoiceReview(invoice, "Human reviewed"); review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setDecision(ReviewDecision.APPROVE); review.setAssessmentId(assessment.id); review.setReviewerId(buyer.getId());
        review.setReviewedAt(Instant.now()); em.persist(review); deliverable.setReviewedAt(Instant.now()); em.flush();
        context = new EvaluationContext(UUID.randomUUID(), buyerOrg.getId(), UUID.randomUUID(), "MILESTONE", milestone.getId(), Instant.now());
        clearInvocations(banking);
    }

    private Organization organization(String name, UUID user) {
        Organization org = new Organization(name, name, EntityType.PRIVATE_LIMITED, null, null, null, null, user); em.persist(org); return org;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : UNCHANGED_TABLES) result.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        return result;
    }

    @Test void twoDurableDecisionsShareContextReplayAndProduceZeroSideEffects() {
        var before = snapshot();
        long releaseBefore = jdbc.queryForObject("select count(*) from engine_decisions where engine_type = 'RELEASE'", Long.class);
        var contractual = rules.evaluateForCaller(deal.getId(), context, UserPrincipal.create(buyer));
        var applicable = approval.evaluateForCaller(deal.getId(), context, UserPrincipal.create(buyer));
        assertThat(contractual.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(applicable.decision()).isEqualTo(DecisionStatus.REVIEW);
        List<EngineDecision> saved = List.of(decisions.recordDecision(contractual), decisions.recordDecision(applicable)); em.flush();
        assertThat(decisions.findByEvaluation(context.organizationId(), context.evaluationId())).hasSize(2);
        assertThat(saved).extracting(EngineDecision::getEngineType).containsExactly(EngineType.MILESTONE_RULES, EngineType.APPROVAL);
        assertThat(saved).allSatisfy(d -> {
            assertThat(d.getOrganizationId()).isEqualTo(deal.getBuyerOrganizationId());
            assertThat(d.getSubjectId()).isEqualTo(milestone.getId()); assertThat(d.getSubjectType()).isEqualTo("MILESTONE");
            assertThat(d.getEvaluationId()).isEqualTo(context.evaluationId()); assertThat(d.getCorrelationId()).isEqualTo(context.correlationId());
            assertReferences(d);
        });
        assertThat(decisions.recordDecision(rules.evaluate(context)).getId()).isEqualTo(saved.getFirst().getId());
        assertThat(decisions.recordDecision(approval.evaluate(context)).getId()).isEqualTo(saved.getLast().getId());
        em.flush(); assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from engine_decisions where engine_type = 'RELEASE'", Long.class)).isEqualTo(releaseBefore);
        assertThat(decisions.findByEvaluation(UUID.randomUUID(), context.evaluationId())).isEmpty(); verifyNoInteractions(banking);
    }

    @Test void prohibitedSourcesAlsoHaveRealDurableReferences() {
        Dispute dispute = new Dispute(deal.getId(), milestone.getId(), buyer.getId(), "test"); em.persist(dispute);
        EscrowLedger release = new EscrowLedger(escrow.getId(), null, milestone.getId(), TransactionType.RELEASE, new BigDecimal("100"), BigDecimal.ZERO, "Historical fixture"); em.persist(release);
        RiskScore score = new RiskScore(); score.subjectKey = "TEST:" + UUID.randomUUID(); score.userId = buyer.getId();
        score.organizationId = deal.getBuyerOrganizationId(); score.userType = com.finx.risk.dto.RiskDtos.UserType.BUYER; em.persist(score);
        RiskReview restriction = new RiskReview(); restriction.subjectId = score.id; restriction.assessmentId = UUID.randomUUID();
        restriction.alertId = UUID.randomUUID(); restriction.status = ReviewStatus.RESTRICTED; restriction.restrictionUntil = Instant.now().plusSeconds(3600); em.persist(restriction); em.flush();
        var before = snapshot(); var a = decisions.recordDecision(rules.evaluate(context)); var b = decisions.recordDecision(approval.evaluate(context)); em.flush();
        assertThat(a.getReasonCodes()).contains("MILESTONE_ALREADY_RELEASED", "MILESTONE_ACTIVE_DISPUTE");
        assertThat(b.getReasonCodes()).contains("APPROVAL_RISK_RESTRICTION_ACTIVE", "APPROVAL_DISPUTE_ACTIVE");
        assertReferences(a); assertReferences(b); assertThat(snapshot()).isEqualTo(before); verifyNoInteractions(banking);
    }

    @Test void inactiveAndUnrelatedCallersAreDeniedBeforeDecisionPersistence() {
        UserPrincipal unrelated = new UserPrincipal(UUID.randomUUID(), "Other", "other@test.invalid", "test", Role.BUYER, UserStatus.ACTIVE, List.of());
        assertThatThrownBy(() -> rules.evaluateForCaller(deal.getId(), context, unrelated)).isInstanceOf(com.finx.exception.UnauthorizedException.class);
        assertThatThrownBy(() -> approval.evaluateForCaller(deal.getId(), context, unrelated)).isInstanceOf(com.finx.exception.UnauthorizedException.class);
        member.setStatus(MemberStatus.SUSPENDED); em.flush();
        assertThatThrownBy(() -> rules.evaluateForCaller(deal.getId(), context, UserPrincipal.create(buyer))).isInstanceOf(com.finx.exception.UnauthorizedException.class);
        assertThatThrownBy(() -> approval.evaluateForCaller(deal.getId(), context, UserPrincipal.create(buyer))).isInstanceOf(com.finx.exception.UnauthorizedException.class);
        assertThat(decisions.findByEvaluation(context.organizationId(), context.evaluationId())).isEmpty(); verifyNoInteractions(banking);
    }

    private void assertReferences(EngineDecision d) {
        Map<String, Class<?>> types = Map.ofEntries(Map.entry("DEAL", Deal.class), Map.entry("MILESTONE", Milestone.class),
                Map.entry("AGREEMENT", DealAgreement.class), Map.entry("DELIVERABLE", Deliverable.class), Map.entry("INVOICE", Invoice.class),
                Map.entry("INVOICE_ASSESSMENT", InvoiceAssessment.class), Map.entry("INVOICE_REVIEW", InvoiceReview.class),
                Map.entry("ESCROW_ACCOUNT", EscrowAccount.class), Map.entry("ESCROW_LEDGER", EscrowLedger.class),
                Map.entry("DISPUTE", Dispute.class), Map.entry("ORGANIZATION_BANK_ACCOUNT", OrganizationBankAccount.class),
                Map.entry("RISK_REVIEW", RiskReview.class), Map.entry("MILESTONE_APPROVAL_BINDING", com.finx.approval.entity.MilestoneApprovalBinding.class));
        assertThat(d.getEvidenceReferences()).isNotEmpty().allSatisfy(ref -> {
            assertThat(types).containsKey(ref.sourceType()); assertThat(em.find(types.get(ref.sourceType()), ref.sourceId())).isNotNull();
        });
    }
}
