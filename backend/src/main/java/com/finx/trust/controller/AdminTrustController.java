package com.finx.trust.controller;

import com.finx.common.response.ApiResponse;
import com.finx.security.service.UserPrincipal;
import com.finx.trust.config.TrustRules;
import com.finx.trust.entity.*;
import com.finx.trust.dto.TrustResult;
import com.finx.trust.model.TrustRole;
import com.finx.trust.repository.*;
import com.finx.trust.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/trust")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTrustController {
    private final TrustScoreService scores;
    private final TrustConfigService configs;
    private final TrustOrganizationRepository organizations;
    private final TrustEventRepository events;
    private final TrustManualReviewRepository reviews;
    private final TrustScoreHistoryRepository history;
    private final TrustReviewService reviewService;
    public AdminTrustController(TrustScoreService scores,TrustConfigService configs,TrustOrganizationRepository organizations,
            TrustEventRepository events,TrustManualReviewRepository reviews,TrustReviewService reviewService,TrustScoreHistoryRepository history) {
        this.scores=scores; this.configs=configs; this.organizations=organizations; this.events=events;
        this.reviews=reviews; this.reviewService=reviewService; this.history=history;
    }
    public record Reason(@NotBlank @Size(min=10,max=1000) String reason) {}
    public record ConfigUpdate(@NotNull TrustRules rules,@Min(0) long expectedVersion,@NotBlank @Size(min=10,max=1000) String reason) {}
    @GetMapping("/organizations")
    public ApiResponse<List<TrustOrganization>> search(@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page) {
        if(query.length()>100) throw new IllegalArgumentException("Search is too long");
        return ApiResponse.success(organizations.search(query,boundedPage(page)));
    }
    @PostMapping("/{id}/recalculate")
    public ApiResponse<TrustResult> recalculate(@PathVariable UUID id,@Valid @RequestBody Reason body,@AuthenticationPrincipal UserPrincipal user) {
        scores.recalculate(id,user.getId(),body.reason(),true); return ApiResponse.success(scores.get(id,TrustRole.OVERALL));
    }
    @GetMapping("/config") public ApiResponse<TrustScoringConfig> config() { return ApiResponse.success(configs.current()); }
    @PutMapping("/config")
    public ApiResponse<TrustScoringConfig> update(@Valid @RequestBody ConfigUpdate body,@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(configs.update(body.rules(),body.expectedVersion(),user.getId(),body.reason()));
    }
    @GetMapping("/{id}/events")
    public ApiResponse<List<TrustEvent>> events(@PathVariable UUID id,@RequestParam(defaultValue="0") int page) {
        return ApiResponse.success(events.findByOrganizationIdOrderByOccurredAtDesc(id,boundedPage(page)));
    }
    @GetMapping("/calculations/{id}")
    public ApiResponse<TrustScoreHistory> calculation(@PathVariable UUID id) {
        return ApiResponse.success(history.findById(id).orElseThrow(()->new com.finx.exception.ResourceNotFoundException("Trust calculation","id",id)));
    }
    @GetMapping("/reviews")
    public ApiResponse<List<TrustManualReview>> reviews(@RequestParam(defaultValue="OPEN") String status,@RequestParam(defaultValue="0") int page) {
        if(!Set.of("OPEN","RESOLVED").contains(status)) throw new IllegalArgumentException("Invalid review status");
        return ApiResponse.success(reviews.findByStatusOrderByCreatedAtDesc(status,boundedPage(page)));
    }
    @PostMapping("/{id}/reviews")
    public ApiResponse<TrustManualReview> review(@PathVariable UUID id,@Valid @RequestBody Reason body,@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(reviewService.create(id,user.getId(),body.reason()));
    }
    @PostMapping("/reviews/{id}/resolve")
    public ApiResponse<TrustManualReview> resolve(@PathVariable UUID id,@Valid @RequestBody Reason body,@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(reviewService.resolve(id,user.getId(),body.reason()));
    }
    private static PageRequest boundedPage(int page) {
        if(page<0 || page>10000) throw new IllegalArgumentException("Invalid page");
        return PageRequest.of(page,50);
    }
}
