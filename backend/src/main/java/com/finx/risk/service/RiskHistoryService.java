package com.finx.risk.service;

import com.finx.exception.ResourceNotFoundException;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.RiskFactor;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.entity.RiskScoreHistory;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskAdminService.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated service for accessing immutable risk score history and factor audit trails.
 */
@Service
@Transactional(readOnly = true)
public class RiskHistoryService {

    private final BusinessRiskRepository db;
    private final RiskAdminService adminService;

    public RiskHistoryService(BusinessRiskRepository db, RiskAdminService adminService) {
        this.db = db;
        this.adminService = adminService;
    }

    /**
     * Get paginated immutable history snapshots for an entity.
     */
    public Page<Assessment> getHistory(UUID entityId, int page, int size) {
        RiskScore subject = adminService.resolveSubject(entityId);
        return adminService.history(subject.id, page, size);
    }

    /**
     * Get the latest completed assessment for an entity.
     */
    public Assessment getLatestAssessment(UUID entityId) {
        RiskScore subject = adminService.resolveSubject(entityId);
        Assessment current = db.current(subject);
        if (current == null) {
            var items = adminService.history(subject.id, 0, 1).items();
            if (!items.isEmpty()) return items.getFirst();
            throw new ResourceNotFoundException("RiskAssessment", "subjectId", subject.id);
        }
        return current;
    }

    /**
     * Get an assessment snapshot by its assessment ID.
     */
    public Assessment getAssessmentById(UUID assessmentId) {
        var rows = db.query(RiskScoreHistory.class,
                "from RiskScoreHistory where assessmentId=:id",
                Map.of("id", assessmentId), 1);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("RiskAssessment", "assessmentId", assessmentId);
        }
        return db.read(rows.getFirst().snapshot, Assessment.class);
    }

    /**
     * Get persisted risk factors for an assessment.
     */
    public List<RiskFactor> getFactorsForAssessment(UUID assessmentId) {
        return db.query(RiskFactor.class,
                "from RiskFactor where assessmentId=:id",
                Map.of("id", assessmentId), 100);
    }
}
