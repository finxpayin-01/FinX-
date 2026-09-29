package com.finx.milestone.reminder;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MilestoneReminderService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneReminderService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a 'UTC'").withZone(ZoneOffset.UTC);

    private final MilestoneReminderRepository reminderRepository;
    private final MilestoneRepository milestoneRepository;
    private final DealRepository dealRepository;
    private final AuditService auditService;
    private final List<NotificationChannelSender> channelSenders;
    private final FinxClock clock;

    public MilestoneReminderService(MilestoneReminderRepository reminderRepository,
                                  MilestoneRepository milestoneRepository,
                                  DealRepository dealRepository,
                                  AuditService auditService,
                                  List<NotificationChannelSender> channelSenders,
                                  FinxClock clock) {
        this.reminderRepository = reminderRepository;
        this.milestoneRepository = milestoneRepository;
        this.dealRepository = dealRepository;
        this.auditService = auditService;
        this.channelSenders = channelSenders;
        this.clock = clock;
    }

    @Transactional
    public List<MilestoneReminder> scheduleRemindersForMilestone(Milestone milestone, Deal deal, int deadlineVersion) {
        Instant deadline = milestone.getSubmissionDeadline();
        if (deadline == null) {
            log.debug("Milestone id={} has no submission deadline, skipping reminder scheduling", milestone.getId());
            return Collections.emptyList();
        }

        Instant now = clock.now();
        List<MilestoneReminder> scheduled = new ArrayList<>();

        // 1. Vendor pre-deadline reminders
        scheduleIfFuture(milestone, deal, deal.getSellerId(), MilestoneReminderType.SEVEN_DAYS_BEFORE,
                deadline.minus(Duration.ofDays(7)), now, deadlineVersion, scheduled);
        scheduleIfFuture(milestone, deal, deal.getSellerId(), MilestoneReminderType.THREE_DAYS_BEFORE,
                deadline.minus(Duration.ofDays(3)), now, deadlineVersion, scheduled);
        scheduleIfFuture(milestone, deal, deal.getSellerId(), MilestoneReminderType.ONE_DAY_BEFORE,
                deadline.minus(Duration.ofDays(1)), now, deadlineVersion, scheduled);
        scheduleIfFuture(milestone, deal, deal.getSellerId(), MilestoneReminderType.SIX_HOURS_BEFORE,
                deadline.minus(Duration.ofHours(6)), now, deadlineVersion, scheduled);

        // 2. Deadline Reached (Vendor + PM + Buyer)
        Instant deadlineTime = deadline;
        scheduleIfFuture(milestone, deal, deal.getSellerId(), MilestoneReminderType.DEADLINE_REACHED,
                deadlineTime, now, deadlineVersion, scheduled);
        scheduleIfFuture(milestone, deal, deal.getBuyerId(), MilestoneReminderType.DEADLINE_REACHED,
                deadlineTime, now, deadlineVersion, scheduled);
        if (deal.getProjectManagerId() != null) {
            scheduleIfFuture(milestone, deal, deal.getProjectManagerId(), MilestoneReminderType.DEADLINE_REACHED,
                    deadlineTime, now, deadlineVersion, scheduled);
        }

        // 3. Overdue 24h Escalation (Vendor + PM + Buyer)
        Instant overdue24h = deadline.plus(Duration.ofHours(24));
        scheduleIfFuture(milestone, deal, deal.getSellerId(), MilestoneReminderType.OVERDUE_24_HOURS,
                overdue24h, now, deadlineVersion, scheduled);
        scheduleIfFuture(milestone, deal, deal.getBuyerId(), MilestoneReminderType.OVERDUE_24_HOURS,
                overdue24h, now, deadlineVersion, scheduled);
        if (deal.getProjectManagerId() != null) {
            scheduleIfFuture(milestone, deal, deal.getProjectManagerId(), MilestoneReminderType.OVERDUE_24_HOURS,
                    overdue24h, now, deadlineVersion, scheduled);
        }

        log.info("Scheduled {} reminders for milestone id={} (version={})", scheduled.size(), milestone.getId(), deadlineVersion);
        return scheduled;
    }

    private void scheduleIfFuture(Milestone milestone, Deal deal, UUID recipientId,
                                  MilestoneReminderType type, Instant scheduledFor,
                                  Instant now, int version, List<MilestoneReminder> scheduled) {
        if (recipientId == null || scheduledFor == null) return;
        if (!scheduledFor.isAfter(now)) {
            log.debug("Skipping reminder type={} for milestone id={}: scheduled time {} is in the past (now={})",
                    type, milestone.getId(), scheduledFor, now);
            return;
        }

        boolean exists = reminderRepository.existsByMilestoneIdAndRecipientIdAndReminderTypeAndDeadlineVersion(
                milestone.getId(), recipientId, type, version);
        if (!exists) {
            MilestoneReminder reminder = new MilestoneReminder(
                    milestone.getId(), deal.getId(), recipientId, type, scheduledFor, NotificationChannel.IN_APP, version);
            scheduled.add(reminderRepository.save(reminder));
        }
    }

    @Transactional
    public void cancelPendingPreDeadlineReminders(UUID milestoneId) {
        int cancelled = reminderRepository.cancelPendingPreDeadlineReminders(milestoneId, MilestoneReminderStatus.CANCELLED);
        log.info("Cancelled {} pre-deadline reminders for milestone id={}", cancelled, milestoneId);
    }

    @Transactional
    public void cancelAllPendingReminders(UUID milestoneId) {
        int cancelled = reminderRepository.updateStatusByMilestoneIdAndCurrentStatus(
                milestoneId, MilestoneReminderStatus.PENDING, MilestoneReminderStatus.CANCELLED);
        log.info("Cancelled all {} pending reminders for milestone id={}", cancelled, milestoneId);
    }

    @Transactional
    public Milestone updateMilestoneDeadline(UUID milestoneId, Instant newDeadline, UserPrincipal currentUser, String reason) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));
        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        boolean isBuyer = deal.getBuyerId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getRole() == Role.ADMIN;
        boolean isAssignedPm = currentUser.getRole() == Role.PROJECT_MANAGER &&
                deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(currentUser.getId());

        if (!isAdmin && !isBuyer && !isAssignedPm) {
            throw new UnauthorizedException("Only the corporate buyer, assigned project manager, or an administrator can update the milestone deadline");
        }

        if (milestone.getStatus() == MilestoneStatus.APPROVED || milestone.getStatus() == MilestoneStatus.COMPLETED || milestone.getStatus() == MilestoneStatus.CANCELLED) {
            throw new BadRequestException("Cannot change deadline for milestone in terminal state: " + milestone.getStatus());
        }

        Instant oldDeadline = milestone.getSubmissionDeadline();
        milestone.setSubmissionDeadline(newDeadline);
        milestone.setDueDate(newDeadline);

        // If milestone was marked OVERDUE and the new deadline is in the future, recover status to IN_PROGRESS or PENDING
        if (milestone.getStatus() == MilestoneStatus.OVERDUE && newDeadline.isAfter(clock.now())) {
            milestone.setStatus(MilestoneStatus.IN_PROGRESS);
        }

        Milestone updated = milestoneRepository.saveAndFlush(milestone);

        // Cancel previous pending reminders
        cancelAllPendingReminders(milestoneId);

        // Increment deadline version from count of distinct schedules
        int nextVersion = (int) reminderRepository.findByMilestoneId(milestoneId).stream()
                .mapToInt(MilestoneReminder::getDeadlineVersion).max().orElse(0) + 1;

        // Schedule new reminders for new deadline
        scheduleRemindersForMilestone(updated, deal, nextVersion);

        // Audit Log
        String auditMetadata = String.format("Old deadline: %s, New deadline: %s, Reason: %s",
                oldDeadline != null ? oldDeadline.toString() : "none",
                newDeadline.toString(),
                reason != null ? reason : "None provided");
        auditService.logEvent(currentUser.getId(), "MILESTONE_DEADLINE_CHANGED", "MILESTONE", milestoneId.toString(), auditMetadata);

        return updated;
    }

    @Transactional
    public boolean processDueReminder(UUID reminderId) {
        Optional<MilestoneReminder> opt = reminderRepository.findByIdForUpdate(reminderId);
        if (opt.isEmpty()) return false;
        MilestoneReminder reminder = opt.get();

        if (reminder.getStatus() != MilestoneReminderStatus.PENDING) {
            log.debug("Reminder id={} is already in state {}, skipping", reminderId, reminder.getStatus());
            return false;
        }

        Optional<Milestone> mOpt = milestoneRepository.findById(reminder.getMilestoneId());
        if (mOpt.isEmpty()) {
            reminder.setStatus(MilestoneReminderStatus.CANCELLED);
            reminder.setFailureReason("Milestone not found");
            reminderRepository.save(reminder);
            return false;
        }

        Milestone milestone = mOpt.get();
        // If milestone is in terminal state, cancel reminder
        if (milestone.getStatus() == MilestoneStatus.APPROVED ||
            milestone.getStatus() == MilestoneStatus.COMPLETED ||
            milestone.getStatus() == MilestoneStatus.CANCELLED) {
            reminder.setStatus(MilestoneReminderStatus.CANCELLED);
            reminderRepository.save(reminder);
            return false;
        }

        // If milestone is already submitted/under review and reminder is pre-deadline, cancel it
        if ((milestone.getStatus() == MilestoneStatus.SUBMITTED || milestone.getStatus() == MilestoneStatus.UNDER_REVIEW) &&
            (reminder.getReminderType() == MilestoneReminderType.SEVEN_DAYS_BEFORE ||
             reminder.getReminderType() == MilestoneReminderType.THREE_DAYS_BEFORE ||
             reminder.getReminderType() == MilestoneReminderType.ONE_DAY_BEFORE ||
             reminder.getReminderType() == MilestoneReminderType.SIX_HOURS_BEFORE)) {
            reminder.setStatus(MilestoneReminderStatus.CANCELLED);
            reminderRepository.save(reminder);
            return false;
        }

        Optional<Deal> dOpt = dealRepository.findById(reminder.getDealId());
        if (dOpt.isEmpty()) {
            reminder.setStatus(MilestoneReminderStatus.CANCELLED);
            reminder.setFailureReason("Deal not found");
            reminderRepository.save(reminder);
            return false;
        }

        Deal deal = dOpt.get();
        reminder.setStatus(MilestoneReminderStatus.PROCESSING);
        reminder.setLastAttemptAt(clock.now());
        reminderRepository.saveAndFlush(reminder);

        try {
            dispatchNotification(reminder, milestone, deal);
            reminder.setStatus(MilestoneReminderStatus.SENT);
            reminder.setSentAt(clock.now());
            reminderRepository.saveAndFlush(reminder);

            auditService.logEvent(
                    reminder.getRecipientId(),
                    "MILESTONE_REMINDER_SENT",
                    "MILESTONE_REMINDER",
                    reminder.getId().toString(),
                    String.format("Sent %s reminder for milestone '%s' (Deal '%s')",
                            reminder.getReminderType(), milestone.getTitle(), deal.getTitle())
            );
            return true;
        } catch (Exception e) {
            log.error("Failed to send milestone reminder id={}: {}", reminder.getId(), e.getMessage(), e);
            reminder.setRetryCount(reminder.getRetryCount() + 1);
            reminder.setFailureReason(e.getMessage());
            if (reminder.getRetryCount() >= 3) {
                reminder.setStatus(MilestoneReminderStatus.FAILED);
            } else {
                reminder.setStatus(MilestoneReminderStatus.PENDING);
            }
            reminderRepository.saveAndFlush(reminder);
            return false;
        }
    }

    private void dispatchNotification(MilestoneReminder reminder, Milestone milestone, Deal deal) {
        String formattedDeadline = milestone.getSubmissionDeadline() != null
                ? DATE_TIME_FORMATTER.format(milestone.getSubmissionDeadline())
                : "No deadline specified";

        String title;
        String message;
        String route;

        boolean isRecipientSeller = deal.getSellerId().equals(reminder.getRecipientId());
        boolean isRecipientBuyer = deal.getBuyerId().equals(reminder.getRecipientId());

        if (isRecipientSeller) {
            route = "/vendor/projects/" + deal.getId();
        } else if (isRecipientBuyer) {
            route = "/corporate/projects/" + deal.getId();
        } else {
            route = "/project-manager";
        }

        switch (reminder.getReminderType()) {
            case SEVEN_DAYS_BEFORE:
                title = "Milestone submission due in 7 days";
                message = String.format("Project: %s | Milestone: %s | Deadline: %s", deal.getTitle(), milestone.getTitle(), formattedDeadline);
                break;
            case THREE_DAYS_BEFORE:
                title = "Milestone submission due in 3 days";
                message = String.format("Project: %s | Milestone: %s | Deadline: %s", deal.getTitle(), milestone.getTitle(), formattedDeadline);
                break;
            case ONE_DAY_BEFORE:
                title = "Milestone submission due tomorrow";
                message = String.format("Project: %s | Milestone: %s | Deadline: %s", deal.getTitle(), milestone.getTitle(), formattedDeadline);
                break;
            case SIX_HOURS_BEFORE:
                title = "Milestone submission due in 6 hours";
                message = String.format("Project: %s | Milestone: %s | Deadline: %s. Please submit deliverables immediately.", deal.getTitle(), milestone.getTitle(), formattedDeadline);
                break;
            case DEADLINE_REACHED:
                if (isRecipientSeller) {
                    title = "Milestone submission deadline has passed";
                    message = String.format("Project: %s | Milestone: %s. The submission deadline was %s. The milestone is now overdue.", deal.getTitle(), milestone.getTitle(), formattedDeadline);
                } else {
                    title = "Vendor milestone submission deadline missed";
                    message = String.format("Project: %s | Milestone: %s. The deadline %s has passed without deliverable submission.", deal.getTitle(), milestone.getTitle(), formattedDeadline);
                }
                break;
            case OVERDUE_24_HOURS:
                title = "ESCALATION: Milestone is 24 hours overdue";
                message = String.format("Project: %s | Milestone: %s. Deadline was %s. Action required.", deal.getTitle(), milestone.getTitle(), formattedDeadline);
                break;
            default:
                title = "Milestone notification";
                message = String.format("Update for milestone %s in project %s", milestone.getTitle(), deal.getTitle());
        }

        for (NotificationChannelSender sender : channelSenders) {
            if (sender.getChannel() == reminder.getChannel() || sender.getChannel() == NotificationChannel.IN_APP) {
                sender.send(reminder.getRecipientId(), title, message, route);
            }
        }
    }

    @Transactional
    public List<Milestone> checkAndTransitionOverdueMilestones() {
        Instant now = clock.now();
        List<Milestone> candidates = milestoneRepository.findOverdueCandidates(now);
        List<Milestone> transitioned = new ArrayList<>();

        for (Milestone m : candidates) {
            if (m.getStatus() == MilestoneStatus.PENDING || m.getStatus() == MilestoneStatus.IN_PROGRESS) {
                m.setStatus(MilestoneStatus.OVERDUE);
                Milestone saved = milestoneRepository.saveAndFlush(m);
                transitioned.add(saved);

                Optional<Deal> dOpt = dealRepository.findById(saved.getDealId());
                String dealTitle = dOpt.map(Deal::getTitle).orElse("Deal");

                auditService.logEvent(
                        dOpt.map(Deal::getSellerId).orElse(null),
                        "MILESTONE_BECAME_OVERDUE",
                        "MILESTONE",
                        saved.getId().toString(),
                        String.format("Milestone '%s' in project '%s' is overdue (deadline was %s)",
                                saved.getTitle(), dealTitle, saved.getSubmissionDeadline())
                );
            }
        }
        return transitioned;
    }
}
