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

    long countByStatus(ReleaseInstructionStatus status);

    @org.springframework.data.jpa.repository.Query("SELECT MIN(ri.createdAt) FROM ReleaseInstruction ri WHERE ri.status = :status")
    Optional<java.time.Instant> findOldestCreatedAtByStatus(@org.springframework.data.repository.query.Param("status") ReleaseInstructionStatus status);

    List<ReleaseInstruction> findByStatusOrderByCreatedAtAsc(ReleaseInstructionStatus status);

    List<ReleaseInstruction> findByStatusInOrderByCreatedAtAsc(Collection<ReleaseInstructionStatus> statuses);

    @org.springframework.data.jpa.repository.Query("SELECT ri FROM ReleaseInstruction ri WHERE ri.status = com.finx.release.model.ReleaseInstructionStatus.PENDING_SETTLEMENT AND ri.updatedAt < :staleThreshold ORDER BY ri.updatedAt ASC")
    List<ReleaseInstruction> findStalePendingSettlement(@org.springframework.data.repository.query.Param("staleThreshold") java.time.Instant staleThreshold);

    interface ScopeProjection {
        UUID getDealId();
        UUID getMilestoneId();
    }

    @org.springframework.data.jpa.repository.Query("SELECT ri.dealId AS dealId, ri.milestoneId AS milestoneId FROM ReleaseInstruction ri WHERE ri.id = :id")
    Optional<ScopeProjection> findScopeById(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT ri FROM ReleaseInstruction ri WHERE ri.id = :id")
    Optional<ReleaseInstruction> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT ri.* FROM release_instructions ri " +
                    "JOIN settlement_observations so ON ri.final_settlement_observation_id = so.id " +
                    "WHERE ri.status = 'SETTLED' " +
                    "AND ri.provider = :provider " +
                    "AND ri.provider_account_id = :providerAccountId " +
                    "AND COALESCE(so.provider_observed_at, so.observed_at) >= :windowStart " +
                    "AND COALESCE(so.provider_observed_at, so.observed_at) <= :windowEnd " +
                    "ORDER BY COALESCE(so.provider_observed_at, so.observed_at) ASC, ri.id ASC",
            nativeQuery = true)
    List<ReleaseInstruction> findOutboundReconciliationCandidates(
            @org.springframework.data.repository.query.Param("provider") String provider,
            @org.springframework.data.repository.query.Param("providerAccountId") String providerAccountId,
            @org.springframework.data.repository.query.Param("windowStart") java.time.Instant windowStart,
            @org.springframework.data.repository.query.Param("windowEnd") java.time.Instant windowEnd);
}
