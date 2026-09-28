package com.finx.trust;

import com.finx.trust.config.TrustRules;
import com.finx.trust.dto.TrustResult;
import com.finx.trust.engine.TrustScoringEngine;
import com.finx.trust.entity.TrustEvent;
import com.finx.trust.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TrustScoringEngineTest {
    static final Instant NOW=Instant.parse("2026-09-28T12:00:00Z");
    static final UUID ORG=UUID.nameUUIDFromBytes("fixture organization".getBytes());
    final TrustRules rules=TrustRules.defaults();
    final TrustScoringEngine engine=new TrustScoringEngine();

    @Test void newAndKybOnlyAccountsAreNeutralAndInsufficient() {
        var e=event(0,TrustEventType.KYB_VERIFIED,TrustRole.OVERALL,0);
        var result=score(List.of(e),TrustRole.OVERALL);
        assertThat(result.overallScore()).isEqualTo(50);
        assertThat(result.trustLevel()).isEqualTo(TrustLevel.INSUFFICIENT_DATA);
        assertThat(result.confidenceScore()).isLessThan(20);
        assertThat(result.positiveSignals()).isEmpty();
    }
    @Test void excellentBuyerAndVendorHaveHighRoleScores() {
        var buyer=score(goodHistory(60,TrustRole.BUYER),TrustRole.BUYER);
        var vendor=score(goodHistory(60,TrustRole.VENDOR),TrustRole.VENDOR);
        assertThat(buyer.overallScore()).isGreaterThan(85);
        assertThat(vendor.overallScore()).isGreaterThan(85);
        assertThat(vendor.confidenceScore()).isGreaterThan(80);
    }
    @Test void latePaymentAndDeliveryReduceTheRelevantFactor() {
        for(var role:List.of(TrustRole.BUYER,TrustRole.VENDOR)) {
            var history=goodHistory(20,role); var good=score(history,role);
            for(int i=0;i<20;i++) history.add(event(i,role==TrustRole.BUYER?TrustEventType.PAYMENT_DELAYED:TrustEventType.MILESTONE_OVERDUE,role,0));
            var late=score(history,role); var factor=role==TrustRole.BUYER?TrustFactor.PAYMENT:TrustFactor.MILESTONE;
            assertThat(late.factors().get(factor).score()).isLessThan(35);
            assertThat(late.overallScore()).isLessThan(good.overallScore());
        }
    }
    @Test void openingDisputeIsNeutralAndWinningNeverAddsFault() {
        var events=goodHistory(12,TrustRole.VENDOR); var before=score(events,TrustRole.VENDOR);
        events.add(event(0,TrustEventType.DISPUTE_CREATED,TrustRole.VENDOR,0));
        var open=score(events,TrustRole.VENDOR);
        assertThat(open.overallScore()).isEqualTo(before.overallScore());
        assertThat(open.statistics().get("unresolvedDisputes")).isEqualTo(1);
        events.add(event(0,TrustEventType.DISPUTE_WON,TrustRole.VENDOR,0));
        var won=score(events,TrustRole.VENDOR);
        assertThat(won.overallScore()).isGreaterThanOrEqualTo(before.overallScore());
        assertThat(won.statistics().get("unresolvedDisputes")).isZero();
    }
    @Test void lossesAndFraudCannotBeHiddenByLargeVolume() {
        var history=goodHistory(100,TrustRole.BUYER);
        for(int i=0;i<100;i++) {
            history.add(event(i,TrustEventType.DISPUTE_LOST,TrustRole.BUYER,0));
            history.add(event(i,TrustEventType.PAYMENT_FAILED,TrustRole.BUYER,0));
        }
        var bad=score(history,TrustRole.BUYER);
        assertThat(bad.overallScore()).isLessThan(60);
        history.add(event(999,TrustEventType.FRAUD_CONFIRMED,TrustRole.OVERALL,900));
        var fraud=score(history,TrustRole.BUYER);
        assertThat(fraud.overallScore()).isLessThanOrEqualTo(20);
        assertThat(fraud.trustLevel()).isEqualTo(TrustLevel.HIGH_RISK);
    }
    @Test void oldOrdinaryNegativeEvidenceDecaysTowardsNeutral() {
        var recent=goodHistory(12,TrustRole.BUYER); var old=goodHistory(12,TrustRole.BUYER);
        for(int i=0;i<12;i++) {
            recent.add(event(i,TrustEventType.PAYMENT_FAILED,TrustRole.BUYER,0));
            old.add(event(i,TrustEventType.PAYMENT_FAILED,TrustRole.BUYER,900));
        }
        assertThat(score(old,TrustRole.BUYER).factors().get(TrustFactor.PAYMENT).score())
            .isGreaterThan(score(recent,TrustRole.BUYER).factors().get(TrustFactor.PAYMENT).score());
    }
    @Test void tinySelfDealingAndConcentratedTransactionsDoNotEstablishTrust() {
        for(int mode=0;mode<3;mode++) {
            var history=goodHistory(100,TrustRole.VENDOR);
            for(var e:history) {
                if(mode==0) e.eventValue=new BigDecimal("0.01");
                if(mode==1) e.counterpartyId=ORG;
                if(mode==2) e.counterpartyId=UUID.nameUUIDFromBytes("one counterparty".getBytes());
            }
            assertThat(score(history,TrustRole.VENDOR).trustLevel()).isEqualTo(TrustLevel.INSUFFICIENT_DATA);
        }
    }
    @Test void repeatedDocumentsCannotIncreaseScoreOrConfidence() {
        var events=goodHistory(12,TrustRole.VENDOR); var before=score(events,TrustRole.VENDOR);
        for(int i=0;i<1000;i++) events.add(event(0,TrustEventType.DOCUMENT_VERIFIED,TrustRole.VENDOR,0));
        var after=score(events,TrustRole.VENDOR);
        assertThat(after.overallScore()).isEqualTo(before.overallScore());
        assertThat(after.confidenceScore()).isEqualTo(before.confidenceScore());
    }
    @Test void buyerAndVendorHistoryAreIsolated() {
        var events=goodHistory(20,TrustRole.VENDOR);
        for(int i=0;i<20;i++) events.add(event(i,TrustEventType.PAYMENT_FAILED,TrustRole.BUYER,0));
        assertThat(score(events,TrustRole.VENDOR).factors().get(TrustFactor.PAYMENT).observations()).isZero();
    }
    @Test void lowVolumeExcellentBehaviourHasLowerConfidenceThanLongHistory() {
        var low=score(goodHistory(3,TrustRole.VENDOR),TrustRole.VENDOR);
        var high=score(goodHistory(60,TrustRole.VENDOR),TrustRole.VENDOR);
        assertThat(low.confidenceScore()).isLessThan(high.confidenceScore());
        assertThat(low.trustLevel()).isNotEqualTo(TrustLevel.INSUFFICIENT_DATA);
    }
    @Test void resultsAreReproducibleAndMissingFactorsRemainNeutral() {
        var events=goodHistory(12,TrustRole.VENDOR);
        assertThat(score(events,TrustRole.VENDOR)).isEqualTo(score(events,TrustRole.VENDOR));
        assertThat(score(events,TrustRole.VENDOR).factors().get(TrustFactor.PAYMENT).score()).isEqualTo(50);
        rules.validate();
    }
    @Test void futureEventsAreExcluded() {
        var future=event(0,TrustEventType.FRAUD_CONFIRMED,TrustRole.OVERALL,-1);
        assertThat(score(List.of(future),TrustRole.OVERALL).dataPoints()).isZero();
    }
    @Test void backdatedFactsReceivedAfterTheCalculationAreExcludedFromReplay() {
        var lateArrival=event(0,TrustEventType.FRAUD_CONFIRMED,TrustRole.OVERALL,10);
        lateArrival.createdAt=NOW.plusSeconds(1);
        assertThat(score(List.of(lateArrival),TrustRole.OVERALL).dataPoints()).isZero();
    }
    @Test void unknownDeadlineIsNeverClaimedToBeOnTime() {
        var submission=event(0,TrustEventType.MILESTONE_SUBMITTED,TrustRole.VENDOR,0);
        submission.metadata=Map.of("deadlineKnown",false);
        assertThat(score(List.of(submission),TrustRole.VENDOR).factors().get(TrustFactor.MILESTONE).observations()).isZero();
    }
    @Test void invalidWeightsCannotDisableSupportingFactorLimits() {
        var invalid=TrustRules.defaults();
        invalid.weights().get(TrustRole.BUYER).put(TrustFactor.NETWORK,.5);
        assertThatThrownBy(invalid::validate).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @CsvSource({"0,HIGH_RISK","39.99,HIGH_RISK","40,ELEVATED_RISK","59.99,ELEVATED_RISK","60,MODERATE","74.99,MODERATE","75,TRUSTED","89.99,TRUSTED","90,HIGHLY_TRUSTED","100,HIGHLY_TRUSTED"})
    void levelsHavePreciseBoundaries(double score,TrustLevel level) { assertThat(TrustLevel.of(score,true)).isEqualTo(level); }

    @Test void sampleVendorCalculation() {
        var data=goodHistory(12,TrustRole.VENDOR);
        for(int i=12;i<48;i++) {
            var completed=event(i,TrustEventType.MILESTONE_COMPLETED,TrustRole.VENDOR,0);
            completed.dealId=UUID.nameUUIDFromBytes(("deal "+i%12).getBytes());
            data.add(completed);
            var evidence=event(i,TrustEventType.DOCUMENT_VERIFIED,TrustRole.VENDOR,0); evidence.dealId=completed.dealId; data.add(evidence);
        }
        for(int i=0;i<3;i++) data.add(event(i,TrustEventType.MILESTONE_OVERDUE,TrustRole.VENDOR,0));
        for(int i=3;i<5;i++) data.add(event(i,TrustEventType.MILESTONE_REJECTED,TrustRole.VENDOR,0));
        data.removeIf(e->e.eventType==TrustEventType.DOCUMENT_VERIFIED && e.milestoneId.equals(event(47,TrustEventType.DOCUMENT_VERIFIED,TrustRole.VENDOR,0).milestoneId));
        var rejected=event(47,TrustEventType.DOCUMENT_REJECTED,TrustRole.VENDOR,0);
        rejected.dealId=event(11,TrustEventType.DEAL_COMPLETED,TrustRole.VENDOR,0).dealId;
        data.add(rejected);
        data.add(event(0,TrustEventType.DISPUTE_CREATED,TrustRole.VENDOR,0));
        data.add(event(0,TrustEventType.DISPUTE_WON,TrustRole.VENDOR,0));
        // Twelve completed deals across five repeat buyers; 47/48 accepted documents rounds to 98%.
        for(var e:data) {
            int dealNumber=java.util.stream.IntStream.range(0,12).filter(i->event(i,TrustEventType.DEAL_COMPLETED,TrustRole.VENDOR,0).dealId.equals(e.dealId)).findFirst().orElseThrow();
            e.counterpartyId=UUID.nameUUIDFromBytes(("sample repeat buyer "+dealNumber%5).getBytes());
        }
        var result=score(data,TrustRole.VENDOR);
        System.out.println("TRUST_SAMPLE_VENDOR="+result);
        assertThat(result.overallScore()).isBetween(75d,100d);
    }
    TrustResult score(List<TrustEvent> data,TrustRole role) {
        return engine.calculate(ORG,role,NOW.minus(Duration.ofDays(400)),NOW,1,rules,
                data.stream().sorted(Comparator.comparing((TrustEvent e)->e.occurredAt).thenComparing(e->e.id)));
    }
    static List<TrustEvent> goodHistory(int deals,TrustRole role) {
        var result=new ArrayList<TrustEvent>();
        for(int i=0;i<deals;i++) {
            var deal=event(i,TrustEventType.DEAL_COMPLETED,role,0); deal.milestoneId=null; result.add(deal);
            result.add(event(i,role==TrustRole.BUYER?TrustEventType.PAYMENT_COMPLETED:TrustEventType.MILESTONE_COMPLETED,role,0));
            result.add(event(i,role==TrustRole.BUYER?TrustEventType.MILESTONE_APPROVED:TrustEventType.DOCUMENT_VERIFIED,role,0));
        }
        return result;
    }
    static TrustEvent event(int n,TrustEventType type,TrustRole role,int age) {
        var e=new TrustEvent(); e.id=UUID.nameUUIDFromBytes((n+":"+type+":"+age).getBytes());
        e.organizationId=ORG; e.counterpartyId=UUID.nameUUIDFromBytes(("counterparty "+n%10).getBytes());
        e.dealId=UUID.nameUUIDFromBytes(("deal "+n).getBytes()); e.milestoneId=UUID.nameUUIDFromBytes(("milestone "+n).getBytes());
        e.role=role; e.eventType=type; e.eventValue=new BigDecimal("25000"); e.currency="INR"; e.severity=1;
        e.occurredAt=NOW.minus(Duration.ofDays(age)); e.metadata=Map.of("deadlineKnown",true,"elapsedSeconds",86400,"disputeId","dispute-"+n);
        e.source="TEST_FIXTURE"; e.sourceKey="test:"+e.id; e.createdAt=e.occurredAt; return e;
    }
}
