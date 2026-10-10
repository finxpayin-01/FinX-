package com.finx.approval;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.approval.repository.MilestoneApprovalBindingRepository;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.approval.service.MilestoneApprovalFingerprintService;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilestoneApprovalBindingServiceTest {

    @Mock
    private MilestoneApprovalBindingRepository bindingRepository;
    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private DeliverableRepository deliverableRepository;
    @Mock
    private DealRepository dealRepository;

    private MilestoneApprovalFingerprintService fingerprintService;
    private MilestoneApprovalBindingService bindingService;

    private Deal deal;
    private Milestone milestone;
    private Deliverable deliverable;
    private UUID buyerOrgId;
    private UUID buyerId;
    private UUID pmId;
    private Instant now;

    @BeforeEach
    void setUp() {
        fingerprintService = new MilestoneApprovalFingerprintService();
        bindingService = new MilestoneApprovalBindingService(
                bindingRepository,
                fingerprintService,
                milestoneRepository,
                deliverableRepository,
                dealRepository
        );

        now = Instant.parse("2026-10-10T12:00:00Z");
        buyerOrgId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        pmId = UUID.randomUUID();

        deal = new Deal("Test Deal", "Description", buyerId, UUID.randomUUID(), new BigDecimal("1000.00"), "INR", DealStatus.IN_PROGRESS);
        deal.setId(UUID.randomUUID());
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setProjectManagerId(pmId);

        milestone = new Milestone(deal.getId(), "M1", "Desc", 1, new BigDecimal("500.00"), "INR", now.plusSeconds(86400));
        milestone.setId(UUID.randomUUID());
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setInvoiceRequired(true);
        milestone.setSubmissionDeadline(now.plusSeconds(43200));

        deliverable = new Deliverable(milestone.getId(), deal.getSellerId(), "Doc", "Spec", "https://finx.local/file1.pdf");
        deliverable.setId(UUID.randomUUID());
        deliverable.setStatus(DeliverableStatus.APPROVED);
        deliverable.setSubmittedAt(now.minusSeconds(3600));
        deliverable.setReviewedAt(now);
        deliverable.setReviewedBy(buyerId);

        lenient().when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));
        lenient().when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
    }

    private MilestoneApprovalBinding createSampleBinding(MilestoneApprovalAuthority authority, UUID approvedBy, UUID pm) {
        String mHash = fingerprintService.computeMilestoneFingerprint(milestone);
        String dHash = fingerprintService.computeDeliverablesFingerprint(List.of(deliverable));
        String aHash = fingerprintService.computeApprovalFingerprint(
                buyerOrgId, deal.getId(), milestone.getId(), approvedBy, authority, pm, mHash, dHash, now
        );
        return new MilestoneApprovalBinding(
                UUID.randomUUID(), buyerOrgId, deal.getId(), milestone.getId(),
                approvedBy, authority, mHash, dHash, aHash, pm, now, now
        );
    }

    // 1. current buyer approval binding valid
    @Test
    void currentBuyerApprovalBindingValid() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isTrue();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_CURRENT");
        assertThat(eval.binding()).isEqualTo(binding);
    }

    // 2. missing binding -> not current
    @Test
    void missingBindingNotCurrent() {
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.empty());

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isFalse();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_MISSING");
        assertThat(eval.binding()).isNull();
    }

    // 3. milestone amount change -> stale
    @Test
    void milestoneAmountChangeStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        milestone.setAmount(new BigDecimal("600.00")); // changed

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_MILESTONE_CHANGED");
    }

    // 4. milestone currency change -> stale
    @Test
    void milestoneCurrencyChangeStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        milestone.setCurrency("USD"); // changed

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_MILESTONE_CHANGED");
    }

    // 5. sequence change -> stale
    @Test
    void sequenceChangeStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        milestone.setSequence(2); // changed

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_MILESTONE_CHANGED");
    }

    // 6. invoiceRequired change -> stale
    @Test
    void invoiceRequiredChangeStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        milestone.setInvoiceRequired(false); // changed

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_MILESTONE_CHANGED");
    }

    // 7. due date change -> stale if fingerprint includes it
    @Test
    void dueDateChangeStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        milestone.setDueDate(now.plusSeconds(999999)); // changed

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_MILESTONE_CHANGED");
    }

    // 8. submission deadline change -> stale if included
    @Test
    void submissionDeadlineChangeStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        milestone.setSubmissionDeadline(now.plusSeconds(888888)); // changed

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_MILESTONE_CHANGED");
    }

    // 9. deliverable added after approval -> stale
    @Test
    void deliverableAddedAfterApprovalStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));

        Deliverable extra = new Deliverable(milestone.getId(), deal.getSellerId(), "Doc2", "Spec2", "https://finx.local/file2.pdf");
        extra.setId(UUID.randomUUID());
        extra.setStatus(DeliverableStatus.APPROVED);
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable, extra));

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_DELIVERABLES_CHANGED");
    }

    // 10. deliverable status changed -> stale
    @Test
    void deliverableStatusChangedStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));

        deliverable.setStatus(DeliverableStatus.REJECTED); // changed
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_DELIVERABLES_CHANGED");
    }

    // 11. deliverable reviewedBy changed -> stale
    @Test
    void deliverableReviewedByChangedStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));

        deliverable.setReviewedBy(UUID.randomUUID()); // changed
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_DELIVERABLES_CHANGED");
    }

    // 12. deliverable fileUrl changed -> stale
    @Test
    void deliverableFileUrlChangedStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));

        deliverable.setFileUrl("https://finx.local/file_modified.pdf"); // changed
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_DELIVERABLES_CHANGED");
    }

    // 13. buyer authority preserved
    @Test
    void buyerAuthorityPreserved() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.BUYER, buyerId, null);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.current()).isTrue();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_CURRENT");

        // If deal buyerId changes, buyer authority check fails
        deal.setBuyerId(UUID.randomUUID());
        ApprovalBindingEvaluation staleEval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(staleEval.current()).isFalse();
        assertThat(staleEval.reasonCode()).isEqualTo("APPROVAL_BINDING_AUTHORITY_CHANGED");
    }

    // 14. PM binding valid while same PM assigned
    @Test
    void pmBindingValidWhileSamePmAssigned() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.PROJECT_MANAGER, pmId, pmId);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.current()).isTrue();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_CURRENT");
    }

    // 15. PM reassignment -> stale
    @Test
    void pmReassignmentStale() {
        MilestoneApprovalBinding binding = createSampleBinding(MilestoneApprovalAuthority.PROJECT_MANAGER, pmId, pmId);
        when(bindingRepository.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId())).thenReturn(List.of(deliverable));
        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        deal.setProjectManagerId(UUID.randomUUID()); // reassigned

        ApprovalBindingEvaluation eval = bindingService.evaluateCurrentBinding(milestone.getId());
        assertThat(eval.present()).isTrue();
        assertThat(eval.current()).isFalse();
        assertThat(eval.reasonCode()).isEqualTo("APPROVAL_BINDING_AUTHORITY_CHANGED");
    }

    // 16. unbound buyer organization -> no authoritative binding
    @Test
    void unboundBuyerOrganizationNoAuthoritativeBinding() {
        deal.setBuyerOrganizationId(null);
        Optional<MilestoneApprovalBinding> created = bindingService.createBinding(
                deal, milestone, List.of(deliverable), buyerId, MilestoneApprovalAuthority.BUYER, now
        );
        assertThat(created).isEmpty();
        verify(bindingRepository, never()).save(any());
    }

    // 17. deterministic fingerprints
    @Test
    void deterministicFingerprints() {
        String hash1 = fingerprintService.computeMilestoneFingerprint(milestone);
        String hash2 = fingerprintService.computeMilestoneFingerprint(milestone);
        assertThat(hash1).isEqualTo(hash2);
    }

    // 18. same evidence -> same fingerprint
    @Test
    void sameEvidenceSameFingerprint() {
        Deliverable d1 = new Deliverable(milestone.getId(), deal.getSellerId(), "Doc", "Spec", "https://finx.local/file1.pdf");
        d1.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        d1.setStatus(DeliverableStatus.APPROVED);
        d1.setSubmittedAt(now.minusSeconds(100));
        d1.setReviewedAt(now);
        d1.setReviewedBy(buyerId);

        Deliverable d2 = new Deliverable(milestone.getId(), deal.getSellerId(), "Doc2", "Spec2", "https://finx.local/file2.pdf");
        d2.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        d2.setStatus(DeliverableStatus.APPROVED);
        d2.setSubmittedAt(now.minusSeconds(50));
        d2.setReviewedAt(now);
        d2.setReviewedBy(buyerId);

        // Regardless of list order, SHA-256 is identical
        String hashOrder1 = fingerprintService.computeDeliverablesFingerprint(List.of(d1, d2));
        String hashOrder2 = fingerprintService.computeDeliverablesFingerprint(List.of(d2, d1));
        assertThat(hashOrder1).isEqualTo(hashOrder2);
    }

    // 19. fingerprint 64 lowercase hex
    @Test
    void fingerprint64LowercaseHex() {
        String mHash = fingerprintService.computeMilestoneFingerprint(milestone);
        String dHash = fingerprintService.computeDeliverablesFingerprint(List.of(deliverable));
        String aHash = fingerprintService.computeApprovalFingerprint(
                buyerOrgId, deal.getId(), milestone.getId(), buyerId, MilestoneApprovalAuthority.BUYER, null, mHash, dHash, now
        );

        assertThat(mHash).matches("^[0-9a-f]{64}$");
        assertThat(dHash).matches("^[0-9a-f]{64}$");
        assertThat(aHash).matches("^[0-9a-f]{64}$");
    }
}
