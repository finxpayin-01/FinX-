package com.finx.trust.service;

import com.finx.trust.config.TrustRules;
import com.finx.trust.entity.TrustScoringConfig;
import com.finx.trust.repository.TrustScoringConfigRepository;
import com.finx.audit.service.AuditService;
import com.finx.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.util.UUID;

@Service
public class TrustConfigService {
    private final TrustScoringConfigRepository configs;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public TrustConfigService(TrustScoringConfigRepository configs, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.configs=configs; this.audit=audit; this.jdbc=jdbc; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public TrustScoringConfig current() {
        return configs.findFirstByOrderByIdDesc().orElseGet(() -> {
            // Hibernate-only fixtures. Production defaults are seeded by Flyway V15.
            var initial=new TrustScoringConfig(); initial.id=0L; initial.rules=TrustRules.defaults();
            initial.reason="Initial TRUST_V1 configuration"; initial.createdAt=java.time.Instant.EPOCH; return initial;
        });
    }
    @Transactional
    public TrustScoringConfig update(TrustRules rules, long expectedVersion, UUID actor, String reason) {
        try { rules.validate(); } catch (IllegalArgumentException | NullPointerException e) { throw new BadRequestException("Invalid trust configuration: "+e.getMessage()); }
        // Serialize configuration writers using the latest immutable row; re-read after locking.
        var current=current();
        if(current.id>0) jdbc.queryForList("select id from trust_scoring_config where id=? for update",current.id);
        if(current().id!=expectedVersion) throw new BadRequestException("Configuration changed. Reload before saving.");
        var config=new TrustScoringConfig(); config.rules=rules; config.actorId=actor;
        config.reason=reason; config.createdAt=clock.instant(); configs.saveAndFlush(config);
        audit.logEvent(actor,"TRUST_CONFIG_CHANGED","TRUST_CONFIG",config.id.toString(),reason);
        return config;
    }
}
