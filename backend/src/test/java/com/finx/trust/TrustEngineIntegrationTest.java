package com.finx.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.audit.service.AuditService;
import com.finx.common.enums.*;
import com.finx.deal.entity.*;
import com.finx.deal.repository.DealRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.trust.event.*;
import com.finx.trust.model.*;
import com.finx.trust.repository.*;
import com.finx.trust.service.*;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"trust.worker.enabled=false", "spring.datasource.url=jdbc:h2:mem:trust_integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrustEngineIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DealRepository deals;
    @Autowired TrustOrganizationService organizations;
    @Autowired TrustEventIngestionService ingestion;
    @Autowired TrustScoreService scores;
    @Autowired TrustEventRepository events;
    @Autowired TrustScoreHistoryRepository histories;
    @Autowired TrustConfigService configs;
    @Autowired TrustScoringConfigRepository configRepository;
    @Autowired AuditService audit;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired TrustNetworkAnalyzer network;
    @Autowired TrustMembershipRepository memberships;

    User account(Role role) {
        var user=new User("Trust fixture "+UUID.randomUUID(),UUID.randomUUID()+"@trust.test","test-fixture-unused",role,UserStatus.ACTIVE);
        return users.saveAndFlush(user);
    }
    @Test void tenantIsolationAdminRbacPersistedScoresAndManualAudit() throws Exception {
        var owner=account(Role.BUYER); var outsider=account(Role.BUYER); var admin=account(Role.ADMIN);
        UUID id=organizations.ensureForUser(owner.getId());
        mvc.perform(get("/api/trust/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trust/"+id).with(user(UserPrincipal.create(outsider)))).andExpect(status().isForbidden());
        for(String suffix:List.of("","/breakdown","/history","/signals","/buyer-score","/vendor-score"))
            mvc.perform(get("/api/trust/"+id+suffix).with(user(UserPrincipal.create(owner)))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/trust/"+id+"/recalculate").with(user(UserPrincipal.create(owner)))
                .contentType("application/json").content("{\"reason\":\"Unauthorized recalculation\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/trust/"+id+"/events").with(user(UserPrincipal.create(owner)))
                .contentType("application/json").content("{}")).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/admin/trust/"+id+"/recalculate").with(user(UserPrincipal.create(admin)))
                .contentType("application/json").content("{\"reason\":\"Requested administrative review\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.overallScore").value(50))
            .andExpect(jsonPath("$.data.trustLevel").value("INSUFFICIENT_DATA"));
        mvc.perform(post("/api/admin/trust/"+id+"/recalculate").with(user(UserPrincipal.create(admin)))
                .contentType("application/json").content("{\"reason\":\"Repeated administrative review\"}"))
            .andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/trust/"+id+"/history?days=90").with(user(UserPrincipal.create(owner))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reason").value("Requested administrative review"));
        var history=histories.findByOrganizationIdAndRoleAndCalculatedAtGreaterThanEqualOrderByCalculatedAtAsc(id,TrustRole.OVERALL,Instant.EPOCH,org.springframework.data.domain.PageRequest.of(0,10));
        assertThat(history).hasSize(1); assertThat(history.get(0).actorId).isEqualTo(admin.getId());
    }
    @Test void durableSourceEventsDeduplicateAndRollbackWithDomainTransaction() {
        var buyer=account(Role.BUYER); var seller=account(Role.SELLER);
        var deal=deals.saveAndFlush(new Deal("Trust real source fixture","Test",buyer.getId(),seller.getId(),new BigDecimal("5000"),"INR",DealStatus.COMPLETED));
        audit.logEvent(buyer.getId(),"DEAL_COMPLETED","DEAL",deal.getId().toString(),"Actual persisted completion fixture");
        UUID org=organizations.ensureForUser(buyer.getId());
        long before=events.count();
        audit.logEvent(buyer.getId(),"DEAL_COMPLETED","DEAL",deal.getId().toString(),"Repeated delivery of same completion");
        assertThat(events.count()).isEqualTo(before);
        var template=new TransactionTemplate(transactions);
        assertThatThrownBy(() -> template.executeWithoutResult(tx -> {
            audit.logEvent(buyer.getId(),"DEAL_CANCELLED","DEAL",deal.getId().toString(),"Must roll back");
            throw new IllegalStateException("simulated domain rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events.count()).isEqualTo(before);
        scores.recalculate(org,null,"worker",false);
        assertThat(scores.get(org,TrustRole.BUYER).statistics().get("completedDeals")).isEqualTo(1);
        assertThat(events.findFirstByOrganizationIdAndProcessedFalseOrderByCreatedAtDesc(org)).isEmpty();
    }
    @Test void ingestionRejectsUnverifiedFaultAndMetadataAndAcceptsVerifiedFraud() {
        var buyer=account(Role.BUYER); UUID id=organizations.ensureForUser(buyer.getId());
        var bad=new TrustEventInput(id,buyer.getId(),null,null,null,TrustRole.OVERALL,TrustEventType.FRAUD_CONFIRMED,
                null,"INR",5,Instant.now().minusSeconds(1),Map.of(),"FINX_AUDIT","unverified");
        assertThatThrownBy(()->ingestion.record(bad)).isInstanceOf(IllegalArgumentException.class);
        var verified=new TrustEventInput(id,buyer.getId(),null,null,null,TrustRole.OVERALL,TrustEventType.FRAUD_CONFIRMED,
                null,"INR",5,Instant.now().minusSeconds(1),Map.of("evidenceReference","investigation:test-case"),"VERIFIED_ADJUDICATION","verified");
        ingestion.record(verified); ingestion.record(verified);
        scores.recalculate(id,null,"worker",false);
        assertThat(scores.get(id,TrustRole.OVERALL).trustLevel()).isEqualTo(TrustLevel.HIGH_RISK);
        assertThat(scores.get(id,TrustRole.OVERALL).overallScore()).isLessThanOrEqualTo(20);
    }
    @Test void concurrentEventDeliveryCannotCreateDuplicateFacts() throws Exception {
        var buyer=account(Role.BUYER); UUID id=organizations.ensureForUser(buyer.getId());
        var event=new TrustEventInput(id,buyer.getId(),null,null,null,TrustRole.OVERALL,TrustEventType.KYB_VERIFIED,
                null,"INR",1,Instant.now().minusSeconds(1),Map.of(),"COMPLIANCE_ENGINE","concurrent-kyb");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->ingestion.record(event)); var second=pool.submit(()->ingestion.record(event));
            first.get(10,java.util.concurrent.TimeUnit.SECONDS); second.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(events.findByOrganizationIdOrderByOccurredAtDesc(id,org.springframework.data.domain.PageRequest.of(0,10))).hasSize(1);
    }
    @Test void closedTransactionLoopsGenerateOnlySupportingNetworkSignals() {
        var buyers=List.of(account(Role.BUYER),account(Role.BUYER),account(Role.BUYER));
        var sellers=List.of(account(Role.SELLER),account(Role.SELLER),account(Role.SELLER));
        var orgs=buyers.stream().map(b->organizations.ensureForUser(b.getId())).toList();
        for(int i=0;i<3;i++) {
            var membership=new com.finx.trust.entity.TrustMembership(); membership.userId=sellers.get(i).getId(); membership.organizationId=orgs.get(i); memberships.saveAndFlush(membership);
            deals.saveAndFlush(new Deal("Closed loop fixture","Test",buyers.get(i).getId(),sellers.get((i+1)%3).getId(),new BigDecimal("2500"),"INR",DealStatus.COMPLETED));
        }
        network.inspect(orgs.get(0)); network.inspect(orgs.get(0));
        var facts=events.findByOrganizationIdOrderByOccurredAtDesc(orgs.get(0),org.springframework.data.domain.PageRequest.of(0,10));
        assertThat(facts).hasSize(1); assertThat(facts.get(0).eventType).isEqualTo(TrustEventType.CIRCULAR_ACTIVITY_CONFIRMED);
        scores.recalculate(orgs.get(0),null,"worker",false);
        assertThat(scores.get(orgs.get(0),TrustRole.OVERALL).trustLevel()).isEqualTo(TrustLevel.INSUFFICIENT_DATA);
    }
    @Test void trustedProducerStillCannotAttributeAnotherOrganizationsDeal() {
        var buyer=account(Role.BUYER); var seller=account(Role.SELLER); var unrelated=account(Role.BUYER);
        organizations.ensureForUser(buyer.getId()); UUID sellerOrg=organizations.ensureForUser(seller.getId());
        UUID unrelatedOrg=organizations.ensureForUser(unrelated.getId());
        var deal=deals.saveAndFlush(new Deal("Attribution fixture","Test",buyer.getId(),seller.getId(),new BigDecimal("5000"),"INR",DealStatus.COMPLETED));
        var event=new TrustEventInput(unrelatedOrg,unrelated.getId(),deal.getId(),null,sellerOrg,TrustRole.BUYER,
                TrustEventType.PAYMENT_COMPLETED,new BigDecimal("5000"),"INR",1,Instant.now().minusSeconds(1),Map.of(),"FINX_AUDIT","wrong-owner");
        assertThatThrownBy(()->ingestion.record(event)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configVersioningValidationAndManualReviewApi() throws Exception {
        var owner=account(Role.BUYER); UUID id=organizations.ensureForUser(owner.getId()); var admin=account(Role.ADMIN);
        var current=configs.current();
        var body=Map.of("rules",current.rules,"expectedVersion",current.id,"reason","Calibration after documented model review");
        mvc.perform(put("/api/admin/trust/config").with(user(UserPrincipal.create(admin))).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isOk());
        assertThat(configs.current().id).isGreaterThan(current.id); assertThat(configRepository.findById(current.id)).isPresent();
        mvc.perform(put("/api/admin/trust/config").with(user(UserPrincipal.create(admin))).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        String review=mvc.perform(post("/api/admin/trust/"+id+"/reviews").with(user(UserPrincipal.create(admin))).contentType("application/json").content("{\"reason\":\"Check unusual completion pattern\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reviewId=json.readTree(review).path("data").path("id").asText();
        mvc.perform(post("/api/admin/trust/reviews/"+reviewId+"/resolve").with(user(UserPrincipal.create(admin))).contentType("application/json").content("{\"reason\":\"Supporting documentation accepted\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }
}
