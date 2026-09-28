package com.finx.trust.event;

import com.finx.trust.repository.TrustOrganizationRepository;
import com.finx.trust.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.time.*;
import java.util.UUID;

/** Durable dirty rows provide restart-safe work. Each organization is its own transaction. */
@Component
public class TrustEventProcessor {
    private static final Logger log=LoggerFactory.getLogger(TrustEventProcessor.class);
    private final TrustOrganizationRepository organizations;
    private final TrustOrganizationService memberships;
    private final TrustConfigService configs;
    private final TrustScoreService scores;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TrustSourceAdapter sources;
    private final com.finx.audit.repository.AuditLogRepository audits;
    private final TrustNetworkAnalyzer network;
    @Value("${trust.worker.enabled:true}") private boolean enabled;
    public TrustEventProcessor(TrustOrganizationRepository organizations, TrustOrganizationService memberships,
            TrustConfigService configs, TrustScoreService scores, JdbcTemplate jdbc, Clock clock,
            TrustSourceAdapter sources,com.finx.audit.repository.AuditLogRepository audits,TrustNetworkAnalyzer network) {
        this.organizations=organizations; this.memberships=memberships; this.configs=configs; this.scores=scores; this.jdbc=jdbc; this.clock=clock;
        this.sources=sources; this.audits=audits;
        this.network=network;
    }
    @Scheduled(fixedDelayString="${trust.worker.delay-ms:15000}",initialDelayString="${trust.worker.initial-delay-ms:30000}")
    public void process() {
        if(!enabled) return;
        var users=jdbc.queryForList("select u.id from users u left join organization_members m on m.user_id=u.id where u.role in ('BUYER','SELLER') and m.user_id is null order by u.id limit 100",UUID.class);
        for(var id:users) memberships.ensureForUser(id);
        var sourceIds=jdbc.queryForList("""
            select a.id from audit_logs a left join trust_audit_receipts r on r.audit_id=a.id
            where r.audit_id is null and a.action in ('PAYMENT_VERIFIED','PAYMENT_FAILED','DELIVERABLE_SUBMITTED',
            'MILESTONE_APPROVED','MILESTONE_REJECTED','MILESTONE_COMPLETED','DEAL_COMPLETED','DEAL_CANCELLED',
            'DISPUTE_CREATED','DISPUTE_RESOLVED','ESCROW_FUNDED') order by a.created_at,a.id limit 100
            """,UUID.class);
        for(var id:sourceIds) {
            try { audits.findById(id).ifPresent(sources::replay); }
            catch(Exception e) { log.error("Trust source reconciliation failed for audit {} ({})",id,e.getClass().getSimpleName()); }
        }
        var now=clock.instant();
        var ids=organizations.pending(now,now.minus(Duration.ofHours(24)),configs.current().id,PageRequest.of(0,50));
        for(var id:ids) {
            try { network.inspect(id); scores.recalculate(id,null,"Scheduled reconciliation",false); }
            catch(Exception e) {
                // A poisoned organization cannot starve the queue; expose failure count to admins.
                jdbc.update("update organizations set failures=failures+1,retry_after=? where id=?",java.sql.Timestamp.from(now.plusSeconds(300)),id);
                log.error("Trust calculation failed for organization {} ({})",id,e.getClass().getSimpleName());
            }
        }
    }
}
