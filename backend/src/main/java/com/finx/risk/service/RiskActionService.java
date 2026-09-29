package com.finx.risk.service;

import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import com.finx.risk.repository.BusinessRiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class RiskActionService {
    private final RiskEventService events;
    private final RiskScoringService scoring;
    private final RiskPolicyService policies;
    private final BusinessRiskRepository db;
    public RiskActionService(RiskEventService events,RiskScoringService scoring,RiskPolicyService policies,BusinessRiskRepository db) {
        this.events=events; this.scoring=scoring; this.policies=policies; this.db=db;
    }
    // Commit the review case even when the caller subsequently returns a payout-in-review response.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<UUID> payoutReviews(UUID buyer,UUID buyerOrg,UUID vendor,UUID vendorOrg,BigDecimal amount,String currency) {
        List<UUID> required=new ArrayList<>();
        check(buyer,buyerOrg,UserType.BUYER,amount,currency,required);
        if (vendor!=null) check(vendor,vendorOrg,UserType.VENDOR,amount,currency,required);
        return required;
    }
    private void check(UUID user,UUID org,UserType type,BigDecimal amount,String currency,List<UUID> required) {
        RiskScore subject=events.subject(user,org,type);
        Assessment assessment=scoring.recalculate(subject.id,"PAYOUT_AUTHORIZATION",null);
        Policy policy=policies.policy(type);
        boolean highValue=!policy.currency().equals(currency) || amount.compareTo(policy.highValueAmount())>=0;
        var reviews=db.query(RiskReview.class,"from RiskReview where subjectId=:id and status<>:cleared order by createdAt desc",
                Map.of("id",subject.id,"cleared",ReviewStatus.CLEARED),1000);
        for (RiskReview review:reviews) {
            boolean restriction=review.status==ReviewStatus.RESTRICTED && review.restrictionUntil!=null && review.restrictionUntil.isAfter(Instant.now());
            boolean policyReview=review.status!=ReviewStatus.RESTRICTED && ((assessment.riskLevel()==Level.CRITICAL && policy.holdCriticalPayouts())
                    || (policy.reviewHighValuePayouts() && highValue));
            if (restriction || policyReview) required.add(review.id);
        }
    }
}
