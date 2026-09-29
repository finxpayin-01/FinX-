package com.finx.milestone.reminder;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "milestone_reminders", indexes = {
        @Index(name = "idx_reminders_due", columnList = "status, scheduled_for"),
        @Index(name = "idx_reminders_milestone_status", columnList = "milestone_id, status"),
        @Index(name = "idx_reminders_recipient", columnList = "recipient_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_milestone_recipient_type", columnNames = {"milestone_id", "recipient_id", "reminder_type", "deadline_version"})
})
public class MilestoneReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "milestone_id", nullable = false)
    private UUID milestoneId;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false, length = 50)
    private MilestoneReminderType reminderType;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private MilestoneReminderStatus status = MilestoneReminderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    private NotificationChannel channel = NotificationChannel.IN_APP;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "deadline_version", nullable = false)
    private int deadlineVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public MilestoneReminder() {
    }

    public MilestoneReminder(UUID milestoneId, UUID dealId, UUID recipientId, MilestoneReminderType reminderType,
                             Instant scheduledFor, NotificationChannel channel, int deadlineVersion) {
        this.milestoneId = milestoneId;
        this.dealId = dealId;
        this.recipientId = recipientId;
        this.reminderType = reminderType;
        this.scheduledFor = scheduledFor;
        this.channel = channel != null ? channel : NotificationChannel.IN_APP;
        this.status = MilestoneReminderStatus.PENDING;
        this.deadlineVersion = deadlineVersion;
        this.retryCount = 0;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMilestoneId() {
        return milestoneId;
    }

    public void setMilestoneId(UUID milestoneId) {
        this.milestoneId = milestoneId;
    }

    public UUID getDealId() {
        return dealId;
    }

    public void setDealId(UUID dealId) {
        this.dealId = dealId;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(UUID recipientId) {
        this.recipientId = recipientId;
    }

    public MilestoneReminderType getReminderType() {
        return reminderType;
    }

    public void setReminderType(MilestoneReminderType reminderType) {
        this.reminderType = reminderType;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public void setScheduledFor(Instant scheduledFor) {
        this.scheduledFor = scheduledFor;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public MilestoneReminderStatus getStatus() {
        return status;
    }

    public void setStatus(MilestoneReminderStatus status) {
        this.status = status;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public int getDeadlineVersion() {
        return deadlineVersion;
    }

    public void setDeadlineVersion(int deadlineVersion) {
        this.deadlineVersion = deadlineVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MilestoneReminder that = (MilestoneReminder) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
