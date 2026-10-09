package com.finx.release.repository;

import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReleaseInstructionRepository extends JpaRepository<ReleaseInstruction, UUID> {

    Optional<ReleaseInstruction> findByReleaseDecisionId(UUID releaseDecisionId);

    Optional<ReleaseInstruction> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);

    Optional<ReleaseInstruction> findFirstByMilestoneIdAndStatusIn(UUID milestoneId, Collection<ReleaseInstructionStatus> statuses);

    List<ReleaseInstruction> findByDealIdOrderByCreatedAtDesc(UUID dealId);

    List<ReleaseInstruction> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<ReleaseInstruction> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
