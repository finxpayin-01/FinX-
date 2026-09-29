package com.finx.milestone.reminder;

import com.finx.common.time.FinxClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class MilestoneDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(MilestoneDeadlineScheduler.class);

    private final MilestoneReminderRepository reminderRepository;
    private final MilestoneReminderService reminderService;
    private final FinxClock clock;

    public MilestoneDeadlineScheduler(MilestoneReminderRepository reminderRepository,
                                    MilestoneReminderService reminderService,
                                    FinxClock clock) {
        this.reminderRepository = reminderRepository;
        this.reminderService = reminderService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${finx.scheduler.milestone-reminders-interval:30000}", initialDelay = 10000)
    public void runScheduler() {
        runSchedulerNow();
    }

    public synchronized SchedulerExecutionReport runSchedulerNow() {
        Instant now = clock.now();
        log.debug("MilestoneDeadlineScheduler starting execution at {}", now);

        // 1. Transition overdue milestones
        int overdueCount = reminderService.checkAndTransitionOverdueMilestones().size();

        // 2. Query and process due reminders
        List<MilestoneReminder> dueReminders = reminderRepository.findDueReminders(MilestoneReminderStatus.PENDING, now);
        int processedCount = 0;
        int failedCount = 0;

        for (MilestoneReminder reminder : dueReminders) {
            try {
                boolean success = reminderService.processDueReminder(reminder.getId());
                if (success) {
                    processedCount++;
                }
            } catch (Exception e) {
                log.error("Error processing due reminder id={}: {}", reminder.getId(), e.getMessage());
                failedCount++;
            }
        }

        log.debug("MilestoneDeadlineScheduler execution completed: overdueMilestones={}, processedReminders={}, failedReminders={}",
                overdueCount, processedCount, failedCount);
        return new SchedulerExecutionReport(overdueCount, dueReminders.size(), processedCount, failedCount, now);
    }

    public record SchedulerExecutionReport(int overdueMilestonesTransitioned, int dueRemindersFound,
                                          int successfullySent, int failed, Instant executedAt) {}
}
