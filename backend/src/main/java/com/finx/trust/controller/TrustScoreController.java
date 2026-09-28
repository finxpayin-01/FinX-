package com.finx.trust.controller;

import com.finx.common.response.ApiResponse;
import com.finx.security.service.UserPrincipal;
import com.finx.trust.dto.TrustResult;
import com.finx.trust.model.*;
import com.finx.trust.repository.*;
import com.finx.trust.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/trust")
public class TrustScoreController {
    private final TrustOrganizationService access;
    private final TrustScoreService scores;
    private final TrustScoreHistoryRepository history;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public TrustScoreController(TrustOrganizationService access,TrustScoreService scores,
            TrustScoreHistoryRepository history,JdbcTemplate jdbc,Clock clock) {
        this.access=access; this.scores=scores; this.history=history; this.jdbc=jdbc; this.clock=clock;
    }
    @GetMapping("/mine")
    public ApiResponse<Map<String,UUID>> mine(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(Map.of("organizationId",access.ensureForUser(user.getId())));
    }
    @GetMapping({"/{id}","/{id}/breakdown"})
    public ApiResponse<TrustResult> score(@PathVariable UUID id,@AuthenticationPrincipal UserPrincipal user) {
        access.requireAccess(id,user); return ApiResponse.success(scores.get(id,TrustRole.OVERALL));
    }
    @GetMapping("/{id}/buyer-score")
    public ApiResponse<TrustResult> buyer(@PathVariable UUID id,@AuthenticationPrincipal UserPrincipal user) {
        access.requireAccess(id,user); return ApiResponse.success(scores.get(id,TrustRole.BUYER));
    }
    @GetMapping("/{id}/vendor-score")
    public ApiResponse<TrustResult> vendor(@PathVariable UUID id,@AuthenticationPrincipal UserPrincipal user) {
        access.requireAccess(id,user); return ApiResponse.success(scores.get(id,TrustRole.VENDOR));
    }
    @GetMapping("/{id}/signals")
    public ApiResponse<Map<String,List<String>>> signals(@PathVariable UUID id,@RequestParam(defaultValue="OVERALL") TrustRole role,
            @AuthenticationPrincipal UserPrincipal user) {
        access.requireAccess(id,user); var result=scores.get(id,role);
        return ApiResponse.success(Map.of("positive",result.positiveSignals(),"negative",result.negativeSignals()));
    }
    public record HistoryPoint(UUID id,double score,double confidence,String level,Instant calculatedAt,String reason,long configVersion) {}
    @GetMapping("/{id}/history")
    public ApiResponse<List<HistoryPoint>> history(@PathVariable UUID id,@RequestParam(defaultValue="90") int days,
            @RequestParam(defaultValue="OVERALL") TrustRole role,@AuthenticationPrincipal UserPrincipal user) {
        access.requireAccess(id,user);
        if(!Set.of(30,90,180).contains(days)) throw new IllegalArgumentException("History period must be 30, 90 or 180 days");
        // Return the last calculation each UTC day: bounded payload without dropping the beginning of the trend.
        var ids=jdbc.queryForList("""
            select id from (select id, calculated_at,
              row_number() over(partition by cast(calculated_at at time zone 'UTC' as date) order by calculated_at desc,id desc) rn
              from trust_score_history where organization_id=? and role=? and calculated_at>=?) h
            where rn=1 order by calculated_at
            """,UUID.class,id,role.name(),java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(days))));
        var records=history.findAllById(ids); records.sort(Comparator.comparing(h->h.calculatedAt));
        return ApiResponse.success(records.stream().map(h->new HistoryPoint(h.id,h.newScore,h.snapshot.confidenceScore(),h.newLevel,h.calculatedAt,h.reason,h.configVersion)).toList());
    }
}
