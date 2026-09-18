package com.finx.projectmanager.controller;

import com.finx.auth.dto.response.UserSummaryResponse;
import com.finx.common.enums.Role;
import com.finx.common.response.ApiResponse;
import com.finx.projectmanager.dto.request.DeclineInvitationRequest;
import com.finx.projectmanager.dto.request.InviteProjectManagerRequest;
import com.finx.projectmanager.dto.response.DealProjectManagerInfoResponse;
import com.finx.projectmanager.dto.response.ProjectManagerInvitationResponse;
import com.finx.projectmanager.entity.InvitationStatus;
import com.finx.projectmanager.service.ProjectManagerInvitationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@Tag(name = "Project Manager Invitations & Assignments", description = "Endpoints for two-sided Project Manager invitations, discovery, and assignments")
@SecurityRequirement(name = "bearerAuth")
public class ProjectManagerInvitationController {

    private final ProjectManagerInvitationService invitationService;
    private final UserRepository userRepository;

    public ProjectManagerInvitationController(ProjectManagerInvitationService invitationService,
                                              UserRepository userRepository) {
        this.invitationService = invitationService;
        this.userRepository = userRepository;
    }

    // =========================================================================
    // PM WORKSPACE ENDPOINTS
    // =========================================================================

    @GetMapping("/api/project-manager/invitations")
    @Operation(summary = "List PM Invitations", description = "Fetch all project invitations addressed to the authenticated Project Manager")
    @PreAuthorize("hasAnyRole('PROJECT_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<ProjectManagerInvitationResponse>>> getMyInvitations(
            @RequestParam(required = false) InvitationStatus status,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        List<ProjectManagerInvitationResponse> list = invitationService.getMyInvitations(currentUser, status);
        return ResponseEntity.ok(ApiResponse.success("Invitations retrieved successfully", list));
    }

    @GetMapping("/api/project-manager/invitations/count")
    @Operation(summary = "Get Pending PM Invitations Count", description = "Fetch count of pending invitations for notification badge")
    @PreAuthorize("hasAnyRole('PROJECT_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getPendingCount(
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        long count = invitationService.getPendingInvitationCount(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Pending count retrieved", Map.of("pendingCount", count)));
    }

    @GetMapping("/api/project-manager/invitations/{id}")
    @Operation(summary = "Get Invitation by ID", description = "Fetch single invitation details")
    public ResponseEntity<ApiResponse<ProjectManagerInvitationResponse>> getInvitationById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        ProjectManagerInvitationResponse resp = invitationService.getInvitationById(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Invitation retrieved successfully", resp));
    }

    @PostMapping("/api/project-manager/invitations/{id}/accept")
    @Operation(summary = "Accept Project Invitation", description = "Project Manager accepts project invitation and becomes active assigned PM")
    @PreAuthorize("hasAnyRole('PROJECT_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProjectManagerInvitationResponse>> acceptInvitation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        ProjectManagerInvitationResponse response = invitationService.acceptInvitation(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Project invitation accepted successfully. You are now assigned to the project.", response));
    }

    @PostMapping("/api/project-manager/invitations/{id}/decline")
    @Operation(summary = "Decline Project Invitation", description = "Project Manager declines project invitation with optional reason")
    @PreAuthorize("hasAnyRole('PROJECT_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProjectManagerInvitationResponse>> declineInvitation(
            @PathVariable UUID id,
            @RequestBody(required = false) DeclineInvitationRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        String reason = (request != null) ? request.getReason() : null;
        ProjectManagerInvitationResponse response = invitationService.declineInvitation(id, reason, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Project invitation declined", response));
    }

    // =========================================================================
    // PROJECT / DEAL SIDE ENDPOINTS
    // =========================================================================

    @GetMapping("/api/deals/project-managers")
    @Operation(summary = "List Eligible Project Managers", description = "Fetch registered Project Managers available for project assignment/invitation")
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getEligibleProjectManagers() {
        List<UserSummaryResponse> pms = userRepository.findByRole(Role.PROJECT_MANAGER).stream()
                .map(UserSummaryResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("Eligible Project Managers retrieved successfully", pms));
    }

    @GetMapping("/api/deals/{dealId}/project-manager")
    @Operation(summary = "Get Project's Project Manager Info", description = "Fetch active PM and latest invitation status for a project")
    public ResponseEntity<ApiResponse<DealProjectManagerInfoResponse>> getDealProjectManager(
            @PathVariable UUID dealId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        DealProjectManagerInfoResponse response = invitationService.getDealProjectManagerInfo(dealId, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Deal Project Manager info retrieved", response));
    }

    @PostMapping("/api/deals/{dealId}/project-manager/invitations")
    @Operation(summary = "Invite Project Manager", description = "Buyer or Admin sends an invitation to an eligible Project Manager")
    public ResponseEntity<ApiResponse<ProjectManagerInvitationResponse>> inviteProjectManager(
            @PathVariable UUID dealId,
            @Valid @RequestBody InviteProjectManagerRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        ProjectManagerInvitationResponse response = invitationService.inviteProjectManager(
                dealId,
                request.getProjectManagerId(),
                currentUser
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Invitation sent successfully to Project Manager", response));
    }

    @PostMapping("/api/deals/{dealId}/project-manager/invitations/{invitationId}/cancel")
    @Operation(summary = "Cancel Project Manager Invitation", description = "Buyer or Admin cancels a pending PM invitation")
    public ResponseEntity<ApiResponse<ProjectManagerInvitationResponse>> cancelInvitation(
            @PathVariable UUID dealId,
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        ProjectManagerInvitationResponse response = invitationService.cancelInvitation(dealId, invitationId, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Invitation cancelled successfully", response));
    }
}
