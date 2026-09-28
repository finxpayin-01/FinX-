package com.finx.trust.event;

import com.finx.audit.entity.AuditLog;
import com.finx.trust.model.*;
import com.finx.trust.service.TrustOrganizationService;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Captures committed domain facts in the originating transaction. Scoring never runs here. */
@Service
public class TrustSourceAdapter {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final TrustOrganizationService organizations;
    private final TrustEventIngestionService ingestion;
    public TrustSourceAdapter(JdbcTemplate jdbc, EntityManager em, TrustOrganizationService organizations, TrustEventIngestionService ingestion) {
        this.jdbc=jdbc; this.em=em; this.organizations=organizations; this.ingestion=ingestion;
    }
    @EventListener
    public void onAudit(TrustAuditRecorded recorded) { capture(recorded.audit()); }

    @Transactional
    public void capture(AuditLog audit) {
        captureFact(audit,false);
    }
    @Transactional
    public void replay(AuditLog audit) {
        captureFact(audit,true);
    }
    private void captureFact(AuditLog audit, boolean replay) {
        String action=audit.getAction();
        if(!Set.of("PAYMENT_VERIFIED","PAYMENT_FAILED","DELIVERABLE_SUBMITTED","MILESTONE_APPROVED",
                "MILESTONE_REJECTED","MILESTONE_COMPLETED","DEAL_COMPLETED","DEAL_CANCELLED",
                "DISPUTE_CREATED","DISPUTE_RESOLVED","ESCROW_FUNDED").contains(action)) return;
        em.flush();
        jdbc.queryForList("select id from audit_logs where id=? for update",audit.getId());
        if(em.find(com.finx.trust.entity.TrustAuditReceipt.class,audit.getId())!=null) return;
        var receipt=new com.finx.trust.entity.TrustAuditReceipt(); receipt.auditId=audit.getId(); receipt.capturedAt=Instant.now(); em.persist(receipt);
        UUID entity;
        try { entity=UUID.fromString(audit.getEntityId()); } catch (IllegalArgumentException e) { return; }
        if(action.startsWith("PAYMENT_")) {
            var payment=one("select * from payments where id=?",entity);
            if(payment.isEmpty()) return;
            var deal=one("select * from deals where id=?",id(payment,"deal_id"));
            emit(audit,deal,id(payment,"buyer_id"),TrustRole.BUYER,
                    action.equals("PAYMENT_VERIFIED")?TrustEventType.PAYMENT_COMPLETED:TrustEventType.PAYMENT_FAILED,
                    id(payment,"milestone_id"),decimal(payment,"amount"),Map.of(),"payment:"+entity+":"+action);
        } else if(action.equals("DELIVERABLE_SUBMITTED")) {
            var document=one("select * from deliverables where id=?",entity); if(document.isEmpty()) return;
            var milestone=one("select * from milestones where id=?",id(document,"milestone_id"));
            var deal=one("select * from deals where id=?",id(milestone,"deal_id"));
            // Historical audits did not snapshot agreed deadlines; do not invent historical punctuality.
            var due=replay?null:time(milestone,"due_date"); var submitted=time(document,"submitted_at");
            long delay=due==null?0:Math.max(0,Duration.between(due,submitted).toSeconds());
            var type=delay>0?TrustEventType.MILESTONE_OVERDUE:TrustEventType.MILESTONE_SUBMITTED;
            emit(audit,deal,id(deal,"seller_id"),TrustRole.VENDOR,type,id(milestone,"id"),decimal(milestone,"amount"),
                    Map.of("deadlineKnown",due!=null,"delayDays",delay/86400d),"document:"+entity+":submitted");
            emit(audit,deal,id(deal,"seller_id"),TrustRole.VENDOR,TrustEventType.DOCUMENT_UPLOADED,id(milestone,"id"),
                    decimal(milestone,"amount"),Map.of(),"document:"+entity+":uploaded");
        } else if(action.startsWith("MILESTONE_")) {
            var milestone=one("select * from milestones where id=?",entity); if(milestone.isEmpty()) return;
            var deal=one("select * from deals where id=?",id(milestone,"deal_id"));
            var type=TrustEventType.valueOf(action);
            var metadata=new HashMap<String,Object>();
            if(type==TrustEventType.MILESTONE_COMPLETED) metadata.put("elapsedSeconds",Math.max(0,Duration.between(time(milestone,"created_at"),audit.getCreatedAt()).toSeconds()));
            String key=type==TrustEventType.MILESTONE_REJECTED ? "audit:"+audit.getId() : "milestone:"+entity+":"+action;
            emit(audit,deal,id(deal,"seller_id"),TrustRole.VENDOR,type,entity,decimal(milestone,"amount"),metadata,key);
            if(type==TrustEventType.MILESTONE_APPROVED) {
                emit(audit,deal,id(deal,"seller_id"),TrustRole.VENDOR,TrustEventType.DOCUMENT_VERIFIED,entity,decimal(milestone,"amount"),Map.of(),"milestone:"+entity+":evidence-approved");
                if(id(deal,"buyer_id").equals(audit.getActorUserId()) || Objects.equals(id(deal,"project_manager_id"),audit.getActorUserId()) && audit.getActorUserId()!=null)
                    emit(audit,deal,id(deal,"buyer_id"),TrustRole.BUYER,type,entity,decimal(milestone,"amount"),Map.of(),key);
            }
        } else if(action.startsWith("DEAL_")) {
            var deal=one("select * from deals where id=?",entity); if(deal.isEmpty()) return;
            both(audit,deal,TrustEventType.valueOf(action),null,Map.of(),"deal:"+entity+":"+action);
        } else if(action.startsWith("DISPUTE_")) {
            if(!audit.getEntityType().equals("DISPUTE")) return; // Chain resolution is not a fault verdict.
            var dispute=one("select * from disputes where id=?",entity); if(dispute.isEmpty()) return;
            var deal=one("select * from deals where id=?",id(dispute,"deal_id"));
            both(audit,deal,TrustEventType.valueOf(action),id(dispute,"milestone_id"),
                    Map.of("disputeId",entity.toString()),"dispute:"+entity+":"+action);
        } else if(action.equals("ESCROW_FUNDED") && audit.getEntityType().equals("BLOCKCHAIN_ESCROW")) {
            var escrow=one("select * from blockchain_escrows where id=?",entity); if(escrow.isEmpty()) return;
            var deal=one("select * from deals where id=?",id(escrow,"deal_id"));
            // An indexed, confirmed funding event is economic activity; failed wallet intents are not.
            emit(audit,deal,id(deal,"buyer_id"),TrustRole.BUYER,TrustEventType.PAYMENT_COMPLETED,null,
                    decimal(deal,"total_amount"),Map.of(),"chain:"+entity+":funded");
        }
    }
    private void both(AuditLog audit,Map<String,Object> deal,TrustEventType type,UUID milestone,Map<String,Object> metadata,String key) {
        // All multi-organization writers acquire locks in UUID order.
        var users=new ArrayList<>(List.of(id(deal,"buyer_id"),id(deal,"seller_id"))); users.sort(Comparator.naturalOrder());
        for(var user:users) emit(audit,deal,user,user.equals(id(deal,"buyer_id"))?TrustRole.BUYER:TrustRole.VENDOR,type,milestone,decimal(deal,"total_amount"),metadata,key);
    }
    private void emit(AuditLog audit,Map<String,Object> deal,UUID user,TrustRole role,TrustEventType type,UUID milestone,
            BigDecimal value,Map<String,Object> metadata,String key) {
        if(deal.isEmpty()) return;
        var users=new ArrayList<>(List.of(id(deal,"buyer_id"),id(deal,"seller_id"))); users.sort(Comparator.naturalOrder());
        var orgs=new HashMap<UUID,UUID>();
        for(var u:users) orgs.put(u,organizations.ensureForUser(u));
        var ids=new ArrayList<>(new HashSet<>(orgs.values())); ids.sort(Comparator.naturalOrder());
        for(var org:ids) jdbc.queryForList("select id from organizations where id=? for update",org);
        UUID counterparty=user.equals(id(deal,"buyer_id"))?id(deal,"seller_id"):id(deal,"buyer_id");
        ingestion.record(new TrustEventInput(orgs.get(user),user,id(deal,"id"),milestone,orgs.get(counterparty),role,type,value,
                deal.get("currency").toString(),1,audit.getCreatedAt(),metadata,"FINX_AUDIT",key));
    }
    private Map<String,Object> one(String query,UUID id) { var rows=jdbc.queryForList(query,id); return rows.isEmpty()?Map.of():rows.get(0); }
    private static UUID id(Map<String,Object> row,String key) { return row.get(key)==null?null:UUID.fromString(row.get(key).toString()); }
    private static BigDecimal decimal(Map<String,Object> row,String key) { return new BigDecimal(row.get(key).toString()); }
    private static Instant time(Map<String,Object> row,String key) {
        Object value=row.get(key); if(value==null) return null;
        if(value instanceof OffsetDateTime time) return time.toInstant();
        if(value instanceof java.sql.Timestamp time) return time.toInstant();
        return Instant.parse(value.toString());
    }
}
