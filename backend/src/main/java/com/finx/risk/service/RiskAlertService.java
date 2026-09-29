package com.finx.risk.service;

import com.finx.audit.service.AuditService;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.RiskAlert;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskAdminService.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated service for managing Risk Alerts and notifications.
 */
@Service
public class RiskAlertService {

    private final BusinessRiskRepository db;
    private final RiskAdminService adminService;
    private final AuditService audit;

    public RiskAlertService(BusinessRiskRepository db, RiskAdminService adminService, AuditService audit) {
        this.db = db;
        this.adminService = adminService;
        this.audit = audit;
    }

    /**
     * Get paginated alerts, either open-only or all.
     */
    @Transactional(readOnly = true)
    public Page<RiskAlert> getAlerts(boolean open, int page, int size) {
        return adminService.alerts(open, page, size);
    }

    /**
     * Get all alerts for a specific subject entity.
     */
    @Transactional(readOnly = true)
    public List<RiskAlert> getAlertsForSubject(UUID subjectId) {
        return db.query(RiskAlert.class,
                "from RiskAlert where subjectId=:id order by createdAt desc",
                Map.of("id", subjectId), 100);
    }

    /**
     * Create a new RiskAlert.
     */
    @Transactional
    public RiskAlert createAlert(UUID subjectId, UUID assessmentId, Level severity, String reason) {
        RiskAlert alert = new RiskAlert();
        alert.subjectId = subjectId;
        alert.assessmentId = assessmentId;
        alert.severity = severity;
        alert.reason = reason;
        alert.createdAt = Instant.now();
        db.persist(alert);
        return alert;
    }

    /**
     * Clear an active risk alert.
     */
    @Transactional
    public void clearAlert(UUID alertId, UUID actorUserId) {
        RiskAlert alert = db.lock(RiskAlert.class, alertId);
        if (alert.clearedAt == null) {
            alert.clearedAt = Instant.now();
            audit.logEvent(actorUserId, "RISK_ALERT_CLEARED", "RISK_ALERT", alertId.toString(),
                    db.json(Map.of("alertId", alertId, "subjectId", alert.subjectId, "clearedAt", alert.clearedAt)));
        }
    }
}
