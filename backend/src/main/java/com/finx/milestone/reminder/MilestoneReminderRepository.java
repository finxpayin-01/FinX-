package com.finx.milestone.reminder;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MilestoneReminderRepository extends JpaRepository<MilestoneReminder, UUID> {

    List<MilestoneReminder> findByMilestoneIdAndStatus(UUID milestoneId, MilestoneReminderStatus status);

    List<MilestoneReminder> findByMilestoneId(UUID milestoneId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM MilestoneReminder r WHERE r.id = :id")
    Optional<MilestoneReminder> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT r FROM MilestoneReminder r WHERE r.status = :status AND r.scheduledFor <= :cutoff ORDER BY r.scheduledFor ASC")
    List<MilestoneReminder> findDueReminders(@Param("status") MilestoneReminderStatus status, @Param("cutoff") Instant cutoff);

    @Modifying
    @Query("UPDATE MilestoneReminder r SET r.status = :newStatus WHERE r.milestoneId = :milestoneId AND r.status = :currentStatus")
    int updateStatusByMilestoneIdAndCurrentStatus(@Param("milestoneId") UUID milestoneId,
                                                 @Param("currentStatus") MilestoneReminderStatus currentStatus,
                                                 @Param("newStatus") MilestoneReminderStatus newStatus);

    @Modifying
    @Query("UPDATE MilestoneReminder r SET r.status = :newStatus WHERE r.milestoneId = :milestoneId AND r.status = 'PENDING' AND r.reminderType IN ('SEVEN_DAYS_BEFORE', 'THREE_DAYS_BEFORE', 'ONE_DAY_BEFORE', 'SIX_HOURS_BEFORE')")
    int cancelPendingPreDeadlineReminders(@Param("milestoneId") UUID milestoneId,
                                         @Param("newStatus") MilestoneReminderStatus newStatus);

    boolean existsByMilestoneIdAndRecipientIdAndReminderTypeAndDeadlineVersion(
            UUID milestoneId, UUID recipientId, MilestoneReminderType reminderType, int deadlineVersion);
}
