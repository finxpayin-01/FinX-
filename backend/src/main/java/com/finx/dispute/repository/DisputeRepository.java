package com.finx.dispute.repository;

import com.finx.dispute.entity.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DisputeRepository extends JpaRepository<Dispute, UUID> {
    List<Dispute> findByDealIdOrderByCreatedAtDesc(UUID dealId);
    List<Dispute> findByRaisedByOrderByCreatedAtDesc(UUID raisedBy);
    List<Dispute> findAllByOrderByCreatedAtDesc();
    boolean existsByDealIdAndStatus(UUID dealId, com.finx.dispute.entity.DisputeStatus status);
    boolean existsByMilestoneIdAndStatus(UUID milestoneId, com.finx.dispute.entity.DisputeStatus status);
}
