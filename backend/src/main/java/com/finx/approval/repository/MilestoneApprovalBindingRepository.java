package com.finx.approval.repository;

import com.finx.approval.entity.MilestoneApprovalBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MilestoneApprovalBindingRepository extends JpaRepository<MilestoneApprovalBinding, UUID> {

    Optional<MilestoneApprovalBinding> findByMilestoneId(UUID milestoneId);

    List<MilestoneApprovalBinding> findByDealIdOrderByApprovedAtDesc(UUID dealId);

    Optional<MilestoneApprovalBinding> findByOrganizationIdAndMilestoneId(UUID organizationId, UUID milestoneId);
}
