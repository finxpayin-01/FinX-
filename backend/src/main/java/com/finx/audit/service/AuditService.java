package com.finx.audit.service;

import com.finx.audit.entity.AuditLog;
import com.finx.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final org.springframework.context.ApplicationEventPublisher events;

    public AuditService(AuditLogRepository auditLogRepository, org.springframework.context.ApplicationEventPublisher events) {
        this.auditLogRepository = auditLogRepository;
        this.events = events;
    }

    @Transactional
    public AuditLog logEvent(UUID actorUserId, String action, String entityType, String entityId, String metadata) {
        try {
            AuditLog auditLog = new AuditLog(actorUserId, action, entityType, entityId, metadata);
            AuditLog saved = auditLogRepository.save(auditLog);
            if (!action.startsWith("RISK_") && entityId != null) {
                UUID sourceId = null;
                try { sourceId = UUID.fromString(entityId); } catch (IllegalArgumentException ignored) { }
                if (sourceId != null) events.publishEvent(new com.finx.risk.event.RiskDomainEvent(
                        saved.getId(), action, entityType, sourceId, java.time.Instant.now()));
            }
            log.info("AUDIT_EVENT: action=[{}] entityType=[{}] entityId=[{}] actor=[{}]",
                    action, entityType, entityId, actorUserId);
            return saved;
        } catch (Exception e) {
            log.error("Failed to persist audit log for action: {}", action, e);
            throw new IllegalStateException("Audit persistence failed; financial operation aborted", e);
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<AuditLog> getRecentAuditLogs() {
        return auditLogRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordLoginFailure(UUID userId) {
        logEvent(userId, "FAILED_LOGIN_ATTEMPT", "USER", userId.toString(), "Authentication attempt failed");
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordInvoiceGate(UUID actor, UUID invoice, UUID deal, UUID milestone, boolean allowed) {
        logEvent(actor, "INVOICE_PAYMENT_GATE_CHECKED", "INVOICE", invoice==null?null:invoice.toString(),
                "deal="+deal+";milestone="+milestone+";allowed="+allowed);
    }
}
