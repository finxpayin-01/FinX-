package com.finx.risk;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.*;
import com.finx.org.entity.Organization;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.risk.config.RiskPolicies;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.*;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(RazorpayTestConfiguration.class)
@Transactional
class RiskIntegrationTest {
    @Autowired EntityManager em;
    @Autowired RiskScoringService scoring;
    @Autowired RiskEventService events;
    @Autowired RiskEventProcessor processor;
    @Autowired RiskAdminService admin;
    @Autowired RiskPolicyService policies;
    @Autowired RiskReviewService reviews;
    @Autowired RiskEvidenceService evidence;
    @Autowired BusinessRiskRepository db;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void eventProcessingIsIdempotentAndHistoryIsAppendOnly() {
        User buyer=person(Role.BUYER);
        audit.logEvent(buyer.getId(),"USER_REGISTERED","USER",buyer.getId().toString(),"Test registration");
        var subject=db.query(RiskScore.class,"from RiskScore where userId=:id",Map.of("id",buyer.getId()),1).getFirst();
        var event=db.query(RiskEvent.class,"from RiskEvent where subjectId=:id",Map.of("id",subject.id),1).getFirst();
        processor.process(event.id); processor.process(event.id);
        assertEquals(1,admin.history(subject.id,0,10).total());
        Assessment before=db.current(subject);
        scoring.recalculate(subject.id,"TEST_REFRESH",null);
        assertEquals(2,admin.history(subject.id,0,10).total());
        assertEquals(before,admin.history(subject.id,0,10).items().get(1));
        assertEquals(10,subject.score);
        assertEquals(0,db.current(subject).scoreChange());
    }

    @Test void bankChangesAreScopedToAnOrganizationAndExpire() {
        User vendor=person(Role.SELLER);
        Organization a=organization(vendor), b=organization(vendor);
        RiskScore first=events.subject(vendor.getId(),a.getId(),UserType.VENDOR);
        RiskScore second=events.subject(vendor.getId(),b.getId(),UserType.VENDOR);
        events.enqueue(first,EventType.BANK_ACCOUNT_CHANGED,"bank-change",null,null,Instant.now());
        em.flush();
        var recent=evidence.collect(first,policies.policy(UserType.VENDOR),Instant.now());
        var unrelated=evidence.collect(second,policies.policy(UserType.VENDOR),Instant.now());
        assertEquals(1,recent.metrics().get("bankChanges").value());
        assertEquals(0,unrelated.metrics().get("bankChanges").value());
        var future=evidence.collect(first,policies.policy(UserType.VENDOR),Instant.now().plusSeconds(86401));
        assertEquals(0,future.metrics().get("bankChanges").value());
    }

