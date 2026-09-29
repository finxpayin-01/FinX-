package com.finx.risk.service;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskSignal;
import com.finx.risk.enums.RiskEnums.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Compatibility view for deal inspection; business assessments are the authoritative history. */
@Service
public class RiskEngineService {
    private final DealRepository deals;
    private final RiskEventService subjects;
    private final RiskScoringService scoring;
    public RiskEngineService(DealRepository deals,RiskEventService subjects,RiskScoringService scoring) {
        this.deals=deals; this.subjects=subjects; this.scoring=scoring;
    }
    @Transactional public RiskAssessment assessDealRisk(UUID dealId) {
        Deal deal=deals.findById(dealId).orElseThrow(()->new ResourceNotFoundException("Deal","id",dealId));
        List<Assessment> assessments=new ArrayList<>();
        assessments.add(scoring.recalculate(subjects.subject(deal.getBuyerId(),deal.getBuyerOrganizationId(),UserType.BUYER).id,"DEAL_INSPECTION",null));
        if(deal.getSellerId()!=null) assessments.add(scoring.recalculate(subjects.subject(deal.getSellerId(),deal.getSellerOrganizationId(),UserType.VENDOR).id,"DEAL_INSPECTION",null));
        Assessment highest=assessments.stream().max(Comparator.comparingInt(Assessment::totalRiskScore)).orElseThrow();
        RiskAssessment view=new RiskAssessment(RiskEntityType.DEAL,dealId,RiskLevel.valueOf(highest.riskLevel().name()),highest.totalRiskScore(),
                "Maximum of the current buyer and vendor business scores. Payouts follow configured review policy.",!highest.reviewRequired());
        view.ruleVersion=highest.ruleVersion();
        view.setSignals(assessments.stream().flatMap(a->a.topRiskFactors().stream().filter(f->f.impact()>0)
                .map(f->new RiskSignal(view.getId(),f.code(),RiskLevel.valueOf(a.riskLevel().name()),a.userType()+": "+f.reason()+" ("+f.impact()+" points)"))).toList());
        return view;
    }
    @Transactional public RiskAssessment getAssessmentForDeal(UUID dealId) { return assessDealRisk(dealId); }
}
