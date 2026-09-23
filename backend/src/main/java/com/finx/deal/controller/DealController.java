package com.finx.deal.controller;

import com.finx.common.enums.Role;
import com.finx.common.response.ApiResponse;
import com.finx.deal.dto.request.CreateDealRequest;
import com.finx.deal.dto.response.DealResponse;
import com.finx.deal.service.DealService;
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
@RequestMapping({"/api/v1/deals", "/api/deals"})
@Tag(name = "Deal Management", description = "Endpoints for initiating and managing B2B fiat escrow deals")
@SecurityRequirement(name = "bearerAuth")
public class DealController {

    private final DealService dealService;

    public DealController(DealService dealService) {
        this.dealService = dealService;
    }

    @PostMapping
    @Operation(summary = "Create a new Deal", description = "Buyer initiates a new B2B fiat deal specifying seller and total amount")
    public ResponseEntity<ApiResponse<DealResponse>> createDeal(
            @Valid @RequestBody CreateDealRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        DealResponse response = dealService.createDeal(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Deal created successfully", response));
    }

    @GetMapping("/sellers")
    @Operation(summary = "List Available Sellers", description = "Fetch all registered sellers available for deal initiation")
    public ResponseEntity<ApiResponse<List<com.finx.auth.dto.response.UserSummaryResponse>>> getAvailableSellers() {
        List<com.finx.auth.dto.response.UserSummaryResponse> sellers = dealService.getAvailableSellers();
        return ResponseEntity.ok(ApiResponse.success("Sellers retrieved successfully", sellers));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Deal by ID", description = "Fetch deal metadata and terms")
    public ResponseEntity<ApiResponse<DealResponse>> getDealById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        DealResponse response = dealService.getDealById(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Deal fetched successfully", response));
    }

    @RequestMapping(value = "/{id}/accept", method = {RequestMethod.POST, RequestMethod.PATCH})
    @Operation(summary = "Accept Deal", description = "Seller accepts the assigned deal, transitioning it to ACTIVE")
    public ResponseEntity<ApiResponse<DealResponse>> acceptDeal(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        DealResponse response = dealService.acceptDeal(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Deal accepted successfully", response));
    }

    @RequestMapping(value = "/{id}/reject", method = {RequestMethod.POST, RequestMethod.PATCH})
    @Operation(summary = "Reject Deal", description = "Seller rejects the assigned deal with optional reason, transitioning it to REJECTED")
    public ResponseEntity<ApiResponse<DealResponse>> rejectDeal(
            @PathVariable UUID id,
            @RequestBody(required = false) com.finx.deal.dto.request.RejectDealRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        DealResponse response = dealService.rejectDeal(id, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Deal rejected successfully", response));
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel Deal", description = "Cancel deal by buyer or seller")
    public ResponseEntity<ApiResponse<DealResponse>> cancelDeal(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        DealResponse response = dealService.cancelDeal(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Deal cancelled successfully", response));
    }

    @GetMapping
    @Operation(summary = "List My Deals", description = "Fetch all deals associated with the authenticated user")
    public ResponseEntity<ApiResponse<List<DealResponse>>> getMyDeals(@AuthenticationPrincipal UserPrincipal currentUser) {
        List<DealResponse> deals;
        if (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.FINANCE) {
            deals = dealService.getAllDeals();
        } else if (currentUser.getRole() == Role.SELLER) {
            deals = dealService.getDealsForSeller(currentUser.getId());
        } else if (currentUser.getRole() == Role.PROJECT_MANAGER) {
            deals = dealService.getDealsForProjectManager(currentUser.getId());
        } else {
            deals = dealService.getDealsForBuyer(currentUser.getId());
        }
        return ResponseEntity.ok(ApiResponse.success("Deals retrieved successfully", deals));
    }
}
