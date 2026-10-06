package com.finx.engine;

import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.engine.adapter.DisputeDecisionAdapter;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.security.service.UserPrincipal;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeDecisionAdapterTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DealAccessService dealAccessService;

    private DisputeDecisionAdapter adapter;

    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID buyerUserId;
    private UUID sellerUserId;
    private Deal deal;
    private Milestone milestone;
    private EvaluationContext dealContext;
    private EvaluationContext milestoneContext;

    @BeforeEach
    void setUp() {
        adapter = new DisputeDecisionAdapter(
                disputeRepository,
                dealRepository,
                milestoneRepository,
                dealAccessService
        );

        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        sellerUserId = UUID.randomUUID();

        deal = new Deal("Test Deal", "Description", buyerUserId, sellerUserId, new BigDecimal("1000"), "INR", DealStatus.IN_PROGRESS);
        deal.setId(dealId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerOrganizationId(sellerOrgId);

        milestone = new Milestone(dealId, "Milestone 1", "Desc", 1, new BigDecimal("500"), "INR", Instant.now().plusSeconds(3600));
        milestone.setId(milestoneId);

        dealContext = new EvaluationContext(
                UUID.randomUUID(),
                buyerOrgId,
                UUID.randomUUID(),
                DecisionSubjectType.DEAL.name(),
                dealId,
                Instant.now()
        );

        milestoneContext = new EvaluationContext(
                UUID.randomUUID(),
                buyerOrgId,
                UUID.randomUUID(),
                DecisionSubjectType.MILESTONE.name(),
                milestoneId,
                Instant.now()
        );
    }

    private Dispute createDispute(UUID milestoneTarget, DisputeStatus status, String reason) {
        Dispute d = new Dispute(dealId, milestoneTarget, buyerUserId, reason);
        d.setId(UUID.randomUUID());
        d.setStatus(status);
        d.setCreatedAt(Instant.now().minusSeconds(100));
        return d;
    }

    @Test
    @DisplayName("1. Engine type is DISPUTE")
    void engineTypeIsDispute() {
        assertThat(adapter.type()).isEqualTo(EngineType.DISPUTE);
    }

    @Test
    @DisplayName("2. Buyer organization owner preserved")
    void buyerOrgOwnerPreserved() {
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of());

        RecordEngineDecisionCommand command = adapter.evaluateForDeal(dealId, dealContext);
        assertThat(command.organizationId()).isEqualTo(buyerOrgId);
    }

    @Test
    @DisplayName("3. Deal subject works")
    void dealSubjectWorks() {
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of());

        RecordEngineDecisionCommand command = adapter.evaluate(dealContext);
        assertThat(command.subjectType()).isEqualTo("DEAL");
        assertThat(command.subjectId()).isEqualTo(dealId);
        assertThat(command.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.reasonCodes()).containsExactly("DISPUTE_NONE_ACTIVE");
    }

    @Test
    @DisplayName("4. Milestone subject works")
    void milestoneSubjectWorks() {
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of());

        RecordEngineDecisionCommand command = adapter.evaluate(milestoneContext);
        assertThat(command.subjectType()).isEqualTo("MILESTONE");
        assertThat(command.subjectId()).isEqualTo(milestoneId);
        assertThat(command.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.reasonCodes()).containsExactly("DISPUTE_NONE_ACTIVE");
    }

    @Test
    @DisplayName("5. Open deal dispute -> BLOCK with DISPUTE_ACTIVE_ON_DEAL")
    void openDealDisputeBlocks() {
        Dispute dealDispute = createDispute(null, DisputeStatus.OPEN, "Deal issue");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(dealDispute));

        RecordEngineDecisionCommand command = adapter.evaluateForDeal(dealId, dealContext);
        assertThat(command.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(command.reasonCodes()).contains("DISPUTE_ACTIVE_ON_DEAL");
    }

    @Test
    @DisplayName("6. Open milestone dispute -> BLOCK with DISPUTE_ACTIVE_ON_MILESTONE")
    void openMilestoneDisputeBlocks() {
        Dispute mDispute = createDispute(milestoneId, DisputeStatus.OPEN, "Milestone issue");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(mDispute));

        RecordEngineDecisionCommand command = adapter.evaluateForMilestone(dealId, milestoneId, milestoneContext);
        assertThat(command.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(command.reasonCodes()).contains("DISPUTE_ACTIVE_ON_MILESTONE");
    }

    @Test
    @DisplayName("7. Both deal and milestone disputes open -> BLOCK with both reason codes")
    void bothDisputesOpenBlocksWithBothReasons() {
        Dispute dealDispute = createDispute(null, DisputeStatus.OPEN, "Deal issue");
        Dispute mDispute = createDispute(milestoneId, DisputeStatus.OPEN, "Milestone issue");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(dealDispute, mDispute));

        RecordEngineDecisionCommand command = adapter.evaluateForMilestone(dealId, milestoneId, milestoneContext);
        assertThat(command.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(command.reasonCodes()).contains("DISPUTE_ACTIVE_ON_DEAL", "DISPUTE_ACTIVE_ON_MILESTONE");
    }

    @Test
    @DisplayName("8. Resolved dispute -> REVIEW with DISPUTE_RECENTLY_RESOLVED")
    void resolvedDisputeRequiresReview() {
        Dispute resolved = createDispute(milestoneId, DisputeStatus.RESOLVED, "Fixed issue");
        resolved.setResolvedAt(Instant.now().minusSeconds(3600));

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(resolved));

        RecordEngineDecisionCommand command = adapter.evaluateForMilestone(dealId, milestoneId, milestoneContext);
        assertThat(command.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(command.reasonCodes()).contains("DISPUTE_RECENTLY_RESOLVED");
    }

    @Test
    @DisplayName("9. Resolved dispute does NOT automatically produce ALLOW")
    void resolvedDisputeDoesNotAllow() {
        Dispute resolved = createDispute(null, DisputeStatus.RESOLVED, "Resolved deal issue");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(resolved));

        RecordEngineDecisionCommand command = adapter.evaluateForDeal(dealId, dealContext);
        assertThat(command.decision()).isNotEqualTo(DecisionStatus.ALLOW);
    }

    @Test
    @DisplayName("10. Rejected dispute is neutral/informational and allows if no active disputes")
    void rejectedDisputeAllowsWhenNoActive() {
        Dispute rejected = createDispute(milestoneId, DisputeStatus.REJECTED, "Invalid dispute");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(rejected));

        RecordEngineDecisionCommand command = adapter.evaluateForMilestone(dealId, milestoneId, milestoneContext);
        assertThat(command.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.reasonCodes()).containsExactly("DISPUTE_NONE_ACTIVE");
    }

    @Test
    @DisplayName("11. Cross-tenant access is blocked with UnauthorizedException")
    void crossTenantAccessBlocked() {
        UUID unrelatedOrgId = UUID.randomUUID();
        EvaluationContext crossTenantContext = new EvaluationContext(
                UUID.randomUUID(),
                unrelatedOrgId,
                UUID.randomUUID(),
                DecisionSubjectType.DEAL.name(),
                dealId,
                Instant.now()
        );

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> adapter.evaluateForDeal(dealId, crossTenantContext))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("12. Caller evaluation uses DealAccessService")
    void callerEvaluationUsesDealAccessService() {
        UserPrincipal caller = mock(UserPrincipal.class);
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of());

        RecordEngineDecisionCommand command = adapter.evaluateForDeal(dealId, dealContext, caller);
        verify(dealAccessService).requireDealAccess(deal, caller);
        assertThat(command.decision()).isEqualTo(DecisionStatus.ALLOW);
    }

    @Test
    @DisplayName("13. Preserves dispute IDs as durable evidence references")
    void preservesDisputeEvidenceReferences() {
        Dispute dispute = createDispute(milestoneId, DisputeStatus.OPEN, "Test dispute");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(dispute));

        RecordEngineDecisionCommand command = adapter.evaluateForMilestone(dealId, milestoneId, milestoneContext);
        assertThat(command.evidenceReferences()).extracting("sourceType")
                .contains("DEAL", "MILESTONE", "DISPUTE");
    }

    @Test
    @DisplayName("14. Deterministic input fingerprint generated")
    void deterministicFingerprint() {
        Dispute dispute = createDispute(null, DisputeStatus.OPEN, "Test dispute");

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(dispute));

        RecordEngineDecisionCommand c1 = adapter.evaluateForDeal(dealId, dealContext);
        RecordEngineDecisionCommand c2 = adapter.evaluateForDeal(dealId, dealContext);
        assertThat(c1.inputFingerprint()).isNotBlank();
        assertThat(c1.inputFingerprint()).isEqualTo(c2.inputFingerprint());
    }
}
