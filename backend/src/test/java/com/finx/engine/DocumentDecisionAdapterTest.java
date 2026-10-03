package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.adapter.DocumentDecisionAdapter;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.escrow.service.EscrowService;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.*;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.invoice.repository.InvoiceReviewRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.risk.repository.BusinessRiskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentDecisionAdapterTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceAssessmentRepository invoiceAssessmentRepository;

    @Mock
    private InvoiceReviewRepository invoiceReviewRepository;

    @Mock
    private BusinessRiskRepository businessRiskRepository;

    @Mock
    private BankingProvider bankingProvider;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private EscrowService escrowService;

    private DocumentDecisionAdapter adapter;

    private UUID buyerOrgId;
    private UUID vendorOrgId;
    private UUID dealId;
    private UUID milestoneId;
    private UUID invoiceId;
    private UUID evaluationId;
    private UUID correlationId;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        adapter = new DocumentDecisionAdapter(invoiceRepository, invoiceAssessmentRepository, invoiceReviewRepository);

        buyerOrgId = UUID.randomUUID();
        vendorOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        evaluationId = UUID.randomUUID();
        correlationId = UUID.randomUUID();

        invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setBuyerOrganizationId(buyerOrgId);
        invoice.setVendorOrganizationId(vendorOrgId);
        invoice.setDealId(dealId);
        invoice.setMilestoneId(milestoneId);
        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setExtractionStatus(ExtractionStatus.EXTRACTED);
        invoice.setMatchingStatus(MatchingStatus.STRONG_MATCH);
        invoice.setDuplicateConfidence(DuplicateConfidence.UNIQUE);
        invoice.setMatchScore(95);
        invoice.setDocumentHash("a1b2c3d4e5f6");
        invoice.setPolicyVersion("invoice-policy-v1");
        invoice.setCreatedAt(Instant.now().minusSeconds(500));
        invoice.setUpdatedAt(Instant.now().minusSeconds(100));

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(invoice));
    }

    private EvaluationContext createContext() {
        return new EvaluationContext(
                evaluationId, buyerOrgId, correlationId,
                DecisionSubjectType.INVOICE.name(), invoiceId, Instant.now()
        );
    }

    @Test
    @DisplayName("1. Persisted InvoiceAssessment maps to DOCUMENT decision")
    void testPersistedAssessmentMapsToDocumentDecision() {
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_sha256_hash_123";
        assessment.resultJson = "{}";
        assessment.policyJson = "{}";
        assessment.createdAt = Instant.now().minusSeconds(200);

        InvoiceReview review = new InvoiceReview();
        review.setId(UUID.randomUUID());
        review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setAssessmentId(assessment.id);
        review.setReviewedAt(Instant.now());

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));
        when(invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED)).thenReturn(Optional.of(review));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getEngineType()).isEqualTo(EngineType.DOCUMENT);
        assertThat(command.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.getOrganizationId()).isEqualTo(buyerOrgId);
        assertThat(command.getSubjectType()).isEqualTo("INVOICE");
        assertThat(command.getSubjectId()).isEqualTo(invoiceId);
        assertThat(command.getScore()).isEqualByComparingTo(BigDecimal.valueOf(95));
    }

    @Test
    @DisplayName("2. Assessment ID is preserved as primary source assessment ID")
    void testAssessmentIdPreserved() {
        UUID expectedAssessmentId = UUID.randomUUID();
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = expectedAssessmentId;
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        InvoiceReview review = new InvoiceReview();
        review.setId(UUID.randomUUID());
        review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setAssessmentId(expectedAssessmentId);
        review.setReviewedAt(Instant.now());

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));
        when(invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED)).thenReturn(Optional.of(review));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getSourceAssessmentId()).isEqualTo(expectedAssessmentId);
        assertThat(command.getEvidenceReferences()).anyMatch(ref ->
                "INVOICE_ASSESSMENT".equals(ref.sourceType()) && expectedAssessmentId.equals(ref.sourceId()));
    }

    @Test
    @DisplayName("3. Invoice ID preserved in evidence references and subjectId")
    void testInvoiceIdPreserved() {
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getSubjectId()).isEqualTo(invoiceId);
        assertThat(command.getEvidenceReferences()).anyMatch(ref ->
                "INVOICE".equals(ref.sourceType()) && invoiceId.equals(ref.sourceId()));
    }

    @Test
    @DisplayName("4. Fingerprint preserved from persisted InvoiceAssessment")
    void testFingerprintPreserved() {
        String expectedFingerprint = "crypto_fingerprint_abc789";
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = expectedFingerprint;
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getInputFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(command.getEvidenceReferences()).anyMatch(ref ->
                "INVOICE_ASSESSMENT".equals(ref.sourceType()) && expectedFingerprint.equals(ref.fingerprint()));
    }

    @Test
    @DisplayName("5. Revision preserved from invoice entity")
    void testRevisionPreserved() {
        UUID parentInvoiceId = UUID.randomUUID();
        invoice.setRevisionOf(parentInvoiceId);

        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getInputRevision()).isEqualTo(parentInvoiceId.toString());
        assertThat(command.getEvidenceReferences()).anyMatch(ref ->
                parentInvoiceId.toString().equals(ref.revision()));
    }

    @Test
    @DisplayName("6. Policy snapshot/version preserved")
    void testPolicySnapshotPreserved() {
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getRuleVersion()).isEqualTo("invoice-policy-v1");
    }

    @Test
    @DisplayName("7. Manual provenance remains MANUAL_ENTRY")
    void testManualProvenanceRemainsManual() {
        invoice.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY);

        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getEvidenceReferences()).anyMatch(ref -> "MANUAL_ENTRY".equals(ref.provenance()));
    }

    @Test
    @DisplayName("8. Manual extraction is NOT mislabeled AI or OCR")
    void testManualNotMislabeledAiOrOcr() {
        invoice.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY);

        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        for (var ref : command.getEvidenceReferences()) {
            assertThat(ref.provenance()).doesNotContain("AI").doesNotContain("OCR").doesNotContain("ML");
        }
    }

    @Test
    @DisplayName("9. Missing assessment maps to REVIEW with DOCUMENT_ASSESSMENT_MISSING")
    void testMissingAssessmentMapsToReview() {
        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.empty());

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.getReasonCodes()).contains(DocumentDecisionAdapter.REASON_DOCUMENT_ASSESSMENT_MISSING);
        assertThat(command.getSourceAssessmentId()).isNull();
    }

    @Test
    @DisplayName("10. Superseded revision maps to REVIEW and is never ALLOW")
    void testSupersededRevisionMapsToReview() {
        // Newer invoice exists for the milestone
        Invoice newerInvoice = new Invoice();
        newerInvoice.setId(UUID.randomUUID());
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(newerInvoice));

        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.getReasonCodes()).contains(DocumentDecisionAdapter.REASON_DOCUMENT_SUPERSEDED_REVISION);
        assertThat(command.getDecision()).isNotEqualTo(DecisionStatus.ALLOW);
    }

    @Test
    @DisplayName("11. Stale / modified invoice after review maps to REVIEW under B10 freshness rules")
    void testModifiedInvoiceAfterReviewMapsToReview() {
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(500);

        InvoiceReview review = new InvoiceReview();
        review.setId(UUID.randomUUID());
        review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setAssessmentId(assessment.id);
        review.setReviewedAt(Instant.now().minusSeconds(300)); // Review was 300s ago

        invoice.setUpdatedAt(Instant.now().minusSeconds(50)); // Invoice modified 50s ago (after review!)

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));
        when(invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED)).thenReturn(Optional.of(review));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.getReasonCodes()).contains(DocumentDecisionAdapter.REASON_DOCUMENT_MODIFIED_AFTER_REVIEW);
    }

    @Test
    @DisplayName("12. Unresolved human review maps to REVIEW")
    void testUnresolvedHumanReviewMapsToReview() {
        invoice.setStatus(InvoiceStatus.REVIEW_REQUIRED);

        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.getReasonCodes()).contains(DocumentDecisionAdapter.REASON_DOCUMENT_REVIEW_REQUIRED);
    }

    @Test
    @DisplayName("13. Native hard mismatch / rejected maps to BLOCK")
    void testNativeHardMismatchMapsToBlock() {
        invoice.setStatus(InvoiceStatus.REJECTED);
        invoice.setRejectionReason("Fraudulent invoice layout detected");

        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(command.getReasonCodes()).contains(DocumentDecisionAdapter.REASON_DOCUMENT_REJECTED);
    }

    @Test
    @DisplayName("14. Current applicable approved assessment maps to ALLOW")
    void testCurrentApplicableApprovedAssessmentMapsToAllow() {
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_sha256_valid";
        assessment.createdAt = Instant.now().minusSeconds(200);

        InvoiceReview review = new InvoiceReview();
        review.setId(UUID.randomUUID());
        review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setAssessmentId(assessment.id);
        review.setReviewedAt(Instant.now()); // Reviewed after invoice updatedAt

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));
        when(invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED)).thenReturn(Optional.of(review));

        RecordEngineDecisionCommand command = adapter.evaluate(createContext());

        assertThat(command.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.getReasonCodes()).contains(DocumentDecisionAdapter.REASON_DOCUMENT_APPROVED);
        assertThat(command.getScore()).isEqualByComparingTo(BigDecimal.valueOf(95));
    }

    @Test
    @DisplayName("15-19. Adapter performs zero mutations on invoices, assessments, reviews, risk events, or banking")
    void testZeroSideEffectsOnDocumentAdapterRead() {
        InvoiceAssessment assessment = new InvoiceAssessment();
        assessment.id = UUID.randomUUID();
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_123";
        assessment.createdAt = Instant.now().minusSeconds(200);

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));

        // Evaluate 5 times
        for (int i = 0; i < 5; i++) {
            adapter.evaluate(createContext());
        }

        // 15. Zero invoice repo mutations
        verify(invoiceRepository, never()).save(any());
        verify(invoiceAssessmentRepository, never()).save(any());
        verify(invoiceReviewRepository, never()).save(any());

        // 16. Zero risk repo mutations
        verifyNoInteractions(businessRiskRepository);

        // 17. Zero banking calls
        verifyNoInteractions(bankingProvider);

        // 18. Zero ledger writes
        verifyNoInteractions(ledgerService);

        // 19. Zero escrow writes
        verifyNoInteractions(escrowService);
    }
}
