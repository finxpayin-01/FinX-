package com.finx.deal.service;

import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.exception.UnauthorizedException;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DealAccessService {

    private final OrganizationMemberRepository memberRepository;

    public DealAccessService(OrganizationMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public boolean canUserAccessDeal(Deal deal, UserPrincipal currentUser) {
        if (deal == null || currentUser == null) {
            return false;
        }

        // 1. Platform ADMIN or platform FINANCE
        if (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.FINANCE) {
            return true;
        }

        UUID userId = currentUser.getId();

        // 2. Explicitly assigned Project Manager for this deal
        if (deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(userId)) {
            return true;
        }

        // 3. Buyer participation:
        // If buyer organization is bound, caller must have ACTIVE membership in that organization
        if (deal.getBuyerOrganizationId() != null) {
            boolean isBuyerOrgMember = memberRepository.findByOrganizationIdAndUserId(deal.getBuyerOrganizationId(), userId)
                    .map(m -> m.getStatus() == MemberStatus.ACTIVE)
                    .orElse(false);
            if (isBuyerOrgMember) {
                return true;
            }
        } else if (deal.getBuyerId() != null && deal.getBuyerId().equals(userId)) {
            // Direct buyer user access for legacy unbound deals
            return true;
        }

        // 4. Seller participation:
        // If seller organization is bound, caller must have ACTIVE membership in that organization
        if (deal.getSellerOrganizationId() != null) {
            boolean isSellerOrgMember = memberRepository.findByOrganizationIdAndUserId(deal.getSellerOrganizationId(), userId)
                    .map(m -> m.getStatus() == MemberStatus.ACTIVE)
                    .orElse(false);
            if (isSellerOrgMember) {
                return true;
            }
        } else if (deal.getSellerId() != null && deal.getSellerId().equals(userId)) {
            // Direct seller user access for legacy unbound deals
            return true;
        }

        return false;
    }

    public void requireDealAccess(Deal deal, UserPrincipal currentUser) {
        if (!canUserAccessDeal(deal, currentUser)) {
            throw new UnauthorizedException("You are not authorized to access this deal");
        }
    }
}
