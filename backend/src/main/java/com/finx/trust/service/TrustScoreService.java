package com.finx.trust.service;

import com.finx.trust.dto.TrustResult;
import com.finx.trust.entity.*;
import com.finx.trust.repository.*;
import com.finx.trust.model.*;
import com.finx.trust.engine.TrustScoringEngine;
import com.finx.audit.service.AuditService;
import com.finx.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

@Service
public class TrustScoreService {
    private final TrustOrganizationRepository organizations;
    private final TrustEventRepository events;
    private final TrustScoreRepository scores;
    private final TrustScoreHistoryRepository history;
    private final TrustSignalRepository signals;
    private final TrustConfigService configs;
    private final TrustScoringEngine engine;
    private final AuditService audit;
    private final Clock clock;
    public TrustScoreService(TrustOrganizationRepository organizations, TrustEventRepository events,
            TrustScoreRepository scores, TrustScoreHistoryRepository history, TrustSignalRepository signals,
            TrustConfigService configs, TrustScoringEngine engine, AuditService audit, Clock clock) {
        this.organizations=organizations; this.events=events; this.scores=scores; this.history=history;
        this.signals=signals; this.configs=configs; this.engine=engine; this.audit=audit; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public TrustResult get(UUID id, TrustRole role) {
        return scores.findByOrganizationIdAndRole(id,role).map(s->s.result).orElseGet(() -> {
            var org=organizations.findById(id).orElseThrow(()->new ResourceNotFoundException("Organization","id",id));
            // No history is read on a page request. An uncalculated account is explicitly neutral.
            var config=configs.current();
            var empty=engine.calculate(id,role,org.createdAt,org.createdAt,config.id,config.rules,java.util.stream.Stream.empty());
            return new TrustResult(id,role,50,0,TrustLevel.INSUFFICIENT_DATA,empty.factors(),List.of(),List.of(),empty.statistics(),
                    "Insufficient transaction history to establish a reliable trust score.",null,config.rules.algorithmVersion(),config.id,0,"",null);
        });
    }
    @Transactional
    public void recalculate(UUID id, UUID actor, String reason, boolean manual) {
        var org=organizations.lock(id).orElseThrow(()->new ResourceNotFoundException("Organization","id",id));
        var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); var config=configs.current();
        if(manual) {
            if(org.lastManualAt!=null && org.lastManualAt.plusSeconds(60).isAfter(now))
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Wait 60 seconds between recalculations");
            org.lastManualAt=now;
        } else if(!org.dirty && org.lastCalculatedAt!=null && org.lastCalculatedAt.plus(Duration.ofHours(24)).isAfter(now)
                && org.configVersion==config.id) return;
        var trigger=events.findFirstByOrganizationIdAndProcessedFalseOrderByCreatedAtDesc(id).orElse(null);
        for(var role:TrustRole.values()) {
            TrustResult result;
            try(var stream=events.streamForOrganization(id)) { result=engine.calculate(id,role,org.createdAt,now,config.id,config.rules,stream); }
            var previous=scores.findByOrganizationIdAndRole(id,role).orElse(null);
            var entry=new TrustScoreHistory(); entry.id=UUID.randomUUID(); entry.organizationId=id; entry.role=role;
            entry.previousScore=previous==null?null:previous.overallScore; entry.newScore=result.overallScore();
            entry.previousLevel=previous==null?null:previous.trustLevel.name(); entry.newLevel=result.trustLevel().name();
            entry.reason=manual?reason:trigger==null?"Scheduled decay/configuration reconciliation":"Recorded "+trigger.eventType.name().toLowerCase().replace('_',' ');
            entry.triggerEventId=trigger==null?null:trigger.id; entry.actorId=actor; entry.configVersion=config.id;
            entry.calculatedAt=now; entry.snapshot=result; history.save(entry);
            var score=previous==null?new TrustScore():previous;
            score.id=previous==null?UUID.randomUUID():score.id; score.organizationId=id; score.role=role;
            score.overallScore=result.overallScore(); score.confidenceScore=result.confidenceScore(); score.trustLevel=result.trustLevel();
            score.configVersion=config.id; score.algorithmVersion=result.algorithmVersion(); score.dataPoints=result.dataPoints();
            score.lastCalculatedAt=now; score.result=result; scores.save(score);
            for(var message:result.positiveSignals()) persistSignal(entry,trigger,message,"POSITIVE",1);
            for(var message:result.negativeSignals()) persistSignal(entry,trigger,message,"RISK",-1);
        }
        events.markProcessed(id); org.dirty=false; org.lastCalculatedAt=now; org.configVersion=config.id;
        org.failures=0; org.retryAfter=null;
        if(manual) audit.logEvent(actor,"TRUST_RECALCULATED","ORGANIZATION",id.toString(),reason);
    }
    private void persistSignal(TrustScoreHistory entry,TrustEvent trigger,String message,String type,double impact) {
        var signal=new TrustSignal(); signal.id=UUID.randomUUID(); signal.organizationId=entry.organizationId;
        signal.calculationId=entry.id; signal.role=entry.role; signal.signalType=type; signal.category="BEHAVIOUR";
        signal.impact=impact; signal.severity=1; signal.description=message;
        // Aggregate explanations refer to the complete calculation, not an unrelated trigger event.
        signal.sourceEventId=null; signal.occurredAt=entry.calculatedAt; signal.expiresAt=entry.calculatedAt.plus(Duration.ofDays(1));
        signals.save(signal);
    }
}
