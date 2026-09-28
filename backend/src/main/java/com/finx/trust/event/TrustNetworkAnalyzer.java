package com.finx.trust.event;

import com.finx.trust.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** Conservative graph heuristic: a closed loop is review context, never a fraud verdict. */
@Service
public class TrustNetworkAnalyzer {
    private final JdbcTemplate jdbc;
    private final TrustEventIngestionService ingestion;
    private final Clock clock;
    public TrustNetworkAnalyzer(JdbcTemplate jdbc,TrustEventIngestionService ingestion,Clock clock) {
        this.jdbc=jdbc; this.ingestion=ingestion; this.clock=clock;
    }
    public void inspect(UUID organization) {
        // Follow completed economic edges of length two or three, within the same currency.
        // Existing single-member organizations cannot generally form these loops; merged/multi-role
        // organizations can. Do not link parties using email domains or other inferred identity.
        var loops=jdbc.queryForList("""
            select distinct a.id, a.currency from deals a
            join organization_members ab on ab.user_id=a.buyer_id
            join organization_members av on av.user_id=a.seller_id
            where ab.organization_id=? and a.status='COMPLETED' and a.created_at>=?
            and (ab.organization_id=av.organization_id or exists (
              select 1 from deals b join organization_members bb on bb.user_id=b.buyer_id
              join organization_members bv on bv.user_id=b.seller_id
              where bb.organization_id=av.organization_id and b.status='COMPLETED' and b.currency=a.currency
              and b.id<>a.id and b.created_at>=a.created_at-interval '90' day
              and (bv.organization_id=ab.organization_id or exists (
                select 1 from deals c join organization_members cb on cb.user_id=c.buyer_id
                join organization_members cv on cv.user_id=c.seller_id
                where cb.organization_id=bv.organization_id and cv.organization_id=ab.organization_id
                and c.status='COMPLETED' and c.currency=a.currency and c.id<>a.id and c.id<>b.id
                and c.created_at>=a.created_at-interval '90' day))))
            order by a.id limit 25
            """,organization,java.sql.Timestamp.from(clock.instant().minus(java.time.Duration.ofDays(90))));
        for(var row:loops) {
            UUID deal=UUID.fromString(row.get("id").toString());
            ingestion.record(new TrustEventInput(organization,null,deal,null,null,TrustRole.OVERALL,
                    TrustEventType.CIRCULAR_ACTIVITY_CONFIRMED,null,row.get("currency").toString(),1,
                    clock.instant(),Map.of(),"COUNTERPARTY_GRAPH","closed-loop:"+deal));
        }
    }
}
