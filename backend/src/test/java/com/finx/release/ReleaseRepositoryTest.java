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
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReleaseRepositoryTest {

    @Autowired private EntityManager em;
    @Autowired private ReleaseInstructionRepository instructionRepo;
    @Autowired private ReleaseReservationRepository reservationRepo;
    @Autowired private com.finx.engine.repository.EnginePolicyVersionRepository policyRepo;

    private Organization org;
    private User user;
    private Deal deal;
    private Milestone milestone;
    private EngineDecision decision;
    private EnginePolicyVersion policyVersion;
    private OrganizationBankAccount bankAccount;
    private EscrowAccount escrowAccount;

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

    @Test
    @DisplayName("Save and find ReleaseInstruction by decision ID and scoped idempotency key")
    void saveAndFindReleaseInstruction() {
        ReleaseInstruction instruction = new ReleaseInstruction(
                null, org.getId(), deal.getId(), milestone.getId(), decision.getId(),
                decision.getDecisionFingerprint(), policyVersion.getId(), policyVersion.getContentHash(),
                bankAccount.getId(), "MOCK", "ACC-MOCK-1", new BigDecimal("50000.0000"),
                "INR", "REL-IDEM-999", "e".repeat(64), user.getId()
        );

        instructionRepo.saveAndFlush(instruction);

        Optional<ReleaseInstruction> foundByDecision = instructionRepo.findByReleaseDecisionId(decision.getId());
        assertThat(foundByDecision).isPresent();
        assertThat(foundByDecision.get().getAmount()).isEqualByComparingTo("50000.0000");

        Optional<ReleaseInstruction> foundByIdem = instructionRepo.findByOrganizationIdAndIdempotencyKey(org.getId(), "REL-IDEM-999");
        assertThat(foundByIdem).isPresent();

        Optional<ReleaseInstruction> active = instructionRepo.findFirstByMilestoneIdAndStatusIn(
                milestone.getId(), List.of(ReleaseInstructionStatus.CREATED, ReleaseInstructionStatus.AUTHORIZED)
        );
        assertThat(active).isPresent();
    }

    @Test
    @DisplayName("sumActiveReservationsForEscrow calculates sum strictly for ACTIVE reservations")
    void sumActiveReservationsForEscrow() {
        ReleaseInstruction inst1 = new ReleaseInstruction(
                null, org.getId(), deal.getId(), milestone.getId(), decision.getId(),
                decision.getDecisionFingerprint(), policyVersion.getId(), policyVersion.getContentHash(),
                bankAccount.getId(), "MOCK", "ACC-1", new BigDecimal("20000.0000"),
                "INR", "IDEM-1", "f".repeat(64), user.getId()
        );
        em.persist(inst1);

        ReleaseReservation resActive1 = new ReleaseReservation(
                null, inst1.getId(), escrowAccount.getId(), milestone.getId(),
                new BigDecimal("20000.0000"), "INR"
        );
        em.persist(resActive1);

        // Second milestone with another decision
        Milestone milestone2 = new Milestone(deal.getId(), "M2", "Desc", 2, new BigDecimal("30000.00"), "INR", Instant.now().plusSeconds(86400));
        milestone2.setStatus(MilestoneStatus.APPROVED);
        em.persist(milestone2);

        EngineDecision decision2 = new EngineDecision(
                org.getId(), EngineType.RELEASE, "MILESTONE", milestone2.getId(),
                DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, null, null, "[]",
                "OK", "[]", "1.0.0", policyVersion.getId(), policyVersion.getContentHash(),
                null, UUID.randomUUID(), UUID.randomUUID(), "1".repeat(64), "1", "1.0", "1.0.0",
                "2".repeat(64), null, Instant.now()
        );
        em.persist(decision2);

        ReleaseInstruction inst2 = new ReleaseInstruction(
                null, org.getId(), deal.getId(), milestone2.getId(), decision2.getId(),
                decision2.getDecisionFingerprint(), policyVersion.getId(), policyVersion.getContentHash(),
                bankAccount.getId(), "MOCK", "ACC-2", new BigDecimal("30000.0000"),
                "INR", "IDEM-2", "3".repeat(64), user.getId()
        );
        em.persist(inst2);

        ReleaseReservation resActive2 = new ReleaseReservation(
                null, inst2.getId(), escrowAccount.getId(), milestone2.getId(),
                new BigDecimal("30000.0000"), "INR"
        );
        em.persist(resActive2);

        // Third milestone that is CONSUMED
        Milestone milestone3 = new Milestone(deal.getId(), "M3", "Desc", 3, new BigDecimal("10000.00"), "INR", Instant.now().plusSeconds(86400));
        milestone3.setStatus(MilestoneStatus.APPROVED);
        em.persist(milestone3);

        EngineDecision decision3 = new EngineDecision(
                org.getId(), EngineType.RELEASE, "MILESTONE", milestone3.getId(),
                DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, null, null, "[]",
                "OK", "[]", "1.0.0", policyVersion.getId(), policyVersion.getContentHash(),
                null, UUID.randomUUID(), UUID.randomUUID(), "4".repeat(64), "1", "1.0", "1.0.0",
                "5".repeat(64), null, Instant.now()
        );
        em.persist(decision3);

        ReleaseInstruction inst3 = new ReleaseInstruction(
                null, org.getId(), deal.getId(), milestone3.getId(), decision3.getId(),
                decision3.getDecisionFingerprint(), policyVersion.getId(), policyVersion.getContentHash(),
                bankAccount.getId(), "MOCK", "ACC-3", new BigDecimal("10000.0000"),
                "INR", "IDEM-3", "6".repeat(64), user.getId()
        );
        em.persist(inst3);

        ReleaseReservation resConsumed = new ReleaseReservation(
                null, inst3.getId(), escrowAccount.getId(), milestone3.getId(),
                new BigDecimal("10000.0000"), "INR"
        );
        resConsumed.markConsumed(Instant.now());
        em.persist(resConsumed);

        em.flush();

        BigDecimal activeSum = reservationRepo.sumActiveReservationsForEscrow(escrowAccount.getId());
        assertThat(activeSum).isEqualByComparingTo("50000.0000");
    }
}
