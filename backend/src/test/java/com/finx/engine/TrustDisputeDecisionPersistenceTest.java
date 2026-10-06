package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.*;
import com.finx.deal.entity.*;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.engine.adapter.DisputeDecisionAdapter;
import com.finx.engine.adapter.TrustDecisionAdapter;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.*;
import com.finx.engine.service.EngineDecisionService;
import com.finx.org.entity.*;
import com.finx.org.enums.OrgEnums.*;
import com.finx.user.entity.User;
import com.finx.verification.entity.*;
import com.finx.verification.enums.*;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(isolation = Isolation.REPEATABLE_READ)
class TrustDisputeDecisionPersistenceTest {

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrustDecisionAdapter trustAdapter;
    @Autowired private DisputeDecisionAdapter disputeAdapter;
    @Autowired private EngineDecisionService decisionService;
    @MockBean private BankingProvider banking;

    private User buyer;
    private User seller;
    private Organization buyerOrg;
    private Organization sellerOrg;
    private Deal deal;
    private Dispute dispute;
    private EvaluationContext trustContext;
    private EvaluationContext disputeContext;

    private static final List<String> UNCHANGED_TABLES = List.of(
            "settlement_instructions",
            "payment_instructions",
            "payment_transactions",
            "journal_entries",
            "journal_lines",
            "escrow_accounts",
            "escrow_ledger",
            "milestones",
            "deliverables",
            "invoices",
            "invoice_reviews",
            "invoice_assessments",
            "deal_agreements",
            "risk_reviews",
            "risk_scores",
            "disputes",
            "organizations",
            "kyb_profiles",
            "kyc_profiles",
            "authorized_representatives",
            "organization_members",
            "organization_bank_accounts"
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

        OrganizationMember sellerMember = new OrganizationMember(sellerOrg.getId(), seller.getId(), OrgRole.OWNER);
        sellerMember.setStatus(MemberStatus.ACTIVE);
        em.persist(sellerMember);

        // KYB for sellerOrg
        KybProfile kyb = new KybProfile(sellerOrg.getId(), "ABCDE1234F", "27ABCDE1234F1Z5", "U12345MH2020PTC123456", null);
        kyb.setStatus(VerificationStatus.VERIFIED);
        kyb.setPanStatus(VerificationStatus.VERIFIED);
        kyb.setGstStatus(VerificationStatus.VERIFIED);
        kyb.setRegistrationStatus(VerificationStatus.VERIFIED);
        kyb.setVerifiedAt(Instant.now());
        em.persist(kyb);

        // Signatory for sellerOrg
        AuthorizedRepresentative rep = new AuthorizedRepresentative(sellerOrg.getId(), seller.getId(), AuthorityRelationshipType.DIRECTOR);
        rep.setAuthorityStatus(AuthorityStatus.VERIFIED);
        rep.setVerifiedAt(Instant.now());
        em.persist(rep);

        // KYC for seller user
        KycProfile kyc = new KycProfile(seller.getId(), "Seller Full Name", "ABCDE1234F");
        kyc.setStatus(VerificationStatus.VERIFIED);
        kyc.setPanStatus(VerificationStatus.VERIFIED);
        kyc.setIdentityStatus(VerificationStatus.VERIFIED);
        kyc.setVerifiedAt(Instant.now());
        em.persist(kyc);

        // Bank account for sellerOrg
        OrganizationBankAccount bank = new OrganizationBankAccount(sellerOrg.getId(), "Seller Legal", "****1234", "hash-val", "HDFC0000001", "HDFC Bank", "Main", true);
        bank.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bank.setEncryptedAccountNumber("enc_acc_ciphertext");
        bank.setVerifiedAt(Instant.now());
        bank.setNameMatchStatus("MATCH");
        bank.setProvider("MOCK");
        em.persist(bank);

        deal = new Deal("Trust Dispute Deal", "Testing", buyer.getId(), seller.getId(), new BigDecimal("1000"), "INR", DealStatus.IN_PROGRESS);
        deal.setBuyerOrganizationId(buyerOrg.getId());
        deal.setSellerOrganizationId(sellerOrg.getId());
        em.persist(deal);

        dispute = new Dispute(deal.getId(), null, buyer.getId(), "Test dispute description");
        dispute.setStatus(DisputeStatus.OPEN);
        em.persist(dispute);

        em.flush();

        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();

        trustContext = new EvaluationContext(
                evalId,
                sellerOrg.getId(),
                corrId,
                DecisionSubjectType.ORGANIZATION.name(),
                sellerOrg.getId(),
                Instant.now()
        );

        disputeContext = new EvaluationContext(
                evalId,
                buyerOrg.getId(),
                corrId,
                DecisionSubjectType.DEAL.name(),
                deal.getId(),
                Instant.now()
        );

        clearInvocations(banking);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : UNCHANGED_TABLES) {
            result.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        }
        return result;
    }

    @Test
    @DisplayName("Persists TRUST and DISPUTE decisions idempotently with zero side effects")
    void persistsTrustAndDisputeDecisions() {
        var before = snapshot();
        long releaseCountBefore = jdbc.queryForObject("select count(*) from engine_decisions where engine_type = 'RELEASE'", Long.class);

        RecordEngineDecisionCommand trustCmd = trustAdapter.evaluate(trustContext);
        RecordEngineDecisionCommand disputeCmd = disputeAdapter.evaluate(disputeContext);

        assertThat(trustCmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(disputeCmd.decision()).isEqualTo(DecisionStatus.BLOCK);

        EngineDecision savedTrust = decisionService.recordDecision(trustCmd);
        EngineDecision savedDispute = decisionService.recordDecision(disputeCmd);
        em.flush();

        assertThat(savedTrust.getId()).isNotNull();
        assertThat(savedTrust.getEngineType()).isEqualTo(EngineType.TRUST);
        assertThat(savedTrust.getOrganizationId()).isEqualTo(sellerOrg.getId());
        assertThat(savedTrust.getSubjectType()).isEqualTo("ORGANIZATION");
        assertThat(savedTrust.getScore()).isNull();

        assertThat(savedDispute.getId()).isNotNull();
        assertThat(savedDispute.getEngineType()).isEqualTo(EngineType.DISPUTE);
        assertThat(savedDispute.getOrganizationId()).isEqualTo(buyerOrg.getId());
        assertThat(savedDispute.getSubjectType()).isEqualTo("DEAL");

        // Idempotent replay test
        EngineDecision replayedTrust = decisionService.recordDecision(trustAdapter.evaluate(trustContext));
        EngineDecision replayedDispute = decisionService.recordDecision(disputeAdapter.evaluate(disputeContext));
        em.flush();

        assertThat(replayedTrust.getId()).isEqualTo(savedTrust.getId());
        assertThat(replayedDispute.getId()).isEqualTo(savedDispute.getId());

        // Zero side effects verification
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from engine_decisions where engine_type = 'RELEASE'", Long.class))
                .isEqualTo(releaseCountBefore);
        verifyNoInteractions(banking);
    }
}
