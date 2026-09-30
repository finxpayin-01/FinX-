package com.finx.org.controller;

import com.finx.common.response.ApiResponse;
import com.finx.org.dto.OrgDtos.*;
import com.finx.org.service.OrganizationService;
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
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations", description = "B2B Organization, Members, and Bank Account Management")
@SecurityRequirement(name = "Bearer Authentication")
public class OrganizationController {

    private final OrganizationService orgService;

    public OrganizationController(OrganizationService orgService) {
        this.orgService = orgService;
    }

    @PostMapping
    @Operation(summary = "Create an organization", description = "Registers a new B2B organization and assigns the creator as OWNER")
    public ResponseEntity<ApiResponse<OrganizationResponse>> createOrganization(
            @Valid @RequestBody CreateOrgRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        OrganizationResponse response = orgService.createOrganization(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Organization created successfully", response));
    }

    @GetMapping("/me")
    @Operation(summary = "Get user's organizations", description = "Lists all organizations the authenticated user belongs to")
    public ResponseEntity<ApiResponse<List<OrganizationResponse>>> getMyOrganizations(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<OrganizationResponse> list = orgService.getMyOrganizations(currentUser);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get organization by ID")
    public ResponseEntity<ApiResponse<OrganizationResponse>> getOrganizationById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        OrganizationResponse response = orgService.getOrganizationById(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/profile")
    @Operation(summary = "Get verified business profile", description = "Returns objective verification badges and transaction history metrics")
    public ResponseEntity<ApiResponse<VerifiedBusinessProfile>> getVerifiedBusinessProfile(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        VerifiedBusinessProfile profile = orgService.getVerifiedBusinessProfile(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Verified business profile retrieved", profile));
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Add member to organization", description = "Requires OWNER or ADMIN role in the organization")
    public ResponseEntity<ApiResponse<MemberResponse>> addMember(
            @PathVariable UUID id,
            @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        MemberResponse response = orgService.addMember(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Member added successfully", response));
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List organization members")
    public ResponseEntity<ApiResponse<List<MemberResponse>>> getMembers(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<MemberResponse> members = orgService.getMembers(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success(members));
    }

    @PostMapping("/{id}/bank-accounts")
    @Operation(summary = "Add organization bank account")
    public ResponseEntity<ApiResponse<BankAccountResponse>> addBankAccount(
            @PathVariable UUID id,
            @Valid @RequestBody AddBankAccountRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        BankAccountResponse response = orgService.addBankAccount(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Bank account added", response));
    }

    @GetMapping("/{id}/bank-accounts")
    @Operation(summary = "List organization bank accounts")
    public ResponseEntity<ApiResponse<List<BankAccountResponse>>> getBankAccounts(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<BankAccountResponse> accounts = orgService.getBankAccounts(id, currentUser);
        return ResponseEntity.ok(ApiResponse.success(accounts));
    }

    @PostMapping("/{id}/bank-accounts/{accountId}/verify")
    @Operation(summary = "Verify bank account via penny drop")
    public ResponseEntity<ApiResponse<BankAccountResponse>> verifyBankAccount(
            @PathVariable UUID id,
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        BankAccountResponse response = orgService.verifyBankAccount(id, accountId, currentUser);
        return ResponseEntity.ok(ApiResponse.success("Bank account verified successfully", response));
    }
}
