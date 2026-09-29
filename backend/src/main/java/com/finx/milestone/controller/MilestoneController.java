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
    private final com.finx.milestone.reminder.MilestoneDeadlineScheduler deadlineScheduler;
    private final com.finx.common.time.FinxClock clock;

    public MilestoneController(MilestoneService milestoneService,
                               com.finx.milestone.reminder.MilestoneDeadlineScheduler deadlineScheduler,
                               com.finx.common.time.FinxClock clock) {
        this.milestoneService = milestoneService;
        this.deadlineScheduler = deadlineScheduler;
        this.clock = clock;
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

    @GetMapping("/milestones/upcoming")
    @Operation(summary = "Get upcoming milestones", description = "Retrieves milestones ordered by nearest upcoming submission deadline")
    public ResponseEntity<ApiResponse<List<MilestoneResponse>>> getUpcomingMilestones(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<MilestoneResponse> upcoming = milestoneService.getUpcomingMilestones(currentUser);
        return ResponseEntity.ok(ApiResponse.success(upcoming));
    }

    @GetMapping("/milestones/overdue")
    @Operation(summary = "Get overdue milestones", description = "Retrieves active milestones that passed their submission deadline")
    public ResponseEntity<ApiResponse<List<MilestoneResponse>>> getOverdueMilestones(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<MilestoneResponse> overdue = milestoneService.getOverdueMilestones(currentUser);
        return ResponseEntity.ok(ApiResponse.success(overdue));
    }

    @PatchMapping("/milestones/{id}/deadline")
    @Operation(summary = "Update milestone submission deadline", description = "Buyer or PM updates deadline, cancelling obsolete reminders and scheduling new ones")
    public ResponseEntity<ApiResponse<MilestoneResponse>> updateDeadline(
            @PathVariable UUID id,
            @Valid @RequestBody com.finx.milestone.dto.request.UpdateDeadlineRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        MilestoneResponse updated = milestoneService.updateMilestoneDeadline(id, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Milestone deadline updated successfully", updated));
    }

    @PostMapping("/milestones/scheduler/run")
    @Operation(summary = "Run milestone deadline scheduler immediately", description = "Manually triggers scheduler cycle for processing due reminders and overdue milestones")
    public ResponseEntity<ApiResponse<com.finx.milestone.reminder.MilestoneDeadlineScheduler.SchedulerExecutionReport>> runScheduler(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        com.finx.milestone.reminder.MilestoneDeadlineScheduler.SchedulerExecutionReport report = deadlineScheduler.runSchedulerNow();
        return ResponseEntity.ok(ApiResponse.success("Scheduler executed successfully", report));
    }

    @PostMapping("/milestones/simulation/clock")
    @Operation(summary = "Simulate clock for time-based testing", description = "Advances or fixes system clock for zero-sleep testing")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> simulateClock(
            @RequestBody java.util.Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        if (body.containsKey("reset") && Boolean.TRUE.equals(body.get("reset"))) {
            clock.reset();
        } else if (body.containsKey("fixedInstant")) {
            clock.setFixed(java.time.Instant.parse(body.get("fixedInstant").toString()));
        } else if (body.containsKey("advanceMinutes")) {
            long minutes = ((Number) body.get("advanceMinutes")).longValue();
            clock.advance(java.time.Duration.ofMinutes(minutes));
        } else if (body.containsKey("advanceHours")) {
            long hours = ((Number) body.get("advanceHours")).longValue();
            clock.advance(java.time.Duration.ofHours(hours));
        } else if (body.containsKey("advanceDays")) {
            long days = ((Number) body.get("advanceDays")).longValue();
            clock.advance(java.time.Duration.ofDays(days));
        }
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                "currentClockTime", clock.now().toString(),
                "instant", clock.now().toEpochMilli()
        )));
    }
}
