package com.finx.engine;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.deal.entity.*;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.engine.adapter.*;
import com.finx.engine.model.*;
import com.finx.escrow.entity.*;
import com.finx.escrow.repository.*;
import com.finx.invoice.entity.*;
import com.finx.invoice.enums.*;
import com.finx.invoice.repository.*;
import com.finx.milestone.entity.*;
import com.finx.milestone.repository.*;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.repository.*;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.verification.service.VerificationEligibilityService;
import org.junit.jupiter.api.BeforeEach;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Real B10 service and adapters; only their repository boundaries are mocked. */
abstract class MilestoneAdapterFixture {
    final DealRepository deals = mock(DealRepository.class);
    final MilestoneRepository milestones = mock(MilestoneRepository.class);
    final DeliverableRepository deliverables = mock(DeliverableRepository.class);
    final DealAgreementRepository agreements = mock(DealAgreementRepository.class);
    final DisputeRepository disputes = mock(DisputeRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final InvoiceAssessmentRepository assessments = mock(InvoiceAssessmentRepository.class);
    final InvoiceReviewRepository reviews = mock(InvoiceReviewRepository.class);
    final OrganizationRepository organizations = mock(OrganizationRepository.class);
    final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    final OrganizationBankAccountRepository banks = mock(OrganizationBankAccountRepository.class);
    final EscrowAccountRepository accounts = mock(EscrowAccountRepository.class);
    final EscrowLedgerRepository ledger = mock(EscrowLedgerRepository.class);
    final BusinessRiskRepository risk = mock(BusinessRiskRepository.class);
    final VerificationEligibilityService eligibility = mock(VerificationEligibilityService.class);
    final Instant now = Instant.parse("2026-10-04T12:00:00Z");
    Deal deal;
    Milestone milestone;
    Deliverable deliverable;
    DealAgreement agreement;
    EscrowAccount account;
    Invoice invoice;
    InvoiceAssessment assessment;
    InvoiceReview review;
    OrganizationBankAccount bank;
    EvaluationContext context;
    MilestoneRulesDecisionAdapter rules;
    ApprovalDecisionAdapter approval;
    List<Milestone> allocation;
    List<Deliverable> submissions;
    List<Dispute> openDisputes;

    @BeforeEach void fixture() {
        deal = new Deal("test", "", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "INR", DealStatus.IN_PROGRESS);
        deal.setId(UUID.randomUUID()); deal.setBuyerOrganizationId(UUID.randomUUID()); deal.setSellerOrganizationId(UUID.randomUUID());
        milestone = new Milestone(deal.getId(), "test", "", 1, new BigDecimal("100.00"), "INR", now.plusSeconds(3600));
        milestone.setId(UUID.randomUUID()); milestone.setStatus(MilestoneStatus.APPROVED); milestone.setUpdatedAt(now.minusSeconds(100));
        context = new EvaluationContext(UUID.randomUUID(), deal.getBuyerOrganizationId(), UUID.randomUUID(), "MILESTONE", milestone.getId(), now);
        deliverable = new Deliverable(milestone.getId(), deal.getSellerId(), "test", "test", "");
        deliverable.setId(UUID.randomUUID()); deliverable.setStatus(DeliverableStatus.APPROVED);
        deliverable.setSubmittedAt(now.minusSeconds(200)); deliverable.setReviewedAt(now.minusSeconds(50)); deliverable.setReviewedBy(deal.getBuyerId());
        agreement = new DealAgreement(deal.getId(), 1, "test", "terms", "a".repeat(64)); agreement.setStatus(AgreementStatus.SIGNED);
        account = new EscrowAccount(deal.getId(), new BigDecimal("100.00"), "INR"); account.setId(UUID.randomUUID()); account.setVersion(1L);
        allocation = new ArrayList<>(List.of(milestone)); submissions = new ArrayList<>(List.of(deliverable)); openDisputes = new ArrayList<>();
        when(deals.findById(deal.getId())).thenReturn(Optional.of(deal));
        when(milestones.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(organizations.existsById(context.organizationId())).thenReturn(true);
        when(milestones.findByDealIdOrderBySequenceAsc(deal.getId())).thenAnswer(i -> allocation);
        when(deliverables.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenAnswer(i -> submissions);
        when(agreements.findTopByDealIdOrderByVersionDesc(deal.getId())).thenReturn(Optional.of(agreement));
        when(disputes.findByDealIdOrderByCreatedAtDesc(deal.getId())).thenAnswer(i -> openDisputes);
        when(accounts.findByDealId(deal.getId())).thenReturn(Optional.of(account));
        bank = new OrganizationBankAccount(); bank.setOrganizationId(deal.getSellerOrganizationId()); bank.setVerifiedAt(now.minusSeconds(500));
        when(eligibility.evaluateBeneficiaryEligibility(deal.getSellerOrganizationId())).thenReturn(
                new VerificationEligibilityService.BeneficiaryEligibilityResult(true, bank, null, true));
        when(banks.findById(bank.getId())).thenReturn(Optional.of(bank));
        var nativeService = new ApprovalApplicabilityService(deals, milestones, deliverables, invoices, reviews, assessments,
                agreements, eligibility, disputes, null, risk);
        var reader = new MilestoneDecisionEvidenceReader(deals, milestones, organizations, new DealAccessService(members),
                deliverables, agreements, disputes, invoices, assessments, reviews, nativeService);
        rules = new MilestoneRulesDecisionAdapter(reader, milestones, accounts, ledger);
        approval = new ApprovalDecisionAdapter(reader, nativeService, banks, risk);
    }

    void invoice() {
        milestone.setInvoiceRequired(true);
        invoice = new Invoice(); invoice.setId(UUID.randomUUID()); invoice.setDealId(deal.getId()); invoice.setMilestoneId(milestone.getId());
        invoice.setBuyerOrganizationId(deal.getBuyerOrganizationId()); invoice.setVendorOrganizationId(deal.getSellerOrganizationId());
        invoice.setStatus(InvoiceStatus.APPROVED); invoice.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY); invoice.setUpdatedAt(now.minusSeconds(100));
        assessment = new InvoiceAssessment(); assessment.invoiceId = invoice.getId(); assessment.fingerprint = "b".repeat(64); assessment.createdAt = now.minusSeconds(200);
        review = new InvoiceReview(invoice, ""); review.setId(UUID.randomUUID()); review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setDecision(ReviewDecision.APPROVE); review.setAssessmentId(assessment.id); review.setReviewerId(deal.getBuyerId()); review.setReviewedAt(now.minusSeconds(50));
        when(invoices.findFirstByMilestoneIdOrderByCreatedAtDesc(milestone.getId())).thenReturn(Optional.of(invoice));
        when(assessments.findFirstByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).thenReturn(Optional.of(assessment));
        when(reviews.findFirstByInvoiceIdAndStatus(invoice.getId(), InvoiceReviewStatus.APPROVED)).thenAnswer(i ->
                review.getStatus() == InvoiceReviewStatus.APPROVED ? Optional.of(review) : Optional.empty());
        when(reviews.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).thenReturn(List.of(review));
    }

    void dispute() {
        Dispute d = new Dispute(deal.getId(), milestone.getId(), deal.getBuyerId(), "test"); d.setId(UUID.randomUUID()); openDisputes.add(d);
    }

    void prior(MilestoneStatus status) {
        milestone.setSequence(2); milestone.setAmount(new BigDecimal("50"));
        Milestone p = new Milestone(deal.getId(), "prior", "", 1, new BigDecimal("50"), "INR", null);
        p.setId(UUID.randomUUID()); p.setStatus(status); allocation.add(p);
    }

    void assertDecision(RecordEngineDecisionCommand command, DecisionStatus status, String reason) {
        assertThat(command.decision()).isEqualTo(status); assertThat(command.reasonCodes()).contains(reason);
        assertThat(command.organizationId()).isEqualTo(deal.getBuyerOrganizationId()).isNotEqualTo(deal.getBuyerId());
        assertThat(command.subjectType()).isEqualTo("MILESTONE"); assertThat(command.subjectId()).isEqualTo(milestone.getId());
        assertThat(command.inputFingerprint()).matches("[0-9a-f]{64}");
    }

    void assertReadOnly() {
        for (Object repository : List.of(deals, milestones, deliverables, agreements, disputes, invoices, assessments, reviews,
                organizations, members, banks, accounts, ledger, risk)) {
            assertThat(mockingDetails(repository).getInvocations()).allSatisfy(inv ->
                    assertThat(inv.getMethod().getName()).doesNotMatch("save.*|delete.*|persist|flush|lock|.*ForUpdate"));
        }
    }
}