    @Test void manualReviewAndPolicyChangesAreAuditedWithOptimisticConcurrency() {
        User buyer=person(Role.BUYER), reviewer=person(Role.ADMIN);
        RiskScore subject=events.subject(buyer.getId(),null,UserType.BUYER);
        var original=RiskPolicies.defaults(UserType.BUYER);
        Map<Category,Integer> weights=new EnumMap<>(original.weights()); weights.replaceAll((k,v)->k==Category.VERIFICATION?100:0);
        var rules=original.rules().stream().map(r->r.code().equals("KYC_INCOMPLETE")||r.code().equals("KYB_INCOMPLETE")
                ?new Rule(r.code(),r.category(),r.metric(),r.threshold(),50,true,r.reason()):r).toList();
        Policy changed=new Policy(weights,rules,90,24,5,BigDecimal.valueOf(100000),true,true,"INR");
        RiskRule rule=policies.rule(UserType.BUYER); long version=rule.version;
        policies.update(rule.id,new PolicyUpdate(version,changed,"Test policy calibration"),reviewer.getId());
        Assessment assessment=scoring.recalculate(subject.id,"TEST",reviewer.getId());
        assertEquals(100,assessment.totalRiskScore()); assertTrue(assessment.reviewRequired());
        RiskReview review=db.query(RiskReview.class,"from RiskReview where subjectId=:id",Map.of("id",subject.id),1).getFirst();
        reviews.decide(review.id,new Decision(ReviewStatus.IN_REVIEW,"Inspect evidence","Internal note",List.of("assessment:"+assessment.id()),Action.VERIFY,null,review.version),reviewer.getId());
        reviews.decide(review.id,new Decision(ReviewStatus.RESTRICTED,"Temporary bank verification review","",List.of(),Action.TEMPORARY_HOLD,Instant.now().plusSeconds(3600),review.version),reviewer.getId());
        reviews.decide(review.id,new Decision(ReviewStatus.CLEARED,"Evidence validated by reviewer","",List.of(),Action.MONITOR,null,review.version),reviewer.getId());
        assertEquals(ReviewStatus.CLEARED,review.status);
        assertNotNull(db.get(RiskAlert.class,review.alertId).clearedAt);
        assertEquals(ReviewStatus.OPEN,db.current(subject).reviewStatus());
        em.flush();
        assertEquals(3,jdbc.queryForObject("select count(*) from audit_logs where action='RISK_REVIEW_DECISION' and entity_id=?",Integer.class,review.id.toString()));
        assertThrows(RuntimeException.class,()->policies.update(rule.id,new PolicyUpdate(version,changed,"Stale edit"),reviewer.getId()));
    }

    @Test void administrativeRoutesRequireAdministratorRole() throws Exception {
        User buyer=person(Role.BUYER), administrator=person(Role.ADMIN);
        RiskScore subject=events.subject(buyer.getId(),null,UserType.BUYER);
        scoring.recalculate(subject.id,"TEST",null);
        mvc.perform(get("/api/admin/risk/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/risk/dashboard").with(user(UserPrincipal.create(buyer)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/risk/dashboard").with(user(UserPrincipal.create(administrator)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.levelCounts.LOW").isNumber());
        mvc.perform(get("/api/v1/admin/risk/"+subject.id).with(user(UserPrincipal.create(administrator)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.assessment.userId").value(buyer.getId().toString()));
        mvc.perform(get("/api/admin/risk/buyers?minScore=0&maxScore=20&openAlerts=false").with(user(UserPrincipal.create(administrator)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(post("/api/admin/risk/"+subject.id+"/recalculate").with(user(UserPrincipal.create(buyer)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/risk/buyers?size=1000").with(user(UserPrincipal.create(administrator)))).andExpect(status().isBadRequest());
    }

    @Test void structuredInvoiceSignalsAreIdempotentAndExplainable() {
        User vendor=person(Role.SELLER);
        RiskScore subject=events.subject(vendor.getId(),null,UserType.VENDOR);
        var signal=new com.finx.risk.event.RiskSignalObserved(subject.id,EventType.INVOICE_UPLOADED,"invoice:1","invoice:1",
                Instant.now(),Map.of(com.finx.risk.event.RiskSignalObserved.Metric.DUPLICATE_INVOICES,1d));
        events.observe(signal); events.observe(signal); em.flush();
        Assessment result=scoring.recalculate(subject.id,"INVOICE_UPLOADED",null);
        assertTrue(result.reasonCodes().contains("DUPLICATE_INVOICE"));
        assertEquals(1,db.count("select count(e) from RiskEvent e where e.subjectId=:id and e.safeEvidence is not null",Map.of("id",subject.id)));
        assertFalse(result.unavailableSignals().contains("duplicateInvoices"));
    }
    private User person(Role role) {
        User user=new User("Risk test",UUID.randomUUID()+"@risk.test","fixture",role,UserStatus.ACTIVE); em.persist(user); em.flush(); return user;
    }
    private Organization organization(User user) {
        Organization org=new Organization("Risk business","Risk business Ltd",EntityType.PRIVATE_LIMITED,"REG-"+UUID.randomUUID(),"TESTPAN",null,"Test address",user.getId());
        em.persist(org); em.flush(); return org;
    }
}
