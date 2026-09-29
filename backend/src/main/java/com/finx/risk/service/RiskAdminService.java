package com.finx.risk.service;

import com.finx.exception.BadRequestException;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import com.finx.risk.repository.BusinessRiskRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class RiskAdminService {
    private final BusinessRiskRepository db;
    private final NamedParameterJdbcTemplate jdbc;
    private final RiskPolicyService policies;
    private final RiskEvidenceService evidence;
    public RiskAdminService(BusinessRiskRepository db,NamedParameterJdbcTemplate jdbc,RiskPolicyService policies,RiskEvidenceService evidence) {
        this.db=db; this.jdbc=jdbc; this.policies=policies; this.evidence=evidence;
    }
    public record Page<T>(List<T> items,long total,int page,int size) {}
    public record Business(UUID id,UUID userId,UUID organizationId,UserType userType,String name,int riskScore,
                           Level riskLevel,String verificationStatus,BigDecimal transactionVolume,String currency,
                           Instant calculatedAt,int scoreChange,long openAlerts) {}
    public Business business(RiskScore s) {
        var names=jdbc.queryForList(s.organizationId==null?"select name from users where id=:id":"select name from organizations where id=:id",
                Map.of("id",s.organizationId==null?s.userId:s.organizationId),String.class);
        Assessment current=db.current(s);
        return new Business(s.id,s.userId,s.organizationId,s.userType,names.isEmpty()?"Business":names.getFirst(),s.score,s.riskLevel,
                s.verificationStatus,s.transactionVolume,s.currency,s.calculatedAt,current==null?0:current.scoreChange(),
                db.count("select count(a) from RiskAlert a where a.subjectId=:id and a.clearedAt is null",Map.of("id",s.id)));
    }
    public Page<Business> businesses(UserType type,Level level,Integer min,Integer max,String verification,Instant from,Instant to,
                                     BigDecimal volume,Boolean alerts,int page,int size) {
        validatePage(page,size);
        if (min!=null && (min<0||min>100) || max!=null && (max<0||max>100) || min!=null && max!=null && min>max)
            throw new BadRequestException("Score range must be between 0 and 100");
        if (from!=null && to!=null && from.isAfter(to)) throw new BadRequestException("Invalid date range");
        StringBuilder where=new StringBuilder(" where 1=1"); Map<String,Object> p=new HashMap<>();
        filter(where,p,"s.userType=:type","type",type); filter(where,p,"s.riskLevel=:level","level",level);
        filter(where,p,"s.score>=:min","min",min); filter(where,p,"s.score<=:max","max",max);
        filter(where,p,"s.verificationStatus=:verification","verification",verification);
        filter(where,p,"s.calculatedAt>=:from","from",from); filter(where,p,"s.calculatedAt<=:to","to",to);
        filter(where,p,"s.transactionVolume>=:volume","volume",volume);
        if (alerts!=null) where.append(alerts?" and exists ":" and not exists ").append("(select a.id from RiskAlert a where a.subjectId=s.id and a.clearedAt is null)");
        List<Business> items=db.page(RiskScore.class,"from RiskScore s"+where+" order by s.score desc,s.id",p,page,size).stream().map(this::business).toList();
        return new Page<>(items,db.count("select count(s) from RiskScore s"+where,p),page,size);
    }
    public Map<String,Object> dashboard() {
        Map<String,Object> result=new LinkedHashMap<>(); Map<String,Long> counts=new LinkedHashMap<>();
        for(Level level:Level.values()) counts.put(level.name(),db.count("select count(s) from RiskScore s where s.riskLevel=:level and s.assessmentId is not null",Map.of("level",level)));
        result.put("totalMonitoredBusinesses",db.count("select count(s) from RiskScore s",Map.of()));
        result.put("unassessedBusinesses",db.count("select count(s) from RiskScore s where s.assessmentId is null",Map.of()));
        result.put("levelCounts",counts);
        result.put("openAlerts",db.count("select count(a) from RiskAlert a where a.clearedAt is null",Map.of()));
        result.put("pendingReviews",db.count("select count(r) from RiskReview r where r.status<>:cleared",Map.of("cleared",ReviewStatus.CLEARED)));
        result.put("pendingEvents",db.count("select count(e) from RiskEvent e where e.processedAt is null and e.deadLetter=false",Map.of()));
        result.put("failedEvents",db.count("select count(e) from RiskEvent e where e.deadLetter=true",Map.of()));
        result.put("recentScoreChanges",db.query(RiskScoreHistory.class,"from RiskScoreHistory order by calculatedAt desc,id",Map.of(),15).stream().map(h->db.read(h.snapshot,Assessment.class)).toList());
        result.put("recentHighRiskEvents",db.query(RiskEvent.class,"from RiskEvent e where exists (select s.id from RiskScore s where s.id=e.subjectId and s.score>60) order by e.occurredAt desc",Map.of(),15));
        return result;
    }
    public RiskScore resolveSubject(UUID entityId) {
        var byId = db.query(RiskScore.class, "from RiskScore where id=:id", Map.of("id", entityId), 1);
        if (!byId.isEmpty()) return byId.getFirst();

        var byOrg = db.query(RiskScore.class, "from RiskScore where organizationId=:id", Map.of("id", entityId), 1);
        if (!byOrg.isEmpty()) return byOrg.getFirst();

        var byUser = db.query(RiskScore.class, "from RiskScore where userId=:id order by (case when organizationId is null then 0 else 1 end)", Map.of("id", entityId), 1);
        if (!byUser.isEmpty()) return byUser.getFirst();

        throw new com.finx.exception.ResourceNotFoundException("RiskScore", "entityId", entityId);
    }
    public Map<String,Object> profile(UUID id) {
        RiskScore s=resolveSubject(id); Map<String,Object> result=new LinkedHashMap<>();
        result.put("subject",business(s)); result.put("assessment",db.current(s));
        result.put("history",history(s.id,0,100));
        result.put("reviews",db.query(RiskReview.class,"from RiskReview where subjectId=:id order by createdAt desc",Map.of("id",s.id),100));
        result.put("alerts",db.query(RiskAlert.class,"from RiskAlert where subjectId=:id order by createdAt desc",Map.of("id",s.id),100));
        result.put("events",db.query(RiskEvent.class,"from RiskEvent where subjectId=:id order by occurredAt desc",Map.of("id",s.id),100));
        result.put("reviewHistory",jdbc.queryForList("""
            select a.id,a.actor_user_id,a.metadata,a.created_at from audit_logs a
            where a.action='RISK_REVIEW_DECISION' and a.entity_id in
            (select cast(r.id as varchar) from risk_reviews r where r.subject_id=:id)
            order by a.created_at desc limit 100
            """,Map.of("id",s.id)));
        var args=evidence.parameters(s,policies.policy(s.userType),Instant.now()); String scope=evidence.dealScope(s);
        result.put("payments",jdbc.queryForList("select x.id,x.amount,x.currency,x.status,x.created_at from payment_instructions x join deals d on d.id=x.deal_id where "+scope+" order by x.created_at desc limit 50",args));
        result.put("legacyPayments",jdbc.queryForList("select x.id,x.amount,x.currency,x.status,x.created_at from payments x join deals d on d.id=x.deal_id where "+scope+" order by x.created_at desc limit 50",args));
        result.put("milestones",jdbc.queryForList("select m.id,m.title,m.status,m.amount,m.currency,m.due_date from milestones m join deals d on d.id=m.deal_id where "+scope+" order by m.created_at desc limit 50",args));
        result.put("disputes",jdbc.queryForList("select x.id,x.deal_id,x.status,x.created_at,x.resolved_at from disputes x join deals d on d.id=x.deal_id where "+scope+" order by x.created_at desc limit 50",args));
        result.put("verification",jdbc.queryForList("select status,verified_at,expires_at from kyc_profiles where user_id=:user",args));
        result.put("businessVerification",s.organizationId==null?List.of():jdbc.queryForList("select status,verified_at,expires_at from kyb_profiles where organization_id=:org",args));
        result.put("securityEvents",jdbc.queryForList("""
            select e.id, e.event_type, a.action as audit_action, a.metadata, e.occurred_at
            from risk_events e left join audit_logs a on a.id=e.audit_log_id
            where e.subject_id=:id and (e.event_type='SECURITY_EVENT' or a.action='FAILED_LOGIN_ATTEMPT')
            order by e.occurred_at desc limit 50
            """,Map.of("id",s.id)));
        result.put("bankAccountChanges",jdbc.queryForList("""
            select e.id, e.event_type, e.source_key, e.occurred_at, e.processed_at
            from risk_events e where e.subject_id=:id and e.event_type='BANK_ACCOUNT_CHANGED'
            order by e.occurred_at desc limit 50
            """,Map.of("id",s.id)));
        return result;
    }
    public Page<Assessment> history(UUID id,int page,int size) {
        validatePage(page,size);
        RiskScore s=resolveSubject(id);
        var p=Map.of("id",s.id);
        return new Page<>(db.page(RiskScoreHistory.class,"from RiskScoreHistory where subjectId=:id order by calculatedAt desc,id",p,page,size).stream().map(h->db.read(h.snapshot,Assessment.class)).toList(),
                db.count("select count(h) from RiskScoreHistory h where h.subjectId=:id",p),page,size);
    }
    public Page<RiskAlert> alerts(boolean open,int page,int size) {
        validatePage(page,size); String where=open?" where clearedAt is null":"";
        return new Page<>(db.page(RiskAlert.class,"from RiskAlert"+where+" order by createdAt desc,id",Map.of(),page,size),
                db.count("select count(a) from RiskAlert a"+where,Map.of()),page,size);
    }
    public Page<RiskReview> reviews(ReviewStatus status,int page,int size) {
        validatePage(page,size); String where=status==null?"":" where status=:status"; Map<String,?> p=status==null?Map.of():Map.of("status",status);
        return new Page<>(db.page(RiskReview.class,"from RiskReview"+where+" order by createdAt desc,id",p,page,size),
                db.count("select count(r) from RiskReview r"+where,p),page,size);
    }
    public List<Map<String,Object>> rules() { return Arrays.stream(UserType.values()).map(type->policies.view(policies.rule(type))).toList(); }
    private void filter(StringBuilder sql,Map<String,Object> p,String clause,String key,Object value) { if(value!=null){ sql.append(" and ").append(clause); p.put(key,value); } }
    private void validatePage(int page,int size) { if(page<0||page>10000||size<1||size>100) throw new BadRequestException("Invalid pagination"); }
}
