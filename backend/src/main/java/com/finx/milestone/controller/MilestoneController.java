package com.finx.milestone.controller;

import com.finx.common.response.ApiResponse;
import com.finx.milestone.dto.request.CreateMilestoneRequest;
import com.finx.milestone.dto.request.ReviewMilestoneRequest;
import com.finx.milestone.dto.request.SubmitDeliverableRequest;
import com.finx.milestone.dto.response.DeliverableResponse;
import com.finx.milestone.dto.response.MilestoneResponse;
import com.finx.milestone.service.MilestoneService;
import com.finx.security.service.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api"})
@Tag(name = "Milestones", description = "Milestone and deliverable lifecycle endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class MilestoneController {

    private final MilestoneService milestoneService;

    public MilestoneController(MilestoneService milestoneService) {
        this.milestoneService = milestoneService;
    }

    @PostMapping("/deals/{dealId}/milestones")
    @Operation(summary = "Create milestone for deal", description = "Allows buyer to add a milestone to an active deal")
    public ResponseEntity<ApiResponse<MilestoneResponse>> createMilestone(
            @PathVariable UUID dealId,
            @Valid @RequestBody CreateMilestoneRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        MilestoneResponse milestone = milestoneService.createMilestone(dealId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Milestone created successfully", milestone));
    }

    @GetMapping("/deals/{dealId}/milestones")
    @Operation(summary = "Get milestones for deal", description = "Retrieves all milestones belonging to a deal")
    public ResponseEntity<ApiResponse<List<MilestoneResponse>>> getMilestonesForDeal(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<MilestoneResponse> milestones = milestoneService.getMilestonesByDealId(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(milestones));
    }

    @GetMapping("/milestones")
    @Operation(summary = "Get all milestones for current user", description = "Retrieves all milestones across user deals")
    public ResponseEntity<ApiResponse<List<MilestoneResponse>>> getAllMilestones(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<MilestoneResponse> milestones = milestoneService.getAllMilestonesForCurrentUser(currentUser);
        return ResponseEntity.ok(ApiResponse.success(milestones));
    }

    @GetMapping("/milestones/{id}")
    @Operation(summary = "Get milestone by ID", description = "Retrieves single milestone with deliverables")
    public ResponseEntity<ApiResponse<MilestoneResponse>> getMilestoneById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        MilestoneResponse milestone = milestoneService.getMilestoneById(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success(milestone));
    }

    @PostMapping("/milestones/{id}/start")
    @Operation(summary = "Vendor starts milestone", description = "Transitions milestone to IN_PROGRESS")
    public ResponseEntity<ApiResponse<MilestoneResponse>> startMilestone(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        MilestoneResponse milestone = milestoneService.startMilestone(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Milestone work started", milestone));
    }

    @PostMapping("/milestones/{id}/submit")
    @Operation(summary = "Submit deliverable", description = "Vendor submits deliverable for review (transitions to UNDER_REVIEW)")
    public ResponseEntity<ApiResponse<DeliverableResponse>> submitDeliverable(
            @PathVariable UUID id,
            @Valid @RequestBody SubmitDeliverableRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        DeliverableResponse deliverable = milestoneService.submitDeliverable(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Deliverable submitted for review", deliverable));
    }

    @PostMapping("/milestones/{id}/approve")
    @Operation(summary = "Approve milestone", description = "Buyer reviews and approves milestone deliverable")
    public ResponseEntity<ApiResponse<MilestoneResponse>> approveMilestone(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        MilestoneResponse milestone = milestoneService.approveMilestone(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Milestone approved successfully", milestone));
    }

    @PostMapping("/milestones/{id}/reject")
    @Operation(summary = "Reject milestone", description = "Buyer rejects milestone deliverable with feedback")
    public ResponseEntity<ApiResponse<MilestoneResponse>> rejectMilestone(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewMilestoneRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        String reason = (request != null) ? request.getReason() : null;
        MilestoneResponse milestone = milestoneService.rejectMilestone(id, reason, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Milestone changes requested", milestone));
    }
}
