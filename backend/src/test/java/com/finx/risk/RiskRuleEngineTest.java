package com.finx.risk;

import com.finx.risk.config.RiskPolicies;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.engine.RiskRuleEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RiskRuleEngineTest {
    private final RiskRuleEngine engine=new RiskRuleEngine();
    private final Instant now=Instant.parse("2026-09-25T00:00:00Z");
    @ParameterizedTest
    @CsvSource({"0,LOW","20,LOW","21,MODERATE","40,MODERATE","41,ELEVATED","60,ELEVATED","61,HIGH","80,HIGH","81,CRITICAL","100,CRITICAL"})
    void boundaries(int score,Level level) { assertEquals(level,RiskRuleEngine.level(score)); }
    @ParameterizedTest @EnumSource(UserType.class)
    void newBusinessAndVerifiedBusiness(UserType type) {
        Policy p=RiskPolicies.defaults(type);
        var fresh=engine.calculate(p,Map.of("kycIncomplete",e(1),"kybIncomplete",e(1)),List.of());
        assertEquals(10,fresh.score()); assertEquals(Action.ALLOW,fresh.action());
        var verified=engine.calculate(p,Map.of("kycIncomplete",e(0),"kybIncomplete",e(0)),fresh.factors());
        assertEquals(0,verified.score());
        assertEquals(-10,verified.factors().stream().mapToDouble(Factor::change).sum());
        assertTrue(verified.unavailableSignals().contains("disputesLost"));
    }
    @ParameterizedTest
    @CsvSource({"BUYER,paymentFailureRatio,1,PAYMENT_FAILURE_RATE_HIGH", "BUYER,disputeRatio,1,DISPUTE_RATIO_HIGH",
            "VENDOR,bankChanges,1,BANK_ACCOUNT_RECENTLY_CHANGED", "BUYER,valueRatio,5,TRANSACTION_VALUE_ANOMALY",
            "VENDOR,rejectionRatio,1,MILESTONE_REJECTION_RATE_HIGH", "BUYER,kycFailed,1,KYC_VERIFICATION_FAILED",
            "VENDOR,kybFailed,1,KYB_VERIFICATION_FAILED", "VENDOR,duplicateInvoices,1,DUPLICATE_INVOICE",
            "BUYER,paymentReversals,1,PAYMENT_REVERSED", "VENDOR,volumeRatio,5,PAYMENT_VOLUME_ANOMALY"})
    void explainsRiskSignals(UserType type,String metric,double value,String code) {
        var result=engine.calculate(RiskPolicies.defaults(type),Map.of(metric,e(value)),List.of());
        assertTrue(result.score()>0);
        assertTrue(result.factors().stream().anyMatch(f->f.code().equals(code)&&f.impact()>0&&f.evidenceReference().equals("test:evidence")&&f.occurredAt().equals(now)));
        assertEquals(result.score(),Math.round(result.factors().stream().mapToDouble(Factor::impact).sum()));
    }
    @ParameterizedTest @EnumSource(UserType.class)
    void capsCategoriesAndTotalWithoutLosingAttribution(UserType type) {
        Policy p=RiskPolicies.defaults(type); Map<String,Evidence> all=new HashMap<>();
        p.rules().forEach(r->all.put(r.metric(),e(10000)));
        var result=engine.calculate(p,all,List.of());
        assertEquals(100,result.score()); assertEquals(Action.TEMPORARY_HOLD,result.action());
        assertEquals(100,result.factors().stream().mapToDouble(Factor::impact).sum(),0.000001);
        assertTrue(result.factorScores().values().stream().allMatch(n->n<=100));
    }
    @Test void weightedConfigurationChangesAndScoreDecrease() {
        Policy initial=RiskPolicies.defaults(UserType.BUYER);
        Map<Category,Integer> weights=new EnumMap<>(initial.weights()); weights.put(Category.PAYMENT,45); weights.put(Category.VERIFICATION,0);
        Policy revised=new Policy(weights,initial.rules(),90,24,5,initial.highValueAmount(),true,true,"INR");
        RiskPolicies.validate(UserType.BUYER,revised);
        var before=engine.calculate(initial,Map.of("paymentFailureRatio",e(1)),List.of());
        var after=engine.calculate(revised,Map.of("paymentFailureRatio",e(1)),before.factors());
        assertEquals(15,before.score()); assertEquals(27,after.score());
        var recovery=engine.calculate(revised,Map.of("paymentFailureRatio",e(0.1)),after.factors());
        assertEquals(0,recovery.score()); assertEquals(-27,recovery.factors().getFirst().change());
    }
    @Test void rejectsInvalidConfigurationAndEvidence() {
        Policy p=RiskPolicies.defaults(UserType.BUYER);
        assertThrows(IllegalArgumentException.class,()->RiskPolicies.validate(UserType.BUYER,new Policy(Map.of(),p.rules(),90,24,5,p.highValueAmount(),true,true,"INR")));
        assertThrows(IllegalArgumentException.class,()->engine.calculate(p,Map.of("kycIncomplete",e(Double.NaN)),List.of()));
        assertThrows(IllegalArgumentException.class,()->RiskRuleEngine.level(101));
    }
    private Evidence e(double value) { return new Evidence(value,"test:evidence",now); }
}
