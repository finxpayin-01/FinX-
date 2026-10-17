package com.finx.release;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class ReleaseOutboundReconciliationCandidateTest {

    @Autowired private EntityManager em;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private SettlementObservationRepository observationRepo;
    @Autowired private com.finx.engine.repository.EnginePolicyVersionRepository policyRepo;

    private Organization org;
    private User user;
    private Deal deal;
    private Milestone milestone;
    private EngineDecision decision;
    private EnginePolicyVersion policyVersion;
    private OrganizationBankAccount bankAccount;
    private EscrowAccount escrowAccount;

    private static final String DEFAULT_PROVIDER = "MOCK";
    private static final String DEFAULT_ACCOUNT = "MOCK_ACC_01";

    @BeforeEach
    void setUp() {
        user = new User("Test User", UUID.randomUUID() + "@test.invalid", "pass", Role.BUYER, UserStatus.ACTIVE);
        em.persist(user);

        org = new Organization("Test Org", "Test Org Legal", EntityType.PRIVATE_LIMITED, null, null, null, null, user.getId());
        em.persist(org);

        deal = new Deal();
        deal.setTitle("Test Deal");
        deal.setDescription("Description");
        deal.setBuyerId(user.getId());
        deal.setSellerId(user.getId());
        deal.setTotalAmount(new BigDecimal("100000.00"));
        deal.setCurrency("INR");
        deal.setStatus(DealStatus.IN_PROGRESS);
        em.persist(deal);

        milestone = new Milestone(deal.getId(), "Milestone 1", "Desc", 1, new BigDecimal("50000.00"), "INR", Instant.now().plusSeconds(86400));
        milestone.setStatus(MilestoneStatus.APPROVED);
        em.persist(milestone);

        escrowAccount = new EscrowAccount(deal.getId(), new BigDecimal("100000.00"), "INR");
        em.persist(escrowAccount);

        policyVersion = policyRepo.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, "FINX_RELEASE_DETERMINISTIC_BASE", "1.0.0"
        ).orElseGet(() -> {
            EnginePolicyVersion p = EnginePolicyVersion.platform(
                    EngineType.RELEASE, "FINX_RELEASE_DETERMINISTIC_BASE", "1.0.0", "a".repeat(64)
            );
            em.persist(p);
            return p;
        });

        decision = new EngineDecision(
                org.getId(),
                EngineType.RELEASE,
                "MILESTONE",
                milestone.getId(),
                DecisionStatus.ALLOW,
                EvaluationOutcome.SUCCESS,
                null,
                null,
                "[]",
                "All release rules passed",
                "[]",
                "1.0.0",
                policyVersion.getId(),
                policyVersion.getContentHash(),
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "b".repeat(64),
                "1",
                "1.0",
                "1.0.0",
                "c".repeat(64),
                null,
                Instant.now()
        );
        em.persist(decision);

        bankAccount = new OrganizationBankAccount(
                org.getId(),
                "Beneficiary Name",
                "XXXX1234",
                "d".repeat(64),
                "HDFC0001234",
                "HDFC Bank",
                "Branch 1",
                true
        );
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        em.persist(bankAccount);

        em.flush();
    }

    private ReleaseInstruction createRelease(ReleaseInstructionStatus status, String provider, String providerAccountId) {
        ReleaseInstruction ri = new ReleaseInstruction(
                UUID.randomUUID(),
                org.getId(),
                deal.getId(),
                milestone.getId(),
                decision.getId(),
                decision.getDecisionFingerprint(),
                policyVersion.getId(),
                policyVersion.getContentHash(),
                bankAccount.getId(),
                provider,
                providerAccountId,
                new BigDecimal("1000.00"),
                "INR",
                UUID.randomUUID().toString(),
                "e".repeat(64),
                user.getId()
        );
        ReflectionTestUtils.setField(ri, "status", status);
        ReflectionTestUtils.setField(ri, "authorizedAt", Instant.now().minus(2, ChronoUnit.HOURS));
        if (status == ReleaseInstructionStatus.SETTLED) {
            ReflectionTestUtils.setField(ri, "settledAt", Instant.now().minus(1, ChronoUnit.HOURS));
        }
        em.persist(ri);
        em.flush();
        return ri;
    }

    private SettlementObservation bindObservation(ReleaseInstruction ri, Instant observedAt, Instant providerObservedAt) {
        SettlementObservation obs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(ri.getId())
                .executionAttemptId(UUID.randomUUID())
                .provider(ri.getProvider())
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-" + UUID.randomUUID())
                .providerOperationKey("FINX-REL-" + ri.getId())
                .providerSettlementId("SETTLE-" + UUID.randomUUID())
                .providerAccountId(ri.getProviderAccountId())
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber("UTR-" + UUID.randomUUID().toString().substring(0, 8))
                .evidenceHash("e".repeat(64))
                .observedAmount(ri.getAmount())
                .observedCurrency(ri.getCurrency())
                .observedDirection("DEBIT")
                .observedAt(observedAt)
                .providerObservedAt(providerObservedAt)
                .createdAt(Instant.now())
                .build();
        em.persist(obs);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());
        em.persist(ri);
        em.flush();
        return obs;
    }

    @Test
    @DisplayName("1. SETTLED release in observation window found")
    void settledReleaseInWindowFound() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri, Instant.now(), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).contains(ri.getId());
    }

    @Test
    @DisplayName("2. Non-SETTLED release ignored")
    void nonSettledReleaseIgnored() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.PENDING_SETTLEMENT, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri, Instant.now(), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).doesNotContain(ri.getId());
    }

    @Test
    @DisplayName("3. Wrong provider ignored")
    void wrongProviderIgnored() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.SETTLED, "OTHER_PROVIDER", DEFAULT_ACCOUNT);
        bindObservation(ri, Instant.now(), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).doesNotContain(ri.getId());
    }

    @Test
    @DisplayName("4. Wrong providerAccount ignored")
    void wrongProviderAccountIgnored() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, "ACC_WRONG");
        bindObservation(ri, Instant.now(), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).doesNotContain(ri.getId());
    }

    @Test
    @DisplayName("5. Observation before window ignored")
    void observationBeforeWindowIgnored() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri, windowStart.minus(10, ChronoUnit.MINUTES), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).doesNotContain(ri.getId());
    }

    @Test
    @DisplayName("6. Observation after window ignored")
    void observationAfterWindowIgnored() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri, windowEnd.plus(10, ChronoUnit.MINUTES), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).doesNotContain(ri.getId());
    }

    @Test
    @DisplayName("7. providerObservedAt preferred when present")
    void providerObservedAtPreferredWhenPresent() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        // observedAt is outside window (2 hours ago), but providerObservedAt is INSIDE window (now)
        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri, windowStart.minus(2, ChronoUnit.HOURS), Instant.now());

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).contains(ri.getId());
    }

    @Test
    @DisplayName("8. observedAt fallback used when providerObservedAt is null")
    void observedAtFallbackUsed() {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri, Instant.now(), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId).contains(ri.getId());
    }

    @Test
    @DisplayName("9. Bounded query only")
    void boundedQueryOnly() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(30, ChronoUnit.MINUTES);
        Instant windowEnd = now.plus(30, ChronoUnit.MINUTES);

        ReleaseInstruction riIn = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(riIn, now, null);

        ReleaseInstruction riOut1 = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(riOut1, now.minus(2, ChronoUnit.HOURS), null);

        ReleaseInstruction riOut2 = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(riOut2, now.plus(2, ChronoUnit.HOURS), null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        assertThat(candidates).extracting(ReleaseInstruction::getId)
                .contains(riIn.getId())
                .doesNotContain(riOut1.getId(), riOut2.getId());
    }

    @Test
    @DisplayName("10. Deterministic ordering by observation time then id")
    void deterministicOrdering() {
        Instant t1 = Instant.now().minus(20, ChronoUnit.MINUTES);
        Instant t2 = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(1, ChronoUnit.HOURS);

        ReleaseInstruction ri2 = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri2, t2, null);

        ReleaseInstruction ri1 = createRelease(ReleaseInstructionStatus.SETTLED, DEFAULT_PROVIDER, DEFAULT_ACCOUNT);
        bindObservation(ri1, t1, null);

        List<ReleaseInstruction> candidates = instructionRepo.findOutboundReconciliationCandidates(
                DEFAULT_PROVIDER, DEFAULT_ACCOUNT, windowStart, windowEnd);

        int idx1 = -1;
        int idx2 = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).getId().equals(ri1.getId())) idx1 = i;
            if (candidates.get(i).getId().equals(ri2.getId())) idx2 = i;
        }

        assertThat(idx1).isGreaterThanOrEqualTo(0);
        assertThat(idx2).isGreaterThanOrEqualTo(0);
        assertThat(idx1).isLessThan(idx2);
    }
}
