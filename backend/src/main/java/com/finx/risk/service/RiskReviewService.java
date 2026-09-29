package com.finx.risk.service;

import com.finx.audit.service.AuditService;
import com.finx.exception.BadRequestException;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import com.finx.risk.repository.BusinessRiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RiskReviewService {
    private final BusinessRiskRepository db;
    private final AuditService audit;
    public RiskReviewService(BusinessRiskRepository db,AuditService audit) { this.db=db; this.audit=audit; }
    @Transactional public RiskReview decide(UUID id,Decision request,UUID reviewer) {
        RiskReview review=db.lock(RiskReview.class,id);
        if (review.version!=request.expectedVersion()) throw new BadRequestException("Review changed; reload before deciding");
        if (review.status==ReviewStatus.CLEARED) throw new BadRequestException("This case is closed; a new assessment creates a new case when evidence changes");
        if (request.decision()==ReviewStatus.OPEN) throw new BadRequestException("Choose IN_REVIEW, CLEARED, RESTRICTED, or ESCALATED");
        if (request.decision()==ReviewStatus.CLEARED && request.action()!=Action.ALLOW && request.action()!=Action.MONITOR)
            throw new BadRequestException("A cleared review must allow or monitor operations");
        if (request.decision()==ReviewStatus.RESTRICTED) {
            if (request.action()!=Action.TEMPORARY_HOLD || request.restrictionUntil()==null
                    || !request.restrictionUntil().isAfter(Instant.now())
                    || request.restrictionUntil().isAfter(Instant.now().plus(30,ChronoUnit.DAYS)))
                throw new BadRequestException("Temporary restrictions require an expiry within 30 days");
        } else if (request.decision()!=ReviewStatus.CLEARED && request.action()!=Action.VERIFY && request.action()!=Action.MANUAL_REVIEW)
            throw new BadRequestException("An open review must request verification or manual review");
        var before=Map.of("status",review.status,"action",review.action,"version",review.version);
        review.status=request.decision(); review.action=request.action(); review.reason=request.reason(); review.notes=request.notes();
        review.evidenceReferences=db.json(request.evidenceReferences()==null?List.of():request.evidenceReferences());
        review.reviewerId=reviewer; review.reviewedAt=Instant.now();
        review.restrictionUntil=request.decision()==ReviewStatus.RESTRICTED?request.restrictionUntil():null;
        if (request.decision()==ReviewStatus.CLEARED) db.get(RiskAlert.class,review.alertId).clearedAt=Instant.now();
        db.flush();
        audit.logEvent(reviewer,"RISK_REVIEW_DECISION","RISK_REVIEW",review.id.toString(),db.json(Map.of(
                "before",before,"decision",request,"subjectId",review.subjectId,"reviewerId",reviewer,"timestamp",review.reviewedAt)));
        return review;
    }
}
