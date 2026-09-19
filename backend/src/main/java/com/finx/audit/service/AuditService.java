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

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public AuditLog logEvent(UUID actorUserId, String action, String entityType, String entityId, String metadata) {
        try {
            AuditLog auditLog = new AuditLog(actorUserId, action, entityType, entityId, metadata);
            AuditLog saved = auditLogRepository.save(auditLog);
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
}
