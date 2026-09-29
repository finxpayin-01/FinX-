package com.finx.risk.service;

import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.RiskScore;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RiskEvidenceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final com.finx.risk.repository.BusinessRiskRepository db;
    public RiskEvidenceService(NamedParameterJdbcTemplate jdbc,com.finx.risk.repository.BusinessRiskRepository db) { this.jdbc = jdbc; this.db=db; }

    public record Snapshot(Map<String,Evidence> metrics, String verificationStatus, double volume) {}

    public Snapshot collect(RiskScore subject, Policy policy, Instant now) {
        Map<String,Object> p = parameters(subject,policy,now);
        Map<String,Evidence> e = new LinkedHashMap<>();
        String deal = dealScope(subject);
        String project = " from deals d where " + deal;
        double projects = number("select count(*)" + project + " and d.created_at>=:since",p);
        add(e,"projectVelocity",number("select count(*)" + project + " and d.created_at>=:velocity",p),"deals",subject,now);
        add(e,"highValueVelocity",number("select count(*)" + project + " and d.created_at>=:velocity and d.currency=:currency and d.total_amount>=:high",p),"deals",subject,now);
        add(e,"cancellationRatio",ratio(number("select count(*)" + project + " and d.created_at>=:since and d.status='CANCELLED'",p),projects),"deals",subject,now);
        double mature = number("select count(*)" + project + " and d.created_at<:since and d.created_at>=:baseline",p);
        add(e,"incompleteRatio",ratio(number("select count(*)" + project + " and d.created_at<:since and d.created_at>=:baseline and d.status not in ('COMPLETED','CANCELLED','REJECTED')",p),mature),"deals",subject,now);
        double baselineCount = number("select count(*)" + project + " and d.created_at<:velocity and d.currency=:currency",p);
        if (baselineCount >= policy.minimumBaselineProjects()) {
            double average = number("select coalesce(avg(d.total_amount),0)" + project + " and d.created_at<:velocity and d.currency=:currency",p);
            double maximum = number("select coalesce(max(d.total_amount),0)" + project + " and d.created_at>=:velocity and d.currency=:currency",p);
            if (average > 0) add(e,"valueRatio",maximum/average,"deals",subject,now);
        }

        String disputes = " from disputes x join deals d on d.id=x.deal_id where " + deal + " and x.created_at>=:since";
        add(e,"disputeRatio",ratio(number("select count(distinct x.deal_id)" + disputes,p),
                number("select count(*)" + project + " and (d.created_at>=:since or exists (select 1 from disputes x where x.deal_id=d.id and x.created_at>=:since))",p)),"disputes",subject,now);
        add(e,"disputeVelocity",number("select count(*)" + disputes + " and x.created_at>=:velocity",p),"disputes",subject,now);
        String counterparty = subject.userType == UserType.BUYER ? "coalesce(d.seller_organization_id,d.seller_id)" : "coalesce(d.buyer_organization_id,d.buyer_id)";
        add(e,"disputeCounterparties",number("select count(distinct " + counterparty + ")" + disputes,p),"disputes",subject,now);

        String milestones = " from milestones m join deals d on d.id=m.deal_id where " + deal;
        String submissions = " from deliverables x join milestones m on m.id=x.milestone_id join deals d on d.id=m.deal_id where " + deal + " and x.submitted_at>=:since";
        double submitted = number("select count(*)" + submissions,p);
        add(e,"rejectionRatio",ratio(number("select count(*)" + submissions + " and x.status='REJECTED'",p),submitted),"deliverables",subject,now);
        double distinctMilestones = number("select count(distinct x.milestone_id)" + submissions,p);
        add(e,"resubmissionRatio",ratio(Math.max(0,submitted-distinctMilestones),submitted),"deliverables",subject,now);
        add(e,"lateRatio",ratio(number("select count(*)" + milestones + " and coalesce(m.submission_deadline,m.due_date)>=:since and coalesce(m.submission_deadline,m.due_date)<:now and (m.status not in ('APPROVED','COMPLETED') or m.updated_at>coalesce(m.submission_deadline,m.due_date))",p),
                number("select count(*)" + milestones + " and coalesce(m.submission_deadline,m.due_date)>=:since and coalesce(m.submission_deadline,m.due_date)<:now",p)),"milestones",subject,now);
        double historicMilestones = number("select count(*)" + milestones + " and m.created_at<:velocity and m.currency=:currency",p);
        if (historicMilestones >= policy.minimumBaselineProjects()) {
            double avg = number("select coalesce(avg(m.amount),0)" + milestones + " and m.created_at<:velocity and m.currency=:currency",p);
            if (avg > 0) add(e,"milestoneValueRatio",number("select coalesce(max(m.amount),0)" + milestones + " and m.created_at>=:velocity and m.currency=:currency",p)/avg,"milestones",subject,now);
        }

        // Both fiat rails contribute, without counting provider transaction retries as separate payments.
        String payments = " from (select deal_id,amount,currency,cast(status as varchar) as status,created_at from payments union all select deal_id,amount,currency,cast(status as varchar) as status,created_at from payment_instructions) x join deals d on d.id=x.deal_id where " + deal;
        double attempts = number("select count(*)" + payments + " and x.created_at>=:since",p);
        add(e,"paymentFailureRatio",ratio(number("select count(*)" + payments + " and x.created_at>=:since and x.status='FAILED'",p),attempts),"payments",subject,now);
        add(e,"paymentFailures",number("select count(*)" + payments + " and x.created_at>=:velocity and x.status='FAILED'",p),"payments",subject,now);
        add(e,"paymentReversals",number("select count(*)" + payments + " and x.created_at>=:since and x.status='REVERSED'",p),"payments",subject,now);
        add(e,"paymentVelocity",number("select count(*)" + payments + " and x.created_at>=:velocity",p),"payments",subject,now);
        add(e,"delayedFundingRatio",ratio(number("select count(*)" + payments + " and x.created_at<:since and x.created_at>=:baseline and x.status in ('PENDING','CREATED','SUBMITTED','PROCESSING')",p),
                number("select count(*)" + payments + " and x.created_at<:since and x.created_at>=:baseline",p)),"payments",subject,now);
        double volume = number("select coalesce(sum(x.amount),0)" + payments + " and x.created_at>=:since and x.status='SUCCESS' and x.currency=:currency",p);
        double baselineVolume = number("select coalesce(sum(x.amount),0)" + payments + " and x.created_at>=:baseline and x.created_at<:since and x.status='SUCCESS' and x.currency=:currency",p);
        double baselinePayments = number("select count(*)" + payments + " and x.created_at>=:baseline and x.created_at<:since and x.status='SUCCESS' and x.currency=:currency",p);
        if (baselineVolume > 0 && baselinePayments >= policy.minimumBaselineProjects()) add(e,"volumeRatio",volume/baselineVolume,"payments",subject,now);

        List<Map<String,Object>> kyc = jdbc.queryForList("select status,name_match_status,expires_at from kyc_profiles where user_id=:user",p);
        List<Map<String,Object>> kyb = subject.organizationId == null ? List.of() : jdbc.queryForList("select status,name_match_status,expires_at,incorporation_date,pan_status,gst_status,registration_status from kyb_profiles where organization_id=:org",p);
        String kycStatus = status(kyc), kybStatus = status(kyb);
        add(e,"kycIncomplete",incomplete(kycStatus),"kyc_profiles",subject,now);
        add(e,"kybIncomplete",incomplete(kybStatus),"kyb_profiles",subject,now);
        add(e,"kycFailed",failed(kycStatus),"kyc_profiles",subject,now);
        add(e,"kybFailed",failed(kybStatus),"kyb_profiles",subject,now);
        add(e,"documentMismatch",mismatch(kyc)||mismatch(kyb)?1:0,"verification",subject,now);
        add(e,"verificationExpired",expired(kyc,now)||expired(kyb,now)?1:0,"verification",subject,now);
        if (subject.organizationId != null) {
            add(e,"bankFailed",number("select count(*) from organization_bank_accounts where organization_id=:org and (verification_status='FAILED' or name_match_status='MISMATCH')",p),"organization_bank_accounts",subject,now);
            var orgs = jdbc.queryForList("select legal_name,registration_number,tax_id,registered_address,gstin from organizations where id=:org",p);
            if (!orgs.isEmpty()) {
                var org = orgs.getFirst();
                long missing = List.of("legal_name","registration_number","tax_id","registered_address").stream().filter(k -> org.get(k)==null || org.get(k).toString().isBlank()).count();
                add(e,"profileMissingRatio",missing/4d,"organizations",subject,now);
                if (!kyb.isEmpty()) {
                    var b = kyb.getFirst();
                    boolean unverified = !"VERIFIED".equals(b.get("pan_status"))
                            || (org.get("gstin")!=null && !"VERIFIED".equals(b.get("gst_status")))
                            || (org.get("registration_number")!=null && !"VERIFIED".equals(b.get("registration_status")));
                    add(e,"identifiersUnverified",unverified?1:0,"kyb_profiles",subject,now);
                    Object date = b.get("incorporation_date");
                    if (date != null) {
                        LocalDate incorporated = LocalDate.parse(date.toString());
                        add(e,"youngCompany",incorporated.isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(policy.windowDays()))?1:0,"kyb_profiles",subject,now);
                    }
                }
            }
        }
        add(e,"bankChanges",events(subject,EventType.BANK_ACCOUNT_CHANGED,p),"risk_events",subject,now);
        Map<String,Object> securityArgs=new HashMap<>(p); securityArgs.put("subject",subject.id);
        add(e,"authFailures",number("select count(*) from risk_events e join audit_logs a on a.id=e.audit_log_id where e.subject_id=:subject and e.occurred_at>=:velocity and a.action='FAILED_LOGIN_ATTEMPT'",securityArgs),"audit_logs",subject,now);
        add(e,"securityEvents",number("select count(*) from risk_events e left join audit_logs a on a.id=e.audit_log_id where e.subject_id=:subject and e.occurred_at>=:velocity and e.event_type='SECURITY_EVENT' and (a.id is null or a.action<>'FAILED_LOGIN_ATTEMPT')",securityArgs),"risk_events",subject,now);
        // Dispute outcomes, device telemetry and invoice contents are deliberately not inferred from free text.
        add(e,"vendorInvitations",number("select count(*)" + project + " and d.created_at>=:velocity and d.seller_id is null",p),"deals",subject,now);
        add(e,"invoiceVelocity",events(subject,EventType.INVOICE_UPLOADED,p),"risk_events",subject,now);
        jdbc.query("select id,safe_evidence,occurred_at from risk_events where subject_id=:subject and safe_evidence is not null and occurred_at>=:since and occurred_at<=:now order by occurred_at asc",
                securityArgs,(org.springframework.jdbc.core.RowCallbackHandler) row->{
            var safe=db.read(row.getString("safe_evidence"),com.finx.risk.event.RiskSignalObserved.SafeEvidence.class);
            Instant occurredAt=row.getTimestamp("occurred_at").toInstant();
            String reference="risk-event:"+row.getObject("id")+";source:"+safe.reference();
            safe.values().forEach((metric,value)->{
                if(metric==com.finx.risk.event.RiskSignalObserved.Metric.NEW_DEVICE_HIGH_VALUE && occurredAt.isBefore(now.minus(policy.velocityHours(),ChronoUnit.HOURS))) return;
                Evidence prior=e.get(metric.metric);
                e.put(metric.metric,new Evidence(value+(prior==null?0:prior.value()),reference,occurredAt));
            });
        });
        return new Snapshot(e,kybStatus,volume);
    }

    private double events(RiskScore s, EventType type, Map<String,Object> p) {
        Map<String,Object> args = new HashMap<>(p); args.put("event",type.name()); args.put("subject",s.id);
        return number("select count(*) from risk_events where subject_id=:subject and event_type=:event and occurred_at>=:velocity",args);
    }
    public String dealScope(RiskScore s) {
        String side = s.userType == UserType.BUYER ? "buyer" : "seller";
        return s.organizationId == null ? "d."+side+"_id=:user and d."+side+"_organization_id is null"
                : "d."+side+"_organization_id=:org";
    }
    public Map<String,Object> parameters(RiskScore s, Policy p, Instant now) {
        Map<String,Object> args = new HashMap<>();
        args.put("user",s.userId); args.put("org",s.organizationId); args.put("currency",p.currency());
        args.put("since",Timestamp.from(now.minus(p.windowDays(),ChronoUnit.DAYS)));
        args.put("baseline",Timestamp.from(now.minus(p.windowDays()*2L,ChronoUnit.DAYS)));
        args.put("velocity",Timestamp.from(now.minus(p.velocityHours(),ChronoUnit.HOURS)));
        args.put("now",Timestamp.from(now)); args.put("high",p.highValueAmount());
        return args;
    }
    private double number(String sql, Map<String,?> p) { Number n = jdbc.queryForObject(sql,p,Number.class); return n == null ? 0 : n.doubleValue(); }
    private double ratio(double n,double d) { return d == 0 ? 0 : Math.min(1,n/d); }
    private void add(Map<String,Evidence> e,String metric,double value,String table,RiskScore s,Instant at) { e.put(metric,new Evidence(value,table+":"+(s.organizationId==null?s.userId:s.organizationId),at)); }
    private String status(List<Map<String,Object>> rows) { return rows.isEmpty()?"NOT_STARTED":String.valueOf(rows.getFirst().get("status")); }
    private int incomplete(String status) { return Set.of("VERIFIED","FAILED","REJECTED","EXPIRED").contains(status)?0:1; }
    private int failed(String status) { return Set.of("FAILED","REJECTED").contains(status)?1:0; }
    private boolean mismatch(List<Map<String,Object>> rows) { return !rows.isEmpty() && "MISMATCH".equals(String.valueOf(rows.getFirst().get("name_match_status"))); }
    private boolean expired(List<Map<String,Object>> rows,Instant now) {
        if (rows.isEmpty()) return false;
        Object at = rows.getFirst().get("expires_at");
        return "EXPIRED".equals(status(rows)) || at instanceof Timestamp t && t.toInstant().isBefore(now)
                || at instanceof OffsetDateTime d && d.toInstant().isBefore(now);
    }
}
