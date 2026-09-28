package com.finx.trust.service;

import com.finx.trust.entity.TrustManualReview;
import com.finx.trust.repository.*;
import com.finx.audit.service.AuditService;
import com.finx.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class TrustReviewService {
    private final TrustManualReviewRepository reviews;
    private final TrustOrganizationRepository organizations;
    private final AuditService audit;
    private final Clock clock;
    public TrustReviewService(TrustManualReviewRepository reviews,TrustOrganizationRepository organizations,AuditService audit,Clock clock) {
        this.reviews=reviews; this.organizations=organizations; this.audit=audit; this.clock=clock;
    }
    @Transactional
    public TrustManualReview create(UUID org,UUID admin,String reason) {
        organizations.lock(org).orElseThrow(()->new ResourceNotFoundException("Organization","id",org));
        var review=new TrustManualReview(); review.id=UUID.randomUUID(); review.organizationId=org;
        review.adminId=admin; review.reason=reason; review.status="OPEN"; review.createdAt=clock.instant();
        reviews.save(review); audit.logEvent(admin,"TRUST_REVIEW_CREATED","TRUST_REVIEW",review.id.toString(),reason);
        return review;
    }
    @Transactional
    public TrustManualReview resolve(UUID id,UUID admin,String reason) {
        var review=reviews.lock(id).orElseThrow(()->new ResourceNotFoundException("Trust review","id",id));
        organizations.lock(review.organizationId).orElseThrow();
        if(!review.status.equals("OPEN")) throw new BadRequestException("Review is already resolved");
        review.status="RESOLVED"; review.resolvedBy=admin; review.resolution=reason; review.resolvedAt=clock.instant();
        audit.logEvent(admin,"TRUST_REVIEW_RESOLVED","TRUST_REVIEW",id.toString(),reason); return review;
    }
}
