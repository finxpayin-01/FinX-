package com.finx.engine.release;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.policy.release.ReleasePolicyDefinition;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowAccountStatus;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.entity.InvoiceCheck;
import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.*;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.*;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.risk.dto.RiskDtos.Level;
import com.finx.risk.dto.RiskDtos.UserType;
import com.finx.risk.entity.RiskScore;
import com.finx.user.entity.User;
import com.finx.verification.entity.AuthorizedRepresentative;
import com.finx.verification.entity.KybProfile;
import com.finx.verification.entity.KycProfile;
import com.finx.verification.enums.AuthorityRelationshipType;
import com.finx.verification.enums.AuthorityStatus;
import com.finx.verification.enums.VerificationStatus;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(isolation = Isolation.REPEATABLE_READ)
class ReleaseDecisionPersistenceTest {

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReleaseDecisionEngine releaseDecisionEngine;
    @MockBean private BankingProvider banking;

    private User buyer;
    private User seller;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private Deal deal;
    private Milestone milestone;

    private static final List<String> UNCHANGED_TABLES = List.of(
            "settlement_instructions",
            "journal_entries",
            "journal_lines",
            "escrow_ledger",
            "disputes"
    );

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();

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

        OrganizationMember sellerMember = new OrganizationMember(sellerOrg.getId(), seller.getId(), OrgRole.OWNER);
        sellerMember.setStatus(MemberStatus.ACTIVE);
        em.persist(sellerMember);

        deal = new Deal("Release Persistence Deal", "", buyer.getId(), seller.getId(), new BigDecimal("10000.00"), "INR", DealStatus.IN_PROGRESS);
        deal.setBuyerOrganizationId(buyerOrg.getId());
        deal.setSellerOrganizationId(sellerOrg.getId());
        em.persist(deal);

        milestone = new Milestone(deal.getId(), "Milestone 1", "Delivery of modules", 1, new BigDecimal("10000.00"), "INR", now.plusSeconds(86400));
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setInvoiceRequired(true);
        em.persist(milestone);

        // Agreement
        DealAgreement agreement = new DealAgreement(deal.getId(), 1, "Agreement Title", "Agreement text", "sha256_agree_mock");
        agreement.setStatus(AgreementStatus.SIGNED);
        agreement.setBuyerAcceptedBy(buyer.getId());
        agreement.setBuyerAcceptedAt(now.minusSeconds(200));
        agreement.setSellerAcceptedBy(seller.getId());
        agreement.setSellerAcceptedAt(now.minusSeconds(190));
        em.persist(agreement);

        // Deliverable
        Deliverable deliverable = new Deliverable(milestone.getId(), buyer.getId(), "file.pdf", "https://finx.mock/file.pdf", "Final artifact");
        deliverable.setStatus(DeliverableStatus.APPROVED);
        deliverable.setReviewedAt(now.minusSeconds(100));
        deliverable.setReviewedBy(buyer.getId());
        em.persist(deliverable);

        // Escrow Account
        EscrowAccount escrow = new EscrowAccount(deal.getId(), new BigDecimal("10000.00"), "INR");
        escrow.setStatus(EscrowAccountStatus.ACTIVE);
        em.persist(escrow);

        // Seller Bank Account
        OrganizationBankAccount bankAcc = new OrganizationBankAccount(sellerOrg.getId(), "Seller Legal", "****1234", "test-hash", "HDFC0001234", "Test", "CURRENT", true);
        bankAcc.setEncryptedAccountNumber("enc_acc_123");
        bankAcc.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAcc.setNameMatchStatus("EXACT_MATCH");
        bankAcc.setVerifiedAt(now.minusSeconds(500));
        em.persist(bankAcc);

        // Seller Trust / KYB / KYC
        KybProfile kyb = new KybProfile(sellerOrg.getId(), "AAACB1234C", "29AAACB1234C1Z5", "U12345KA2020PTC123456", "UDYAM-1234");
        kyb.setStatus(VerificationStatus.VERIFIED);
        kyb.setPanStatus(VerificationStatus.VERIFIED);
        kyb.setGstStatus(VerificationStatus.VERIFIED);
        kyb.setRegistrationStatus(VerificationStatus.VERIFIED);
        kyb.setNameMatchStatus("MATCH");
        kyb.setVerifiedAt(now.minusSeconds(500));
        em.persist(kyb);

