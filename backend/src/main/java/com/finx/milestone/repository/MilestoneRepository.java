package com.finx.milestone.repository;

import com.finx.milestone.entity.Milestone;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MilestoneRepository extends JpaRepository<Milestone, UUID> {
    List<Milestone> findByDealIdOrderBySequenceAsc(UUID dealId);
    List<Milestone> findByDealIdInOrderByCreatedAtDesc(List<UUID> dealIds);
    long countByDealId(UUID dealId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Milestone m where m.id = :id")
    java.util.Optional<Milestone> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT m FROM Milestone m WHERE m.status IN (com.finx.milestone.entity.MilestoneStatus.PENDING, com.finx.milestone.entity.MilestoneStatus.IN_PROGRESS) AND m.submissionDeadline IS NOT NULL AND m.submissionDeadline <= :now")
    List<Milestone> findOverdueCandidates(@Param("now") java.time.Instant now);

    @Query("SELECT m FROM Milestone m WHERE m.dealId IN :dealIds AND m.submissionDeadline IS NOT NULL AND m.status IN (com.finx.milestone.entity.MilestoneStatus.PENDING, com.finx.milestone.entity.MilestoneStatus.IN_PROGRESS) AND m.submissionDeadline > :now ORDER BY m.submissionDeadline ASC")
    List<Milestone> findUpcomingByDealIds(@Param("dealIds") List<UUID> dealIds, @Param("now") java.time.Instant now);

    @Query("SELECT m FROM Milestone m WHERE m.dealId IN :dealIds AND (m.status = com.finx.milestone.entity.MilestoneStatus.OVERDUE OR (m.status IN (com.finx.milestone.entity.MilestoneStatus.PENDING, com.finx.milestone.entity.MilestoneStatus.IN_PROGRESS) AND m.submissionDeadline IS NOT NULL AND m.submissionDeadline <= :now)) ORDER BY m.submissionDeadline ASC")
    List<Milestone> findOverdueByDealIds(@Param("dealIds") List<UUID> dealIds, @Param("now") java.time.Instant now);
}

