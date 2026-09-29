package com.finx.risk.service;

import com.finx.audit.service.AuditService;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.engine.RiskRuleEngine;
import com.finx.risk.entity.*;
import com.finx.risk.enums.RiskEnums;
import com.finx.risk.repository.BusinessRiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RiskScoringService {
    private final BusinessRiskRepository db;
    private final RiskEvidenceService evidence;
    private final RiskRuleEngine engine;
    private final RiskPolicyService policies;
    private final AuditService audit;
    public RiskScoringService(BusinessRiskRepository db,RiskEvidenceService evidence,RiskRuleEngine engine,RiskPolicyService policies,AuditService audit) {
        this.db=db; this.evidence=evidence; this.engine=engine; this.policies=policies; this.audit=audit;
    }
    @Transactional public Assessment recalculate(UUID subjectId,String trigger,UUID actor) {
        RiskScore subject=db.lock(RiskScore.class,subjectId);
        RiskRule rule=policies.rule(subject.userType);
        Policy policy=db.read(rule.configuration,Policy.class);
        Assessment previous=db.current(subject);
        Instant now=Instant.now();
        var observations=evidence.collect(subject,policy,now);
        Calculation result=engine.calculate(policy,observations.metrics(),previous==null?List.of():previous.topRiskFactors());
        boolean reviewRequired=result.score()>60;
        RiskAssessment row=new RiskAssessment(RiskEnums.RiskEntityType.USER,subject.id,
                RiskEnums.RiskLevel.valueOf(result.level().name()),result.score(),db.json(result.factors()),!reviewRequired);
        row.userId=subject.userId; row.organizationId=subject.organizationId; row.userType=subject.userType.name();
        row.previousScore=previous==null?null:previous.totalRiskScore(); row.scoreChange=result.score()-(previous==null?0:previous.totalRiskScore());
        row.reviewRequired=reviewRequired; row.reviewStatus=reviewRequired?"OPEN":"CLEARED";
        row.ruleVersion="rules-v1:"+subject.userType+":"+rule.version;
        row.setCreatedAt(now); db.persist(row);
        var active=result.factors().stream().filter(f->f.impact()>0).toList();
        double factorChange=result.factors().stream().mapToDouble(Factor::change).sum();
        Assessment assessment=new Assessment(row.getId(),subject.id,subject.userId,subject.organizationId,subject.userType,
                result.score(),result.level(),row.previousScore,row.scoreChange,result.factorScores(),result.factors(),
                active.stream().map(Factor::code).sorted().toList(),active.stream().map(Factor::code).sorted().toList(),
                result.factors().stream().map(Factor::reason).toList(),now,row.ruleVersion,reviewRequired,
                reviewRequired?ReviewStatus.OPEN:ReviewStatus.CLEARED,result.action(),trigger,result.unavailableSignals(),row.scoreChange-factorChange);
        RiskScoreHistory history=new RiskScoreHistory(); history.subjectId=subject.id; history.assessmentId=row.getId();
        history.snapshot=db.json(assessment); history.calculatedAt=now; db.persist(history);
        for (Factor factor:result.factors()) {
            RiskFactor entry=new RiskFactor(); entry.assessmentId=row.getId(); entry.code=factor.code(); entry.evidence=db.json(factor); db.persist(entry);
        }
        subject.assessmentId=row.getId(); subject.score=result.score(); subject.riskLevel=result.level();
        subject.verificationStatus=observations.verificationStatus(); subject.transactionVolume=BigDecimal.valueOf(observations.volume());
        subject.currency=policy.currency(); subject.calculatedAt=now; subject.nextRefreshAt=now.plus(1,ChronoUnit.DAYS);
        if (reviewRequired) {
            // Unchanged evidence keeps the existing case and its decision; new evidence opens a new case.
            var last=db.query(RiskReview.class,"from RiskReview where subjectId=:id order by createdAt desc",Map.of("id",subject.id),1);
            boolean same=previous!=null && previous.totalRiskScore()==result.score() && previous.ruleVersion().equals(row.ruleVersion)
                    && previous.reasonCodes().equals(assessment.reasonCodes()) && result.factors().stream().allMatch(f->Math.abs(f.change())<0.000001
                        && previous.topRiskFactors().stream().anyMatch(old->old.code().equals(f.code()) && old.observedValue()==f.observedValue() && old.threshold()==f.threshold()));
            if (last.isEmpty() || !same) {
                RiskAlert alert=new RiskAlert(); alert.subjectId=subject.id; alert.assessmentId=row.getId(); alert.severity=result.level();
                alert.reason=db.json(assessment.humanReadableReasons()); db.persist(alert);
                RiskReview review=new RiskReview(); review.subjectId=subject.id; review.assessmentId=row.getId(); review.alertId=alert.id;
                review.action=result.action(); db.persist(review);
            }
        }
        audit.logEvent(actor,"RISK_ASSESSED","RISK_ASSESSMENT",row.getId().toString(),db.json(Map.of(
                "subjectId",subject.id,"score",result.score(),"change",row.scoreChange,"ruleVersion",row.ruleVersion,"trigger",trigger)));
        return assessment;
    }
}
