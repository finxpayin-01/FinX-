package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.engine.adapter.ReconciliationDecisionAdapter;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.engine.service.EngineDecisionService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationMember;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(isolation = Isolation.REPEATABLE_READ)
class ReconciliationDecisionPersistenceTest {

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReconciliationDecisionAdapter reconciliationAdapter;
    @Autowired private EngineDecisionService decisionService;
    @MockBean private BankingProvider banking;

    private User buyer;
    private User seller;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private Deal deal;
    private Milestone milestone;
    private EvaluationContext context;

    private static final List<String> UNCHANGED_TABLES = List.of(
            "settlement_instructions",
            "journal_entries",
            "journal_lines",
            "escrow_accounts",
            "escrow_ledger",
            "milestones",
            "deals",
            "invoices",
            "disputes"
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

        deal = new Deal("Recon Persistence Deal", "", buyer.getId(), seller.getId(), new BigDecimal("10000.00"), "INR", DealStatus.IN_PROGRESS);
        deal.setBuyerOrganizationId(buyerOrg.getId());
        deal.setSellerOrganizationId(sellerOrg.getId());
        em.persist(deal);

        milestone = new Milestone(deal.getId(), "Milestone 1", "Delivery of modules", 1, new BigDecimal("10000.00"), "INR", Instant.now().plusSeconds(86400));
        milestone.setStatus(MilestoneStatus.APPROVED);
        em.persist(milestone);

        context = new EvaluationContext(
                UUID.randomUUID(),
                buyerOrg.getId(),
                UUID.randomUUID(),
                DecisionSubjectType.MILESTONE.name(),
                milestone.getId(),
                Instant.now()
        );
    }

    private Map<String, Integer> countUnchangedTables() {
        return UNCHANGED_TABLES.stream().collect(Collectors.toMap(
                t -> t,
                t -> jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)
        ));
    }

    @Test
    @DisplayName("Evaluate and persist RECONCILIATION decision with clean funding evidence")
    void persistReconciliationDecision() {
        // Setup clean reconciled funding
        PaymentInstruction pi = new PaymentInstruction(
                "INST-RECON-" + UUID.randomUUID(),
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
                "TX-RECON-" + UUID.randomUUID(),
                pi.getId(),
                "BANK",
                "PROV-REF-1",
                new BigDecimal("10000.00"),
                "INR",
                "SUCCESS",
                "UTR-RECON-1",
                "{}"
        );
        pt.setCreatedAt(Instant.now().minusSeconds(200));
        em.persist(pt);

        String sourceHash = "recon-source-hash-123";
        BankStatementImport imp = new BankStatementImport(
                "BANK",
                "ACC-123",
                Instant.now().minusSeconds(1000),
                Instant.now(),
                sourceHash,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                "INR",
                buyer.getId()
        );
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        em.persist(imp);

        BankStatementLine line = new BankStatementLine(
                imp.getId(),
                "BANK",
                "ACC-123",
                "LINE-1",
                pt.getTransactionReference(),
                pt.getUtrNumber(),
                new BigDecimal("10000.00"),
                "INR",
                FinancialDirection.CREDIT,
                "SUCCESS",
                BankStatementLineStatus.FINAL_SUCCESS,
                Instant.now().minusSeconds(150),
                null,
                "line-fp-1"
        );
        em.persist(line);

        ReconciliationRun run = new ReconciliationRun("RUN-" + UUID.randomUUID(), "BANK", buyer.getId());
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(imp.getId());
        run.setEndTime(Instant.now());
        run.setMatchedCount(1);
        run.setMismatchCount(0);
        em.persist(run);

        ReconciliationItem item = new ReconciliationItem(
                run.getId(),
                pt.getTransactionReference(),
                line.getTransactionReference(),
                pt.getAmount(),
                line.getAmount(),
                ReconciliationItemStatus.MATCHED,
                "Clean match"
        );
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item.setInternalEffectId(pt.getId());
        item.setBankStatementLineId(line.getId());
        item.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        em.persist(item);

        em.flush();

        Map<String, Integer> beforeCounts = countUnchangedTables();

        // 1. Evaluate
        RecordEngineDecisionCommand cmd = reconciliationAdapter.evaluate(context);
        assertThat(cmd.engineType()).isEqualTo(EngineType.RECONCILIATION);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).containsExactly(ReconciliationDecisionAdapter.RECONCILIATION_CONFIRMED);
        assertThat(cmd.score()).isNull();
        assertThat(cmd.inputFingerprint()).isNotBlank();

        // 2. Persist
        EngineDecision persisted = decisionService.recordDecision(cmd);
        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getEngineType()).isEqualTo(EngineType.RECONCILIATION);
        assertThat(persisted.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(persisted.getOrganizationId()).isEqualTo(buyerOrg.getId());
        assertThat(persisted.getSubjectType()).isEqualTo("MILESTONE");
        assertThat(persisted.getSubjectId()).isEqualTo(milestone.getId());
        assertThat(persisted.getScore()).isNull();

        em.flush();

        // 3. Verify zero financial mutations and zero banking provider calls
        Map<String, Integer> afterCounts = countUnchangedTables();
        assertThat(afterCounts).isEqualTo(beforeCounts);
        verifyNoInteractions(banking);
    }
}
