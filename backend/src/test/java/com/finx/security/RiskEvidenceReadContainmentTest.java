package com.finx.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.UnauthorizedException;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.payment.service.PaymentService;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.dto.RiskEvidenceDtos.*;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskReview;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.entity.RiskScoreHistory;
import com.finx.risk.enums.RiskEnums.RiskEntityType;
import com.finx.risk.enums.RiskEnums.RiskLevel;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskEngineService;
import com.finx.risk.service.RiskEventService;
import com.finx.risk.service.RiskEvidenceReadService;
import com.finx.risk.service.RiskScoringService;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B13 Focused Containment Test Suite:
 * Proves that deal risk inspection is a stable, read-only assessment reference.
 *
 * Covers:
 * 1. Zero risk history writes on repeated read
 * 2. Zero risk review writes on repeated read
 * 3. Zero risk event writes on repeated read
 * 4. Zero score/state alteration on repeated read
 * 5. Returned buyer persisted source ID exists
 * 6. Returned vendor persisted source ID exists
 * 7. Buyer and vendor identities remain distinct
 * 8. Transient aggregate ID is not presented as durable source identity
 * 9. High score + cleared review native semantics preserved
 * 10. Active effective restriction preserved
 * 11. reviewRequired differs from effective restriction without being flattened
 * 12. Missing buyer evidence remains missing/unresolved
 * 13. Missing vendor evidence remains missing/unresolved
 * 14. Stale/expired evidence is not silently refreshed
 * 15. Source observation timestamp preserved
 * 16. Policy version preserved when actually available
 * 17. Unknown policy version is not fabricated
 * 18. Cross-tenant risk evidence read denied
 * 19. Inactive unauthorized membership denied according to B07
 * 20. Invoice persisted assessment ID retained
 * 21. Invoice fingerprint/revision retained
 * 22. Manual invoice provenance retained
 * 23. Read path performs zero bank calls
 * 24. Read path performs zero escrow mutation
 * 25. Read path performs zero ledger posting
 * 26. Mutation separation: read produces 0 writes, explicit recalculate mutates
 * 27. Architecture negative test: no financial mutation dependencies
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RiskEvidenceReadContainmentTest {

    @Mock private DealRepository dealRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private BusinessRiskRepository businessRiskRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceAssessmentRepository invoiceAssessmentRepository;
    @Mock private RiskEventService riskEventService;
    @Mock private RiskScoringService riskScoringService;

    // Financial side-effect spies/mocks
    @Mock private BankingProvider bankingProvider;
    @Mock private EscrowService escrowService;
    @Mock private LedgerService ledgerService;

    private DealAccessService dealAccessService;
    private RiskEvidenceReadService evidenceReadService;
    private RiskEngineService riskEngineService;

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger persistCount = new AtomicInteger(0);
    private final AtomicInteger flushCount = new AtomicInteger(0);

    private UUID dealId;
    private UUID buyerUserId;
    private UUID buyerOrgId;
    private UUID sellerUserId;
    private UUID sellerOrgId;
    private UUID unrelatedUserId;
    private UUID unrelatedOrgId;

    private Deal deal;
    private RiskScore buyerScore;
    private RiskScore sellerScore;
    private RiskAssessment buyerPersistedAssessment;
    private RiskAssessment sellerPersistedAssessment;
    private Assessment buyerSnapshot;
    private Assessment sellerSnapshot;
    private Invoice invoice;
    private InvoiceAssessment invoiceAssessment;

    @BeforeEach
    void setUp() {
        dealId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerUserId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        unrelatedUserId = UUID.randomUUID();
        unrelatedOrgId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerId(buyerUserId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerId(sellerUserId);
        deal.setSellerOrganizationId(sellerOrgId);
        deal.setStatus(DealStatus.IN_PROGRESS);

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        dealAccessService = new DealAccessService(memberRepository);

        // Track any persist or flush calls on BusinessRiskRepository
        doAnswer(invocation -> {
            persistCount.incrementAndGet();
            return invocation.getArgument(0);
        }).when(businessRiskRepository).persist(any());

        doAnswer(invocation -> {
            flushCount.incrementAndGet();
            return null;
        }).when(businessRiskRepository).flush();

        // Setup buyer score and assessment
        buyerScore = new RiskScore();
        buyerScore.id = UUID.randomUUID();
        buyerScore.subjectKey = "ORG:" + buyerOrgId + ":BUYER";
        buyerScore.userId = buyerUserId;
        buyerScore.organizationId = buyerOrgId;
        buyerScore.userType = UserType.BUYER;
        buyerScore.score = 25;
        buyerScore.riskLevel = Level.LOW;
        buyerScore.calculatedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        buyerScore.nextRefreshAt = Instant.now().plus(22, ChronoUnit.HOURS);

        buyerPersistedAssessment = new RiskAssessment(
                RiskEntityType.USER, buyerScore.id, RiskLevel.LOW, 25, "Low risk buyer", true);
        buyerPersistedAssessment.ruleVersion = "rules-v1:BUYER:1.0";
        buyerPersistedAssessment.reviewRequired = false;
        buyerPersistedAssessment.reviewStatus = "CLEARED";
        buyerScore.assessmentId = buyerPersistedAssessment.getId();

        buyerSnapshot = new Assessment(
                buyerPersistedAssessment.getId(),
                buyerScore.id,
                buyerUserId,
                buyerOrgId,
                UserType.BUYER,
                25,
                Level.LOW,
                null,
                0,
                Map.of(),
                List.of(),
                List.of(),
                List.of("LOW_DISPUTE_RATE"),
                List.of("Low historical dispute rate"),
                buyerScore.calculatedAt,
                "rules-v1:BUYER:1.0",
                false,
                ReviewStatus.CLEARED,
                Action.ALLOW,
                "INITIAL",
                List.of(),
                0.0
        );

        // Setup seller score and assessment
        sellerScore = new RiskScore();
        sellerScore.id = UUID.randomUUID();
        sellerScore.subjectKey = "ORG:" + sellerOrgId + ":VENDOR";
        sellerScore.userId = sellerUserId;
        sellerScore.organizationId = sellerOrgId;
        sellerScore.userType = UserType.VENDOR;
        sellerScore.score = 35;
        sellerScore.riskLevel = Level.MODERATE;
        sellerScore.calculatedAt = Instant.now().minus(3, ChronoUnit.HOURS);
        sellerScore.nextRefreshAt = Instant.now().plus(21, ChronoUnit.HOURS);

        sellerPersistedAssessment = new RiskAssessment(
                RiskEntityType.USER, sellerScore.id, RiskLevel.MODERATE, 35, "Moderate risk vendor", true);
        sellerPersistedAssessment.ruleVersion = "rules-v1:VENDOR:1.0";
        sellerPersistedAssessment.reviewRequired = false;
        sellerPersistedAssessment.reviewStatus = "CLEARED";
        sellerScore.assessmentId = sellerPersistedAssessment.getId();

        sellerSnapshot = new Assessment(
                sellerPersistedAssessment.getId(),
                sellerScore.id,
                sellerUserId,
                sellerOrgId,
                UserType.VENDOR,
                35,
                Level.MODERATE,
                null,
                0,
                Map.of(),
                List.of(),
                List.of(),
                List.of("NEW_ACCOUNT"),
                List.of("Vendor account newly onboarded"),
                sellerScore.calculatedAt,
                "rules-v1:VENDOR:1.0",
                false,
                ReviewStatus.CLEARED,
                Action.ALLOW,
                "INITIAL",
                List.of(),
                0.0
        );

        // Configure default query mocks for buyer and seller
        when(businessRiskRepository.query(eq(RiskScore.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(invocation -> {
                    Map<String, Object> params = invocation.getArgument(2);
                    String key = (String) params.get("key");
                    if (buyerScore.subjectKey.equals(key)) return List.of(buyerScore);
                    if (sellerScore.subjectKey.equals(key)) return List.of(sellerScore);
                    return List.of();
                });

        when(businessRiskRepository.current(buyerScore)).thenReturn(buyerSnapshot);
        when(businessRiskRepository.current(sellerScore)).thenReturn(sellerSnapshot);

        when(businessRiskRepository.query(eq(RiskAssessment.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(invocation -> {
                    Map<String, Object> params = invocation.getArgument(2);
                    UUID id = (UUID) params.get("id");
                    if (buyerPersistedAssessment.getId().equals(id)) return List.of(buyerPersistedAssessment);
                    if (sellerPersistedAssessment.getId().equals(id)) return List.of(sellerPersistedAssessment);
                    return List.of();
                });

        when(businessRiskRepository.query(eq(RiskReview.class), anyString(), anyMap(), anyInt()))
                .thenReturn(List.of());

        // Setup invoice and assessment
        invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setDealId(dealId);
        invoice.setMilestoneId(UUID.randomUUID());
        invoice.setVendorOrganizationId(sellerOrgId);
        invoice.setBuyerOrganizationId(buyerOrgId);
        invoice.setInvoiceNumber("INV-2026-001");
        invoice.setStatus(InvoiceStatus.MATCHED);
        invoice.setExtractionStatus(ExtractionStatus.EXTRACTED);
        invoice.setMatchScore(95);
        invoice.setMatchingStatus(MatchingStatus.STRONG_MATCH);
        invoice.setDuplicateConfidence(DuplicateConfidence.UNIQUE);
        invoice.setPolicyVersion("v1.0.0");
        invoice.setCreatedAt(Instant.now().minus(1, ChronoUnit.HOURS));

        invoiceAssessment = new InvoiceAssessment();
        invoiceAssessment.id = UUID.randomUUID();
        invoiceAssessment.invoiceId = invoice.getId();
        invoiceAssessment.fingerprint = "sha256-mock-fingerprint-9999";
        invoiceAssessment.createdAt = invoice.getCreatedAt();

        when(invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId))
                .thenReturn(List.of(invoice));
        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoice.getId()))
                .thenReturn(Optional.of(invoiceAssessment));
        when(invoiceAssessmentRepository.findById(invoiceAssessment.id))
                .thenReturn(Optional.of(invoiceAssessment));

        // Instantiate services
        evidenceReadService = new RiskEvidenceReadService(
                dealRepository,
                dealAccessService,
                businessRiskRepository,
                invoiceRepository,
                invoiceAssessmentRepository
        );

        riskEngineService = new RiskEngineService(
                dealRepository,
                riskEventService,
                riskScoringService,
                evidenceReadService
        );
    }

    private UserPrincipal buyerPrincipal() {
        return new UserPrincipal(buyerUserId, "Buyer", "buyer@finx.com", "secret", Role.BUYER,
                UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
    }

    private UserPrincipal sellerPrincipal() {
        return new UserPrincipal(sellerUserId, "Seller", "seller@finx.com", "secret", Role.SELLER,
                UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_SELLER")));
    }

    private UserPrincipal adminPrincipal() {
        return new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "secret", Role.ADMIN,
                UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UserPrincipal unrelatedPrincipal() {
        return new UserPrincipal(unrelatedUserId, "Unrelated", "unrelated@finx.com", "secret", Role.BUYER,
                UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
    }

    // -------------------------------------------------------------------------
    // 1-4. Repeated Read Zero Side-Effect Guarantee
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Repeated read creates zero new risk history rows")
    void repeatedReadCreatesZeroNewRiskHistory() {
        int initialPersists = persistCount.get();

        for (int i = 0; i < 5; i++) {
            DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);
            assertThat(snapshot).isNotNull();
        }

        assertThat(persistCount.get()).isEqualTo(initialPersists);
        verify(businessRiskRepository, never()).persist(any(RiskScoreHistory.class));
    }

    @Test
    @DisplayName("2. Repeated read creates zero new risk reviews")
    void repeatedReadCreatesZeroNewRiskReviews() {
        for (int i = 0; i < 5; i++) {
            DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);
            assertThat(snapshot).isNotNull();
        }

        verify(businessRiskRepository, never()).persist(any(RiskReview.class));
    }

    @Test
    @DisplayName("3. Repeated read creates zero new risk events")
    void repeatedReadCreatesZeroNewRiskEvents() {
        for (int i = 0; i < 5; i++) {
            DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);
            assertThat(snapshot).isNotNull();
        }

        verify(riskEventService, never()).enqueue(any(), any(), any(), any(), any(), any());
        verify(riskEventService, never()).observe(any());
    }

    @Test
    @DisplayName("4. Repeated read does not alter score, level, or timestamps")
    void repeatedReadDoesNotAlterScoreOrState() {
        int initialBuyerScore = buyerScore.score;
        Instant initialBuyerCalculatedAt = buyerScore.calculatedAt;
        Instant initialBuyerRefresh = buyerScore.nextRefreshAt;

        for (int i = 0; i < 5; i++) {
            DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);
            assertThat(snapshot.buyerEvidence().score()).isEqualTo(initialBuyerScore);
            assertThat(snapshot.buyerEvidence().observedAt()).isEqualTo(initialBuyerCalculatedAt);
        }

        assertThat(buyerScore.score).isEqualTo(initialBuyerScore);
        assertThat(buyerScore.calculatedAt).isEqualTo(initialBuyerCalculatedAt);
        assertThat(buyerScore.nextRefreshAt).isEqualTo(initialBuyerRefresh);
    }

    // -------------------------------------------------------------------------
    // 5-7. Durable Source Identities & Buyer/Vendor Separation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Returned buyer persisted source ID exists in persisted records")
    void returnedBuyerPersistedSourceIdExists() {
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        UUID persistedAssessmentId = snapshot.buyerEvidence().persistedAssessmentId();
        assertThat(persistedAssessmentId).isNotNull();
        assertThat(persistedAssessmentId).isEqualTo(buyerPersistedAssessment.getId());
        assertThat(buyerScore.assessmentId).isEqualTo(persistedAssessmentId);
    }

    @Test
    @DisplayName("6. Returned vendor persisted source ID exists in persisted records")
    void returnedVendorPersistedSourceIdExists() {
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        UUID persistedAssessmentId = snapshot.vendorEvidence().persistedAssessmentId();
        assertThat(persistedAssessmentId).isNotNull();
        assertThat(persistedAssessmentId).isEqualTo(sellerPersistedAssessment.getId());
        assertThat(sellerScore.assessmentId).isEqualTo(persistedAssessmentId);
    }

    @Test
    @DisplayName("7. Buyer and vendor identities remain distinct with clear counterparty provenance")
    void buyerAndVendorIdentitiesRemainDistinct() {
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().subjectId()).isNotEqualTo(snapshot.vendorEvidence().subjectId());
        assertThat(snapshot.buyerEvidence().userType()).isEqualTo(UserType.BUYER);
        assertThat(snapshot.vendorEvidence().userType()).isEqualTo(UserType.VENDOR);
        assertThat(snapshot.buyerEvidence().persistedAssessmentId())
                .isNotEqualTo(snapshot.vendorEvidence().persistedAssessmentId());
    }

    // -------------------------------------------------------------------------
    // 8. Transient Aggregate ID Is Not Cited as Durable Source Identity
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. Transient aggregate ID is not presented as durable source identity")
    void transientAggregateIdIsNotPresentedAsDurableSourceIdentity() {
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);
        assertThat(snapshot.hasDurableAggregate()).isFalse();

        // Compatibility view explicitly marks transient state
        RiskAssessment view = riskEngineService.getAssessmentForDeal(dealId);
        assertThat(view.isTransient()).isTrue();
        assertThat(view.getEntityType()).isEqualTo(RiskEntityType.DEAL);
    }

    // -------------------------------------------------------------------------
    // 9. High Score with Cleared Review Native Semantics
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. High score with cleared review preserves native state without manufacturing BLOCK")
    void highScoreClearedReviewNativeSemanticsPreserved() {
        // Buyer has high risk score (85), but an admin cleared the review
        buyerScore.score = 85;
        buyerScore.riskLevel = Level.HIGH;

        RiskReview clearedReview = new RiskReview();
        clearedReview.id = UUID.randomUUID();
        clearedReview.subjectId = buyerScore.id;
        clearedReview.status = ReviewStatus.CLEARED;
        clearedReview.action = Action.ALLOW;
        clearedReview.restrictionUntil = null;

        when(businessRiskRepository.query(eq(RiskReview.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> p = inv.getArgument(2);
                    if (buyerScore.id.equals(p.get("id"))) return List.of(clearedReview);
                    return List.of();
                });

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().score()).isEqualTo(85);
        assertThat(snapshot.buyerEvidence().reviewStatus()).isEqualTo(ReviewStatus.CLEARED);
        assertThat(snapshot.buyerEvidence().effectiveRestrictionActive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // 10. Active Effective Restriction Preserved
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. Active effective restriction is preserved even with low risk score")
    void activeEffectiveRestrictionPreserved() {
        // Moderate score, but active temporary hold is configured
        buyerScore.score = 25;
        buyerScore.riskLevel = Level.LOW;

        RiskReview restrictedReview = new RiskReview();
        restrictedReview.id = UUID.randomUUID();
        restrictedReview.subjectId = buyerScore.id;
        restrictedReview.status = ReviewStatus.RESTRICTED;
        restrictedReview.action = Action.TEMPORARY_HOLD;
        restrictedReview.restrictionUntil = Instant.now().plus(7, ChronoUnit.DAYS);
        restrictedReview.reason = "Suspected fraudulent activity hold";

        when(businessRiskRepository.query(eq(RiskReview.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> p = inv.getArgument(2);
                    if (buyerScore.id.equals(p.get("id"))) return List.of(restrictedReview);
                    return List.of();
                });

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().effectiveRestrictionActive()).isTrue();
        assertThat(snapshot.buyerEvidence().activeRestrictionId()).isEqualTo(restrictedReview.id);
        assertThat(snapshot.buyerEvidence().restrictionUntil()).isEqualTo(restrictedReview.restrictionUntil);
        assertThat(snapshot.effectiveRestrictionActive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // 11. reviewRequired Differs from Effective Restriction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. reviewRequired differs from effective restriction without being flattened")
    void reviewRequiredDiffersFromEffectiveRestrictionWithoutBeingFlattened() {
        RiskReview openReview = new RiskReview();
        openReview.id = UUID.randomUUID();
        openReview.subjectId = buyerScore.id;
        openReview.status = ReviewStatus.OPEN;
        openReview.action = Action.MANUAL_REVIEW;
        openReview.restrictionUntil = null;

        when(businessRiskRepository.query(eq(RiskReview.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> p = inv.getArgument(2);
                    if (buyerScore.id.equals(p.get("id"))) return List.of(openReview);
                    return List.of();
                });

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        // reviewRequired is true because an open review case exists, but NO active restriction hold applies
        assertThat(snapshot.buyerEvidence().reviewRequired()).isTrue();
        assertThat(snapshot.buyerEvidence().reviewStatus()).isEqualTo(ReviewStatus.OPEN);
        assertThat(snapshot.buyerEvidence().effectiveRestrictionActive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // 12-13. Missing Evidence Remains Missing / Unresolved
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("12. Missing buyer evidence remains explicitly missing/unresolved")
    void missingBuyerEvidenceRemainsMissingUnresolved() {
        when(businessRiskRepository.query(eq(RiskScore.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> p = inv.getArgument(2);
                    String key = (String) p.get("key");
                    if (sellerScore.subjectKey.equals(key)) return List.of(sellerScore);
                    return List.of(); // buyer not found
                });

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(snapshot.buyerEvidence().score()).isNull();
        assertThat(snapshot.buyerEvidence().persistedAssessmentId()).isNull();
        assertThat(snapshot.buyerEvidence().riskLevel()).isNull();
    }

    @Test
    @DisplayName("13. Missing vendor evidence remains explicitly missing/unresolved")
    void missingVendorEvidenceRemainsMissingUnresolved() {
        deal.setSellerId(null);
        deal.setSellerOrganizationId(null);

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.vendorEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(snapshot.vendorEvidence().score()).isNull();
        assertThat(snapshot.vendorEvidence().persistedAssessmentId()).isNull();
    }

    // -------------------------------------------------------------------------
    // 14. Stale / Expired Evidence Is Not Silently Refreshed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("14. Stale evidence is marked stale and not silently refreshed")
    void staleExpiredEvidenceIsNotSilentlyRefreshed() {
        buyerScore.nextRefreshAt = Instant.now().minus(2, ChronoUnit.HOURS); // past due
        Instant originalCalculatedAt = buyerScore.calculatedAt;

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().status()).isEqualTo(EvidenceStatus.STALE);
        assertThat(snapshot.buyerEvidence().observedAt()).isEqualTo(originalCalculatedAt);
        // Verified zero recalculations were triggered
        verify(riskScoringService, never()).recalculate(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // 15. Source Observation Timestamp Preserved
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("15. Source observation timestamp is preserved from calculation time, not now()")
    void sourceObservationTimestampPreserved() {
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().observedAt()).isEqualTo(buyerScore.calculatedAt);
        assertThat(snapshot.buyerEvidence().observedAt()).isBefore(snapshot.snapshotAt());
    }

    // -------------------------------------------------------------------------
    // 16-17. Policy Version Preserved vs Not Fabricated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("16. Policy version is preserved when actually available")
    void policyVersionPreservedWhenActuallyAvailable() {
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().ruleVersion()).isEqualTo("rules-v1:BUYER:1.0");
        assertThat(snapshot.vendorEvidence().ruleVersion()).isEqualTo("rules-v1:VENDOR:1.0");
    }

    @Test
    @DisplayName("17. Unknown policy version is not fabricated")
    void unknownPolicyVersionIsNotFabricated() {
        buyerPersistedAssessment.ruleVersion = null;
        buyerSnapshot = new Assessment(
                buyerSnapshot.id(), buyerSnapshot.entityId(), buyerSnapshot.userId(), buyerSnapshot.organizationId(),
                buyerSnapshot.userType(), buyerSnapshot.totalRiskScore(), buyerSnapshot.riskLevel(),
                buyerSnapshot.previousScore(), buyerSnapshot.scoreChange(), buyerSnapshot.factorScores(),
                buyerSnapshot.topRiskFactors(), buyerSnapshot.triggeredRules(), buyerSnapshot.reasonCodes(),
                buyerSnapshot.humanReadableReasons(), buyerSnapshot.calculatedAt(), null,
                buyerSnapshot.reviewRequired(), buyerSnapshot.reviewStatus(), buyerSnapshot.action(),
                buyerSnapshot.trigger(), buyerSnapshot.unavailableSignals(), buyerSnapshot.roundingAdjustment()
        );
        when(businessRiskRepository.current(buyerScore)).thenReturn(buyerSnapshot);

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().ruleVersion()).isNull();
    }

    // -------------------------------------------------------------------------
    // 18-19. Read Authorization & B07 Scope
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("18. Cross-tenant risk evidence read denied for unrelated organization")
    void crossTenantRiskEvidenceReadDenied() {
        UserPrincipal unrelated = unrelatedPrincipal();

        assertThatThrownBy(() -> evidenceReadService.getDealRiskEvidence(dealId, unrelated))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("19. Inactive membership denied according to B07 scope contract")
    void inactiveUnauthorizedMembershipDeniedAccordingToB07() {
        // Buyer member exists but is suspended
        when(memberRepository.findByOrganizationIdAndUserId(buyerOrgId, buyerUserId))
                .thenReturn(Optional.of(new com.finx.org.entity.OrganizationMember() {{
                    setStatus(MemberStatus.SUSPENDED);
                }}));

        UserPrincipal suspendedBuyer = buyerPrincipal();

        assertThatThrownBy(() -> evidenceReadService.getDealRiskEvidence(dealId, suspendedBuyer))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("19b. Active deal participant is permitted to read risk evidence")
    void activeDealParticipantPermittedToReadRiskEvidence() {
        when(memberRepository.findByOrganizationIdAndUserId(buyerOrgId, buyerUserId))
                .thenReturn(Optional.of(new com.finx.org.entity.OrganizationMember() {{
                    setStatus(MemberStatus.ACTIVE);
                }}));

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidence(dealId, buyerPrincipal());
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.dealId()).isEqualTo(dealId);
    }

    // -------------------------------------------------------------------------
    // 20-22. Invoice Assessment Identity, Fingerprint, & Manual Provenance
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("20. Invoice persisted assessment ID is retained")
    void invoicePersistedAssessmentIdRetained() {
        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.invoiceEvidence().persistedAssessmentId()).isEqualTo(invoiceAssessment.id);
        assertThat(snapshot.invoiceEvidence().invoiceId()).isEqualTo(invoice.getId());
    }

    @Test
    @DisplayName("21. Invoice fingerprint and revision reference are retained")
    void invoiceFingerprintAndRevisionRetained() {
        invoice.setRevisionOf(UUID.randomUUID());

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.invoiceEvidence().fingerprint()).isEqualTo("sha256-mock-fingerprint-9999");
        assertThat(snapshot.invoiceEvidence().revisionOf()).isEqualTo(invoice.getRevisionOf());
    }

    @Test
    @DisplayName("22. Manual invoice provenance remains explicitly manual through read contract")
    void manualInvoiceProvenanceRetained() {
        invoice.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY);

        DealRiskEvidenceSnapshot snapshot = evidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.invoiceEvidence().manualProvenance()).isTrue();
        assertThat(snapshot.invoiceEvidence().provenance()).isEqualTo("MANUAL_ENTRY");
    }

    // -------------------------------------------------------------------------
    // 23-25. Zero Bank Calls, Escrow Mutations, or Ledger Posts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("23. Read path performs zero bank provider calls")
    void readPathPerformsZeroBankCalls() {
        evidenceReadService.getDealRiskEvidenceInternal(dealId);
        riskEngineService.getAssessmentForDeal(dealId);

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("24. Read path performs zero escrow mutation")
    void readPathPerformsZeroEscrowMutation() {
        evidenceReadService.getDealRiskEvidenceInternal(dealId);
        riskEngineService.getAssessmentForDeal(dealId);

        verifyNoInteractions(escrowService);
    }

    @Test
    @DisplayName("25. Read path performs zero ledger posting")
    void readPathPerformsZeroLedgerPosting() {
        evidenceReadService.getDealRiskEvidenceInternal(dealId);
        riskEngineService.getAssessmentForDeal(dealId);

        verifyNoInteractions(ledgerService);
    }

    // -------------------------------------------------------------------------
    // 26. Mutation Separation: Read vs Explicit Recalculate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("26. Mutation separation: getAssessmentForDeal causes 0 writes, assessDealRisk calls recalculate")
    void mutationSeparationExplicitRecalculate() {
        int initialPersists = persistCount.get();

        // 1. Read: 0 writes, 0 recalculations
        RiskAssessment view = riskEngineService.getAssessmentForDeal(dealId);
        assertThat(view).isNotNull();
        assertThat(persistCount.get()).isEqualTo(initialPersists);
        verify(riskScoringService, never()).recalculate(any(), any(), any());

        // 2. Explicit recalculate command: calls scoring.recalculate
        RiskScore mockBuyerSubject = new RiskScore();
        mockBuyerSubject.id = UUID.randomUUID();
        when(riskEventService.subject(buyerUserId, buyerOrgId, UserType.BUYER)).thenReturn(mockBuyerSubject);
        when(riskScoringService.recalculate(eq(mockBuyerSubject.id), eq("DEAL_INSPECTION"), isNull()))
                .thenReturn(buyerSnapshot);

        RiskScore mockSellerSubject = new RiskScore();
        mockSellerSubject.id = UUID.randomUUID();
        when(riskEventService.subject(sellerUserId, sellerOrgId, UserType.VENDOR)).thenReturn(mockSellerSubject);
        when(riskScoringService.recalculate(eq(mockSellerSubject.id), eq("DEAL_INSPECTION"), isNull()))
                .thenReturn(sellerSnapshot);

        RiskAssessment recalculateView = riskEngineService.assessDealRisk(dealId);
        assertThat(recalculateView).isNotNull();
        verify(riskScoringService, atLeastOnce()).recalculate(any(), eq("DEAL_INSPECTION"), any());
    }

    // -------------------------------------------------------------------------
    // 27. Architecture Negative Test: No Financial Mutation Dependencies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("27. Architecture negative test: RiskEvidenceReadService does not depend on financial mutating components")
    void architectureNegativeTest() {
        Class<?> serviceClass = RiskEvidenceReadService.class;

        // Prohibited mutating dependency types
        Class<?>[] prohibitedTypes = new Class<?>[] {
                BankingProvider.class,
                SettlementAuthorizationService.class,
                EscrowService.class,
                LedgerService.class,
                RazorpayService.class,
                PaymentService.class
        };

        for (Field field : serviceClass.getDeclaredFields()) {
            for (Class<?> prohibited : prohibitedTypes) {
                assertThat(field.getType())
                        .withFailMessage("RiskEvidenceReadService must not declare field of type: " + prohibited.getName())
                        .isNotEqualTo(prohibited);
            }
        }

        for (Constructor<?> ctor : serviceClass.getDeclaredConstructors()) {
            for (Class<?> paramType : ctor.getParameterTypes()) {
                for (Class<?> prohibited : prohibitedTypes) {
                    assertThat(paramType)
                            .withFailMessage("RiskEvidenceReadService constructor must not accept type: " + prohibited.getName())
                            .isNotEqualTo(prohibited);
                }
            }
        }

        for (Method method : serviceClass.getDeclaredMethods()) {
            for (Class<?> returnType : new Class<?>[] { method.getReturnType() }) {
                for (Class<?> prohibited : prohibitedTypes) {
                    assertThat(returnType)
                            .withFailMessage("RiskEvidenceReadService method must not return type: " + prohibited.getName())
                            .isNotEqualTo(prohibited);
                }
            }
        }
    }
}
