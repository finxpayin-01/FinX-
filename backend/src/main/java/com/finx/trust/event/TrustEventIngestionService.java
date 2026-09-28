package com.finx.trust.event;

import com.finx.trust.entity.TrustEvent;
import com.finx.trust.repository.*;
import com.finx.trust.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class TrustEventIngestionService {
    private final TrustEventRepository events;
    private final TrustOrganizationRepository organizations;
    private final Clock trustClock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public TrustEventIngestionService(TrustEventRepository events, TrustOrganizationRepository organizations, Clock trustClock,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.events=events; this.organizations=organizations; this.trustClock=trustClock;
        this.jdbc=jdbc;
    }
    @Transactional
    public void record(TrustEventInput fact) {
        Objects.requireNonNull(fact.organizationId()); Objects.requireNonNull(fact.role()); Objects.requireNonNull(fact.eventType());
        if(fact.eventType()==TrustEventType.FRAUD_CONFIRMED && fact.role()!=TrustRole.OVERALL)
            throw new IllegalArgumentException("Confirmed organization fraud must apply to all roles");
        if(fact.occurredAt()==null || fact.occurredAt().isAfter(trustClock.instant()) || fact.severity()<1 || fact.severity()>5
                || fact.sourceKey()==null || fact.sourceKey().isBlank() || fact.sourceKey().length()>300
                || !Set.of("FINX_AUDIT","EVIDENCE_VERIFIER","COMPLIANCE_ENGINE","COUNTERPARTY_GRAPH","VERIFIED_ADJUDICATION").contains(fact.source())
                || fact.currency()==null || !fact.currency().matches("[A-Z]{3,10}")
                || (fact.eventValue()!=null && (fact.eventValue().signum()<0 || fact.eventValue().precision()>19 || fact.eventValue().scale()>4)))
            throw new IllegalArgumentException("Invalid trust event");
        if(fact.metadata()==null || fact.metadata().size()>8 || fact.metadata().entrySet().stream().anyMatch(e ->
                !Set.of("deadlineKnown","delayDays","disputeId","elapsedSeconds","evidenceReference").contains(e.getKey())
                || !(e.getValue() instanceof String || e.getValue() instanceof Boolean || e.getValue() instanceof Number)
                || e.getValue().toString().length()>300)) throw new IllegalArgumentException("Invalid event metadata");
        if(Set.of(TrustEventType.FRAUD_CONFIRMED,TrustEventType.DISPUTE_LOST,TrustEventType.DISPUTE_WON,
                TrustEventType.CONTRACT_BREACH,TrustEventType.UNFAIR_REJECTION_CONFIRMED).contains(fact.eventType())
                && (!Set.of("VERIFIED_ADJUDICATION","COMPLIANCE_ENGINE").contains(fact.source())
                    || !(fact.metadata().get("evidenceReference") instanceof String reference) || reference.isBlank()))
            throw new IllegalArgumentException("Verified adjudication reference required");
        if(fact.metadata().values().stream().anyMatch(v->v instanceof Number n && !Double.isFinite(n.doubleValue())))
            throw new IllegalArgumentException("Metadata numbers must be finite");
        validateRelationships(fact);
        var org=organizations.lock(fact.organizationId()).orElseThrow();
        String key=fact.organizationId()+":"+fact.source()+":"+fact.sourceKey();
        if(key.length()>300) throw new IllegalArgumentException("Event key too long");
        if(events.existsBySourceKey(key)) return;
        var event=new TrustEvent(); event.id=UUID.randomUUID(); event.organizationId=org.id;
        event.userId=fact.userId(); event.dealId=fact.dealId(); event.milestoneId=fact.milestoneId();
        event.counterpartyId=fact.counterpartyId(); event.role=fact.role(); event.eventType=fact.eventType();
        event.eventValue=fact.eventValue(); event.currency=fact.currency(); event.severity=fact.severity();
        event.occurredAt=fact.occurredAt(); event.metadata=Map.copyOf(fact.metadata()); event.source=fact.source();
        event.sourceKey=key; event.createdAt=trustClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); events.save(event); org.dirty=true;
    }
    private void validateRelationships(TrustEventInput fact) {
        if(fact.userId()!=null && jdbc.queryForObject("select count(*) from organization_members where user_id=? and organization_id=?",Long.class,
                fact.userId(),fact.organizationId())!=1) throw new IllegalArgumentException("Event user is not an organization member");
        if(fact.milestoneId()!=null && (fact.dealId()==null || jdbc.queryForObject("select count(*) from milestones where id=? and deal_id=?",Long.class,
                fact.milestoneId(),fact.dealId())!=1)) throw new IllegalArgumentException("Milestone does not belong to event deal");
        if(fact.dealId()==null) return;
        var rows=jdbc.queryForList("""
            select b.organization_id buyer_org,s.organization_id vendor_org,d.currency,d.total_amount
            from deals d join organization_members b on b.user_id=d.buyer_id
            join organization_members s on s.user_id=d.seller_id where d.id=?
            """,fact.dealId());
        if(rows.isEmpty()) throw new IllegalArgumentException("Unknown event deal organizations");
        var row=rows.get(0); UUID buyer=UUID.fromString(row.get("buyer_org").toString()),vendor=UUID.fromString(row.get("vendor_org").toString());
        if(!fact.organizationId().equals(buyer) && !fact.organizationId().equals(vendor)
                || fact.role()==TrustRole.BUYER && !fact.organizationId().equals(buyer)
                || fact.role()==TrustRole.VENDOR && !fact.organizationId().equals(vendor)
                || fact.counterpartyId()!=null && !fact.counterpartyId().equals(fact.organizationId().equals(buyer)?vendor:buyer)
                || !fact.currency().equals(row.get("currency").toString())
                || fact.eventValue()!=null && fact.eventValue().compareTo(new java.math.BigDecimal(row.get("total_amount").toString()))>0)
            throw new IllegalArgumentException("Event does not match recorded deal parties, role, currency or value");
    }
}
