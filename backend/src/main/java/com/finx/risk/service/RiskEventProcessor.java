package com.finx.risk.service;

import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import com.finx.risk.repository.BusinessRiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class RiskEventProcessor {
    private final BusinessRiskRepository db;
    private final RiskScoringService scoring;
    private final RiskPolicyService policies;
    public RiskEventProcessor(BusinessRiskRepository db,RiskScoringService scoring,RiskPolicyService policies) { this.db=db; this.scoring=scoring; this.policies=policies; }
    @Transactional public void process(UUID id) {
        RiskEvent event=db.lock(RiskEvent.class,id);
        if (event.processedAt!=null || event.deadLetter || event.retryAt.isAfter(Instant.now())) return;
        scoring.recalculate(event.subjectId,event.eventType.name()+":"+event.id,null);
        event.processedAt=Instant.now(); event.lastError=null;
    }
    @Transactional public void failed(UUID id) {
        RiskEvent event=db.lock(RiskEvent.class,id);
        if (event.processedAt!=null) return;
        event.attempts++; event.deadLetter=event.attempts>=10;
        event.retryAt=Instant.now().plusSeconds(Math.min(3600,30L*(1L<<Math.min(7,event.attempts))));
        event.lastError="Scoring failed; inspect server logs with event ID " + id;
    }
    @Transactional public List<UUID> pending() {
        return db.query(RiskEvent.class,"from RiskEvent where processedAt is null and deadLetter=false and retryAt<=:now order by occurredAt,id",
                Map.of("now",Instant.now()),50).stream().map(e->e.id).toList();
    }
    @Transactional public void refresh() {
        for (UserType type:UserType.values()) {
            RiskRule rule=policies.rule(type);
            String version="rules-v1:"+type+":"+rule.version;
            var subjects=db.query(RiskScore.class,"""
                from RiskScore s where s.userType=:type and
                (s.nextRefreshAt<=:now or exists (select a.id from RiskAssessment a where a.id=s.assessmentId and a.ruleVersion<>:version))
                and not exists (select e.id from RiskEvent e where e.subjectId=s.id and e.processedAt is null and e.deadLetter=false)
                order by s.nextRefreshAt,s.id
                """,Map.of("type",type,"now",Instant.now(),"version",version),50);
            for (RiskScore s:subjects) scoring.recalculate(s.id,"SCHEDULED_REFRESH",null);
        }
    }
}
