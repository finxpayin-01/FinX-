package com.finx.risk.service;

import com.finx.audit.service.AuditService;
import com.finx.exception.BadRequestException;
import com.finx.risk.config.RiskPolicies;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import com.finx.risk.repository.BusinessRiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class RiskPolicyService {
    private final BusinessRiskRepository db;
    private final AuditService audit;
    public RiskPolicyService(BusinessRiskRepository db, AuditService audit) { this.db=db; this.audit=audit; }
    public static UUID id(UserType type) { return UUID.nameUUIDFromBytes(("FINX-RISK-"+type).getBytes(StandardCharsets.UTF_8)); }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional public void initialize() {
        for (UserType type : UserType.values()) {
            if (db.query(RiskRule.class,"from RiskRule where userType=:type",Map.of("type",type),1).isEmpty()) {
                RiskRule rule = new RiskRule(); rule.id=id(type); rule.userType=type;
                rule.configuration=db.json(RiskPolicies.defaults(type)); db.persist(rule);
            }
        }
    }
    public RiskRule rule(UserType type) { return db.get(RiskRule.class,id(type)); }
    public Policy policy(UserType type) { return db.read(rule(type).configuration,Policy.class); }
    @Transactional public Map<String,Object> update(UUID id, PolicyUpdate request, UUID actor) {
        RiskRule rule = db.lock(RiskRule.class,id);
        if (rule.version != request.expectedVersion()) throw new BadRequestException("Policy changed; reload before editing");
        try { RiskPolicies.validate(rule.userType,request.policy()); }
        catch (IllegalArgumentException e) { throw new BadRequestException(e.getMessage()); }
        String before=rule.configuration;
        rule.configuration=db.json(request.policy()); rule.updatedAt=Instant.now(); rule.updatedBy=actor;
        db.flush();
        audit.logEvent(actor,"RISK_POLICY_UPDATED","RISK_RULE",id.toString(),db.json(Map.of(
                "previous",db.read(before,Policy.class),"policy",request.policy(),"version",rule.version,"reason",request.reason())));
        // The worker detects version mismatch in bounded batches; no global recalculation in this request.
        return view(rule);
    }
    public Map<String,Object> view(RiskRule rule) {
        return Map.of("id",rule.id,"userType",rule.userType,"version",rule.version,"policy",db.read(rule.configuration,Policy.class),"updatedAt",rule.updatedAt);
    }
}
