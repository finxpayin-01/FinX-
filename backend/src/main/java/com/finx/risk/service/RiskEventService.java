package com.finx.risk.service;

import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import com.finx.risk.event.RiskDomainEvent;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.user.entity.User;
import com.finx.org.entity.Organization;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class RiskEventService {
    private final BusinessRiskRepository db;
    private final NamedParameterJdbcTemplate jdbc;
    public RiskEventService(BusinessRiskRepository db,NamedParameterJdbcTemplate jdbc) { this.db=db; this.jdbc=jdbc; }

    @Transactional public RiskScore subject(UUID userId,UUID organizationId,UserType type) {
        // Serialize subject creation against an existing row, including on multiple application instances.
        if (organizationId == null) db.lock(User.class,userId); else db.lock(Organization.class,organizationId);
        String key=(organizationId==null?"USER:"+userId:"ORG:"+organizationId)+":"+type;
        var existing=db.query(RiskScore.class,"from RiskScore where subjectKey=:key",Map.of("key",key),1);
        if (!existing.isEmpty()) return existing.getFirst();
        RiskScore subject=new RiskScore(); subject.subjectKey=key; subject.userId=userId;
        subject.organizationId=organizationId; subject.userType=type; db.persist(subject); db.flush();
        enqueue(subject,EventType.REGISTERED,"REGISTERED:"+key,null,null,Instant.now());
        return subject;
    }

    public RiskEvent enqueue(RiskScore subject,EventType type,String key,UUID auditId,UUID dealId,Instant at) {
        var existing=db.query(RiskEvent.class,"from RiskEvent e where e.subjectId=:id and e.sourceKey=:key",Map.of("id",subject.id,"key",key),1);
        if(!existing.isEmpty()) return existing.getFirst();
        RiskEvent event=new RiskEvent(); event.subjectId=subject.id; event.eventType=type; event.sourceKey=key;
        event.auditLogId=auditId; event.dealId=dealId; event.occurredAt=at; db.persist(event);
        return event;
    }

    @EventListener @Transactional
    public void observe(com.finx.risk.event.RiskSignalObserved signal) {
        if(signal.subjectId()==null || signal.eventType()==null || signal.sourceKey()==null || signal.sourceKey().isBlank() || signal.sourceKey().length()>200
                || signal.evidenceReference()==null || !signal.evidenceReference().matches("[A-Za-z0-9:/_.-]{1,255}")
                || signal.occurredAt()==null || signal.occurredAt().isAfter(Instant.now().plusSeconds(60))
                || signal.values()==null || signal.values().isEmpty()) throw new IllegalArgumentException("Invalid structured risk event");
        signal.values().forEach((metric,value)->{
            if(metric==null || metric.eventType!=signal.eventType() || value==null || !Double.isFinite(value) || value<0 || value>1000000)
                throw new IllegalArgumentException("Risk telemetry must contain supported nonnegative business metrics");
        });
        RiskScore subject=db.lock(RiskScore.class,signal.subjectId());
        var existing=db.query(RiskEvent.class,"from RiskEvent where subjectId=:id and sourceKey=:key",Map.of("id",subject.id,"key","SIGNAL:"+signal.sourceKey()),1);
        if(!existing.isEmpty()) return;
        RiskEvent event=enqueue(subject,signal.eventType(),"SIGNAL:"+signal.sourceKey(),null,null,signal.occurredAt());
        event.safeEvidence=db.json(new com.finx.risk.event.RiskSignalObserved.SafeEvidence(signal.evidenceReference(),signal.values()));
    }

    @EventListener
    @Transactional
    public void onDomainEvent(RiskDomainEvent event) {
        EventType type=eventType(event.action());
        if (type==null) return;
        db.flush();
        UUID dealId=dealId(event);
        if (dealId!=null) {
            var deals=jdbc.queryForList("select buyer_id,seller_id,buyer_organization_id,seller_organization_id from deals where id=:id",Map.of("id",dealId));
            if (deals.isEmpty()) return;
            var d=deals.getFirst();
            recordParty(d,"buyer",UserType.BUYER,type,event,dealId);
            recordParty(d,"seller",UserType.VENDOR,type,event,dealId);
            return;
        }
        UUID orgId="ORGANIZATION".equals(event.entityType())?event.entityId():null;
        if ("BANK_ACCOUNT".equals(event.entityType())) {
            var ids=jdbc.queryForList("select organization_id from organization_bank_accounts where id=:id",Map.of("id",event.entityId()),UUID.class);
            if (!ids.isEmpty()) orgId=ids.getFirst();
            if (orgId!=null && type==EventType.BANK_ACCOUNT_CHANGED) {
                Long accounts=jdbc.queryForObject("select count(*) from organization_bank_accounts where organization_id=:id",Map.of("id",orgId),Long.class);
                if (accounts!=null && accounts<=1) type=EventType.KYB_UPDATED;
            }
        }
        if (orgId!=null) {
            var members=jdbc.queryForList("select distinct u.id,u.role from users u join organization_members m on m.user_id=u.id where m.organization_id=:id and u.role in ('BUYER','SELLER') order by u.id",Map.of("id",orgId));
            for (var member:members) {
                RiskScore s=subject((UUID)member.get("id"),orgId,"BUYER".equals(member.get("role"))?UserType.BUYER:UserType.VENDOR);
                enqueue(s,type,"AUDIT:"+event.auditId(),event.auditId(),null,event.occurredAt());
            }
        } else if ("USER".equals(event.entityType())) {
            var users=jdbc.queryForList("select role from users where id=:id and role in ('BUYER','SELLER')",Map.of("id",event.entityId()));
            if (users.isEmpty()) return;
            UserType userType="BUYER".equals(users.getFirst().get("role"))?UserType.BUYER:UserType.VENDOR;
            RiskScore base=subject(event.entityId(),null,userType);
            enqueue(base,type,"AUDIT:"+event.auditId(),event.auditId(),null,event.occurredAt());
            var orgs=jdbc.queryForList("select organization_id from organization_members where user_id=:id order by organization_id",Map.of("id",event.entityId()),UUID.class);
            for (UUID org:orgs) {
                RiskScore s=subject(event.entityId(),org,userType);
                enqueue(s,type,"AUDIT:"+event.auditId(),event.auditId(),null,event.occurredAt());
            }
        }
    }

    private void recordParty(Map<String,Object> d,String side,UserType userType,EventType type,RiskDomainEvent event,UUID deal) {
        UUID user=(UUID)d.get(side+"_id");
        if (user==null) return;
        // Counterparty-only signals must not penalize the other business.
        if (userType==UserType.VENDOR && Set.of(EventType.PROJECT_CANCELLED,EventType.PAYMENT_FAILED,EventType.PAYMENT_REVERSED).contains(type)) return;
        RiskScore s=subject(user,(UUID)d.get(side+"_organization_id"),userType);
        RiskEvent row=enqueue(s,type,"AUDIT:"+event.auditId(),event.auditId(),deal,event.occurredAt());
        switch(event.entityType()) {
            case "MILESTONE" -> row.milestoneId=event.entityId();
            case "PAYMENT" -> row.paymentId=event.entityId();
            case "DISPUTE" -> row.disputeId=event.entityId();
            case "DELIVERABLE" -> row.attachmentId=event.entityId();
            default -> { }
        }
    }

    private UUID dealId(RiskDomainEvent e) {
        String sql=switch(e.entityType()) {
            case "DEAL" -> "select id from deals where id=:id";
            case "MILESTONE" -> "select deal_id from milestones where id=:id";
            case "PAYMENT" -> "select deal_id from payments where id=:id";
            case "PAYMENT_INSTRUCTION" -> "select deal_id from payment_instructions where id=:id";
            case "DISPUTE" -> "select deal_id from disputes where id=:id";
            case "SETTLEMENT" -> "select deal_id from settlement_instructions where id=:id";
            case "DELIVERABLE" -> "select m.deal_id from deliverables x join milestones m on m.id=x.milestone_id where x.id=:id";
            default -> null;
        };
        if (sql==null) return null;
        var ids=jdbc.queryForList(sql,Map.of("id",e.entityId()),UUID.class);
        return ids.isEmpty()?null:ids.getFirst();
    }

    public static EventType eventType(String action) {
        return switch(action) {
            case "USER_REGISTERED","USER_REGISTERED_GOOGLE" -> EventType.REGISTERED;
            case "FAILED_LOGIN_ATTEMPT" -> EventType.SECURITY_EVENT;
            case "ORGANIZATION_CREATED","ORGANIZATION_MEMBER_ADDED","PROFILE_UPDATED" -> EventType.PROFILE_UPDATED;
            case "BANK_ACCOUNT_ADDED","BANK_ACCOUNT_CHANGED" -> EventType.BANK_ACCOUNT_CHANGED;
            case "BANK_ACCOUNT_VERIFIED" -> EventType.KYB_UPDATED;
            case "DEAL_CREATED","PROJECT_CREATED","PROJECT_ACCEPTED" -> EventType.PROJECT_CREATED;
            case "DEAL_CANCELLED","PROJECT_CANCELLED" -> EventType.PROJECT_CANCELLED;
            case "PAYMENT_CREATED","PAYMENT_INSTRUCTION_CREATED","PAYMENT_INITIATED" -> EventType.PAYMENT_INITIATED;
            case "PAYMENT_VERIFIED","PAYMENT_CONFIRMED","SETTLEMENT_COMPLETED" -> EventType.PAYMENT_SUCCEEDED;
            case "DELIVERABLE_SUBMITTED" -> EventType.MILESTONE_SUBMITTED;
            default -> { try { yield EventType.valueOf(action); } catch(IllegalArgumentException ex) { yield null; } }
        };
    }

    @Transactional public int discover(int limit) {
        var users=jdbc.queryForList("""
            select u.id,u.role from users u where u.role in ('BUYER','SELLER')
            and not exists (select 1 from risk_scores s where s.user_id=u.id and s.organization_id is null)
            order by u.id limit :limit
            """,Map.of("limit",limit));
        for (var u:users) subject((UUID)u.get("id"),null,"BUYER".equals(u.get("role"))?UserType.BUYER:UserType.VENDOR);
        var members=jdbc.queryForList("""
            select m.user_id,m.organization_id,u.role from organization_members m join users u on u.id=m.user_id
            where u.role in ('BUYER','SELLER') and not exists
            (select 1 from risk_scores s where s.organization_id=m.organization_id
            and s.user_type=case when u.role='BUYER' then 'BUYER' else 'VENDOR' end)
            order by m.organization_id,m.user_id limit :limit
            """,Map.of("limit",limit));
        for (var m:members) subject((UUID)m.get("user_id"),(UUID)m.get("organization_id"),"BUYER".equals(m.get("role"))?UserType.BUYER:UserType.VENDOR);
        return users.size()+members.size();
    }
}
