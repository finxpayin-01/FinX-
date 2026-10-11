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

    interface ScopeProjection {
        UUID getDealId();
        UUID getMilestoneId();
    }

    @org.springframework.data.jpa.repository.Query("SELECT ri.dealId AS dealId, ri.milestoneId AS milestoneId FROM ReleaseInstruction ri WHERE ri.id = :id")
    Optional<ScopeProjection> findScopeById(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT ri FROM ReleaseInstruction ri WHERE ri.id = :id")
    Optional<ReleaseInstruction> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