        AuthorizedRepresentative rep = new AuthorizedRepresentative(sellerOrg.getId(), seller.getId(), AuthorityRelationshipType.DIRECTOR);
        rep.setAuthorityStatus(AuthorityStatus.VERIFIED);
        rep.setVerifiedAt(now.minusSeconds(500));
        em.persist(rep);

        KycProfile kyc = new KycProfile(seller.getId(), "Seller User", "AAACB1234K");
        kyc.setStatus(VerificationStatus.VERIFIED);
        kyc.setPanStatus(VerificationStatus.VERIFIED);
        kyc.setIdentityStatus(VerificationStatus.VERIFIED);
        kyc.setNameMatchStatus("MATCH");
        kyc.setVerifiedAt(now.minusSeconds(500));
        em.persist(kyc);

        // Risk evidence
        RiskScore riskScore = new RiskScore();
        riskScore.subjectKey = "ORG:" + buyerOrg.getId();
        riskScore.organizationId = buyerOrg.getId();
        riskScore.userId = buyer.getId();
        riskScore.userType = UserType.BUYER;
        riskScore.score = 25;
        riskScore.riskLevel = Level.LOW;
        riskScore.calculatedAt = now.minusSeconds(500);
        em.persist(riskScore);

        // Invoice evidence
        Invoice inv = new Invoice();
        inv.setDealId(deal.getId());
        inv.setMilestoneId(milestone.getId());
        inv.setBuyerOrganizationId(buyerOrg.getId());
        inv.setVendorOrganizationId(sellerOrg.getId());
        inv.setInvoiceNumber("INV-REL-001");
        inv.setNormalizedInvoiceNumber("INVREL001");
        inv.setTotalAmount(new BigDecimal("10000.00"));
        inv.setFileName("invoice.pdf");
        inv.setFileUrl("private/invoices/inv.pdf");
        inv.setCreatedBy(seller.getId());
        inv.setStatus(InvoiceStatus.APPROVED);
        inv.setMatchingStatus(MatchingStatus.STRONG_MATCH);
        inv.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY);
        inv.setDocumentHash("doc_hash_rel_001");
        inv.setMatchScore(98);
        inv.setPolicyVersion("1.0.0");
        inv.setInvoiceDate(LocalDate.now());
        inv.setCreatedAt(now.minusSeconds(400));
        inv.setUpdatedAt(now.minusSeconds(350));
        em.persist(inv);

        InvoiceCheck checkHash = new InvoiceCheck(inv, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED", "Doc ok", 10, 10, null);
        em.persist(checkHash);

        InvoiceAssessment invAssess = new InvoiceAssessment();
        invAssess.invoiceId = inv.getId();
        invAssess.fingerprint = "inv_fp_001";
        invAssess.policyJson = "{}";
        invAssess.resultJson = "{}";
        invAssess.createdAt = now.minusSeconds(380);
        em.persist(invAssess);

        InvoiceReview invRev = new InvoiceReview();
        invRev.setInvoice(inv);
        invRev.setAssessmentId(invAssess.id);
        invRev.setStatus(InvoiceReviewStatus.APPROVED);
        invRev.setDecision(ReviewDecision.APPROVE);
        invRev.setReviewerId(buyer.getId());
        invRev.setReviewedAt(now.minusSeconds(300));
        invRev.setCreatedAt(now.minusSeconds(300));
        em.persist(invRev);

        // Reconciled Payment
        PaymentInstruction pi = new PaymentInstruction(
                "INST-REL-" + UUID.randomUUID(),
                deal.getId(),
                milestone.getId(),
                buyerOrg.getId(),
                sellerOrg.getId(),
                new BigDecimal("10000.00"),
                "INR",
                "BANK",
                "idem-key-" + UUID.randomUUID(),
                buyer.getId()
        );
        pi.setStatus(InstructionStatus.SUCCESS);
        em.persist(pi);

        PaymentTransaction pt = new PaymentTransaction(
                "TX-REL-" + UUID.randomUUID(),
                pi.getId(),
                "BANK",
                "PROV-REF-REL",
                new BigDecimal("10000.00"),
                "INR",
                "SUCCESS",
                "UTR-REL-001",
                "{}"
        );
        pt.setCreatedAt(now.minusSeconds(300));
        em.persist(pt);

        String sourceHash = "recon-rel-hash-999";
        BankStatementImport imp = new BankStatementImport(
                "BANK", "ACC-REL", now.minusSeconds(1000), now, sourceHash,
                new BigDecimal("10000.00"), new BigDecimal("20000.00"), "INR", buyer.getId()
        );
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        em.persist(imp);

        BankStatementLine line = new BankStatementLine(
                imp.getId(), "BANK", "ACC-REL", "LINE-REL-1", pt.getTransactionReference(), pt.getUtrNumber(),
                new BigDecimal("10000.00"), "INR", FinancialDirection.CREDIT, "SUCCESS",
                BankStatementLineStatus.FINAL_SUCCESS, now.minusSeconds(250), null, "line-fp-rel"
        );
        em.persist(line);

        ReconciliationRun run = new ReconciliationRun("RUN-REL-" + UUID.randomUUID(), "BANK", buyer.getId());
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(imp.getId());
        run.setEndTime(now.minusSeconds(100));
        run.setMatchedCount(1);
        run.setMismatchCount(0);
        em.persist(run);

        ReconciliationItem item = new ReconciliationItem(
                run.getId(), pt.getTransactionReference(), line.getTransactionReference(),
                pt.getAmount(), line.getAmount(), ReconciliationItemStatus.MATCHED, "Clean release match"
        );
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item.setInternalEffectId(pt.getId());
        item.setBankStatementLineId(line.getId());
        item.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        em.persist(item);

        em.flush();
    }

    private Map<String, Integer> countUnchangedTables() {
        return UNCHANGED_TABLES.stream().collect(Collectors.toMap(
                t -> t,
                t -> jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)
        ));
    }

    @Test
    @DisplayName("Evaluate and persist RELEASE EngineDecision with exactly 8 component engine records")
    void persistReleaseDecision() {
        Map<String, Integer> beforeCounts = countUnchangedTables();

        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        Instant evalTime = Instant.now();

        ReleaseEvaluationResult result = releaseDecisionEngine.evaluateAndRecordMilestone(
                deal.getId(),
                milestone.getId(),
                evalId,
                corrId,
                evalTime,
                null
        );

        em.flush();

        // 1. Verify component decisions
        assertThat(result.componentDecisions()).hasSize(8);
        for (EngineDecision comp : result.componentDecisions()) {
            assertThat(comp.getId()).isNotNull();
            assertThat(comp.getEvaluationId()).isEqualTo(evalId);
            assertThat(comp.getCorrelationId()).isEqualTo(corrId);
        }

        // 2. Verify RELEASE decision
        EngineDecision release = result.releaseDecision();
        assertThat(release).isNotNull();
        assertThat(release.getId()).isNotNull();
        assertThat(release.getEngineType()).isEqualTo(EngineType.RELEASE);
        assertThat(release.getSubjectType()).isEqualTo("MILESTONE");
        assertThat(release.getSubjectId()).isEqualTo(milestone.getId());
        assertThat(release.getOrganizationId()).isEqualTo(buyerOrg.getId());
        assertThat(release.getPolicyVersionId()).isNotNull();
        assertThat(release.getPolicyContentHash()).isEqualTo(ReleasePolicyDefinition.getExpectedContentHash());
        assertThat(release.getScore()).isNull(); // strictly null
        assertThat(release.getInputFingerprint()).isNotBlank();
        assertThat(release.getEvidenceReferences()).isNotEmpty();

        // 3. Verify zero financial mutations and zero banking calls
        Map<String, Integer> afterCounts = countUnchangedTables();
        assertThat(afterCounts).isEqualTo(beforeCounts);
        verifyNoInteractions(banking);
    }
}
