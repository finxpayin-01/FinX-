package com.finx.milestone;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.reminder.*;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilestoneReminderEngineTest {

    @Mock
    private MilestoneReminderRepository reminderRepository;

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private DealRepository dealRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationChannelSender inAppChannelSender;

    private FinxClock clock;
    private MilestoneReminderService reminderService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID pmId;
    private UUID dealId;
    private Deal deal;
    private Instant baseNow;

    @BeforeEach
    void setUp() {
        baseNow = Instant.parse("2026-10-01T12:00:00Z");
        clock = new FinxClock();
        clock.setFixed(baseNow);

        lenient().when(inAppChannelSender.getChannel()).thenReturn(NotificationChannel.IN_APP);

        reminderService = new MilestoneReminderService(
                reminderRepository,
                milestoneRepository,
                dealRepository,
                auditService,
                List.of(inAppChannelSender),
                clock
        );

        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        pmId = UUID.randomUUID();
        dealId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setTitle("Alpha Infrastructure Deal");
        deal.setBuyerId(buyerId);
        deal.setSellerId(sellerId);
        deal.setProjectManagerId(pmId);
        deal.setStatus(DealStatus.IN_PROGRESS);

        lenient().when(reminderRepository.save(any(MilestoneReminder.class))).thenAnswer(invocation -> {
            MilestoneReminder r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });

        lenient().when(reminderRepository.saveAndFlush(any(MilestoneReminder.class))).thenAnswer(invocation -> {
            MilestoneReminder r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
    }

    private Milestone createMilestone(Instant deadline) {
        Milestone m = new Milestone();
        m.setId(UUID.randomUUID());
        m.setDealId(dealId);
        m.setTitle("Foundation Engineering Phase");
        m.setAmount(new BigDecimal("50000.00"));
        m.setStatus(MilestoneStatus.PENDING);
        m.setSubmissionDeadline(deadline);
        return m;
    }

    @Test
    @DisplayName("Milestone due in 10 days schedules 7d, 3d, 24h, 6h, deadline, and 24h overdue reminders")
    void testMilestoneDueIn10Days() {
        Instant deadline = baseNow.plus(Duration.ofDays(10));
        Milestone milestone = createMilestone(deadline);

        List<MilestoneReminder> reminders = reminderService.scheduleRemindersForMilestone(milestone, deal, 1);

        // Pre-deadline: 7d, 3d, 1d, 6h for seller (4)
        // Deadline reached: seller, buyer, pm (3)
        // Overdue 24h: seller, buyer, pm (3)
        // Total = 10
        assertThat(reminders).hasSize(10);

        List<MilestoneReminderType> sellerTypes = reminders.stream()
                .filter(r -> r.getRecipientId().equals(sellerId))
                .map(MilestoneReminder::getReminderType)
                .toList();

        assertThat(sellerTypes).contains(
                MilestoneReminderType.SEVEN_DAYS_BEFORE,
                MilestoneReminderType.THREE_DAYS_BEFORE,
                MilestoneReminderType.ONE_DAY_BEFORE,
                MilestoneReminderType.SIX_HOURS_BEFORE,
                MilestoneReminderType.DEADLINE_REACHED,
                MilestoneReminderType.OVERDUE_24_HOURS
        );
    }

    @Test
    @DisplayName("Milestone due in 5 days skips 7d, schedules 3d, 24h, 6h")
    void testMilestoneDueIn5Days() {
        Instant deadline = baseNow.plus(Duration.ofDays(5));
        Milestone milestone = createMilestone(deadline);

        List<MilestoneReminder> reminders = reminderService.scheduleRemindersForMilestone(milestone, deal, 1);

        List<MilestoneReminderType> sellerTypes = reminders.stream()
                .filter(r -> r.getRecipientId().equals(sellerId))
                .map(MilestoneReminder::getReminderType)
                .toList();

        assertThat(sellerTypes).doesNotContain(MilestoneReminderType.SEVEN_DAYS_BEFORE);
        assertThat(sellerTypes).contains(
                MilestoneReminderType.THREE_DAYS_BEFORE,
                MilestoneReminderType.ONE_DAY_BEFORE,
                MilestoneReminderType.SIX_HOURS_BEFORE
        );
    }

    @Test
    @DisplayName("Milestone due in 2 days skips 7d and 3d, schedules 24h and 6h")
    void testMilestoneDueIn2Days() {
        Instant deadline = baseNow.plus(Duration.ofDays(2));
        Milestone milestone = createMilestone(deadline);

        List<MilestoneReminder> reminders = reminderService.scheduleRemindersForMilestone(milestone, deal, 1);

        List<MilestoneReminderType> sellerTypes = reminders.stream()
                .filter(r -> r.getRecipientId().equals(sellerId))
                .map(MilestoneReminder::getReminderType)
                .toList();

        assertThat(sellerTypes).doesNotContain(
                MilestoneReminderType.SEVEN_DAYS_BEFORE,
                MilestoneReminderType.THREE_DAYS_BEFORE
        );
        assertThat(sellerTypes).contains(
                MilestoneReminderType.ONE_DAY_BEFORE,
                MilestoneReminderType.SIX_HOURS_BEFORE
        );
    }

    @Test
    @DisplayName("Milestone due in 8 hours schedules only 6h before deadline")
    void testMilestoneDueIn8Hours() {
        Instant deadline = baseNow.plus(Duration.ofHours(8));
        Milestone milestone = createMilestone(deadline);

        List<MilestoneReminder> reminders = reminderService.scheduleRemindersForMilestone(milestone, deal, 1);

        List<MilestoneReminderType> sellerTypes = reminders.stream()
                .filter(r -> r.getRecipientId().equals(sellerId))
                .map(MilestoneReminder::getReminderType)
                .toList();

        assertThat(sellerTypes).doesNotContain(
                MilestoneReminderType.SEVEN_DAYS_BEFORE,
                MilestoneReminderType.THREE_DAYS_BEFORE,
                MilestoneReminderType.ONE_DAY_BEFORE
        );
        assertThat(sellerTypes).contains(MilestoneReminderType.SIX_HOURS_BEFORE);
    }

    @Test
    @DisplayName("Milestone due in 3 hours skips all pre-deadline reminders")
    void testMilestoneDueIn3Hours() {
        Instant deadline = baseNow.plus(Duration.ofHours(3));
        Milestone milestone = createMilestone(deadline);

        List<MilestoneReminder> reminders = reminderService.scheduleRemindersForMilestone(milestone, deal, 1);

        List<MilestoneReminderType> sellerPreDeadline = reminders.stream()
                .filter(r -> r.getRecipientId().equals(sellerId))
                .map(MilestoneReminder::getReminderType)
                .filter(t -> t.name().endsWith("_BEFORE"))
                .toList();

        assertThat(sellerPreDeadline).isEmpty();
    }

    @Test
    @DisplayName("Early submission cancels all pre-deadline reminders")
    void testEarlySubmissionCancelsReminders() {
        UUID milestoneId = UUID.randomUUID();

        reminderService.cancelPendingPreDeadlineReminders(milestoneId);

        verify(reminderRepository).cancelPendingPreDeadlineReminders(
                eq(milestoneId),
                eq(MilestoneReminderStatus.CANCELLED)
        );
    }

    @Test
    @DisplayName("Deadline extension cancels old pending reminders, reschedules with incremented version and audits")
    void testDeadlineExtension() {
        Instant oldDeadline = baseNow.plus(Duration.ofDays(3));
        Instant newDeadline = baseNow.plus(Duration.ofDays(10));
        Milestone milestone = createMilestone(oldDeadline);

        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));

        UserPrincipal buyerPrincipal = new UserPrincipal(
                buyerId,
                "Buyer User",
                "buyer@finx.com",
                "hash",
                Role.BUYER,
                UserStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority(Role.BUYER.getAuthority()))
        );

        reminderService.updateMilestoneDeadline(milestone.getId(), newDeadline, buyerPrincipal, "Scope extension requested");

        // Verifies old pending reminders cancelled
        verify(reminderRepository).updateStatusByMilestoneIdAndCurrentStatus(
                eq(milestone.getId()),
                eq(MilestoneReminderStatus.PENDING),
                eq(MilestoneReminderStatus.CANCELLED)
        );

        // Verifies audit log written
        verify(auditService).logEvent(
                eq(buyerId),
                eq("MILESTONE_DEADLINE_CHANGED"),
                eq("MILESTONE"),
                eq(milestone.getId().toString()),
                anyString()
        );

        assertThat(milestone.getSubmissionDeadline()).isEqualTo(newDeadline);
    }

    @Test
    @DisplayName("Overdue transition marks eligible milestones as OVERDUE and logs audit event")
    void testOverdueTransition() {
        Instant passedDeadline = baseNow.minus(Duration.ofMinutes(10));
        Milestone overdueMilestone = createMilestone(passedDeadline);
        overdueMilestone.setStatus(MilestoneStatus.PENDING);

        when(milestoneRepository.findOverdueCandidates(any(Instant.class)))
                .thenReturn(List.of(overdueMilestone));
        when(milestoneRepository.saveAndFlush(any(Milestone.class))).thenAnswer(i -> i.getArgument(0));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        List<Milestone> transitioned = reminderService.checkAndTransitionOverdueMilestones();

        assertThat(transitioned).hasSize(1);
        assertThat(overdueMilestone.getStatus()).isEqualTo(MilestoneStatus.OVERDUE);

        verify(auditService).logEvent(
                eq(sellerId),
                eq("MILESTONE_BECAME_OVERDUE"),
                eq("MILESTONE"),
                eq(overdueMilestone.getId().toString()),
                anyString()
        );
    }

    @Test
    @DisplayName("Overdue transition does not affect terminal states (APPROVED, PAID, COMPLETED, DISPUTED, CANCELLED)")
    void testTerminalStatesUntouchedByOverdue() {
        when(milestoneRepository.findOverdueCandidates(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        List<Milestone> transitioned = reminderService.checkAndTransitionOverdueMilestones();
        assertThat(transitioned).isEmpty();
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Scheduler idempotency: processing due reminders marks them SENT without duplicating")
    void testSchedulerIdempotency() {
        UUID reminderId = UUID.randomUUID();
        Milestone milestone = createMilestone(baseNow.plus(Duration.ofDays(1)));
        milestone.setId(UUID.randomUUID());

        MilestoneReminder reminder = new MilestoneReminder(
                milestone.getId(),
                dealId,
                sellerId,
                MilestoneReminderType.ONE_DAY_BEFORE,
                baseNow.minus(Duration.ofMinutes(5)),
                NotificationChannel.IN_APP,
                1
        );
        reminder.setId(reminderId);

        when(reminderRepository.findByIdForUpdate(reminderId))
                .thenReturn(Optional.of(reminder));
        when(milestoneRepository.findById(milestone.getId()))
                .thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId))
                .thenReturn(Optional.of(deal));

        // First run
        boolean sentFirstRun = reminderService.processDueReminder(reminderId);
        assertThat(sentFirstRun).isTrue();
        assertThat(reminder.getStatus()).isEqualTo(MilestoneReminderStatus.SENT);
        verify(inAppChannelSender, times(1)).send(
                eq(sellerId),
                contains("due tomorrow"),
                anyString(),
                anyString()
        );

        // Second run simulation: status is now SENT, should be skipped
        boolean sentSecondRun = reminderService.processDueReminder(reminderId);
        assertThat(sentSecondRun).isFalse();

        // Channel sender was still only called exactly once
        verify(inAppChannelSender, times(1)).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Concurrency: two workers contending on the same reminder: second worker skips when already PROCESSING or SENT")
    void testConcurrencyPessimisticLockingProtection() {
        UUID reminderId = UUID.randomUUID();
        Milestone milestone = createMilestone(baseNow.plus(Duration.ofDays(1)));

        MilestoneReminder reminderAlreadyProcessing = new MilestoneReminder(
                milestone.getId(),
                dealId,
                sellerId,
                MilestoneReminderType.ONE_DAY_BEFORE,
                baseNow.minus(Duration.ofMinutes(5)),
                NotificationChannel.IN_APP,
                1
        );
        reminderAlreadyProcessing.setId(reminderId);
        // Worker 1 already marked it PROCESSING
        reminderAlreadyProcessing.setStatus(MilestoneReminderStatus.PROCESSING);

        when(reminderRepository.findByIdForUpdate(reminderId))
                .thenReturn(Optional.of(reminderAlreadyProcessing));

        // Worker 2 attempts to process
        boolean processedByWorker2 = reminderService.processDueReminder(reminderId);

        // Worker 2 must skip it
        assertThat(processedByWorker2).isFalse();
        verify(inAppChannelSender, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Failed delivery records failure reason, increments retry count and bounds retries")
    void testFailedDeliveryHandling() {
        UUID reminderId = UUID.randomUUID();
        Milestone milestone = createMilestone(baseNow.plus(Duration.ofDays(1)));

        MilestoneReminder reminder = new MilestoneReminder(
                milestone.getId(),
                dealId,
                sellerId,
                MilestoneReminderType.ONE_DAY_BEFORE,
                baseNow.minus(Duration.ofMinutes(5)),
                NotificationChannel.IN_APP,
                1
        );
        reminder.setId(reminderId);

        when(reminderRepository.findByIdForUpdate(reminderId))
                .thenReturn(Optional.of(reminder));
        when(milestoneRepository.findById(milestone.getId()))
                .thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId))
                .thenReturn(Optional.of(deal));

        doThrow(new RuntimeException("Simulated socket timeout"))
                .when(inAppChannelSender).send(any(), any(), any(), any());

        // Attempt 1
        boolean processed = reminderService.processDueReminder(reminderId);
        assertThat(processed).isFalse();
        assertThat(reminder.getStatus()).isEqualTo(MilestoneReminderStatus.PENDING);
        assertThat(reminder.getRetryCount()).isEqualTo(1);
        assertThat(reminder.getFailureReason()).contains("Simulated socket timeout");

        // Attempt 2
        reminderService.processDueReminder(reminderId);
        assertThat(reminder.getRetryCount()).isEqualTo(2);

        // Attempt 3 -> exceeds threshold, transitions to FAILED
        reminderService.processDueReminder(reminderId);
        assertThat(reminder.getRetryCount()).isEqualTo(3);
        assertThat(reminder.getStatus()).isEqualTo(MilestoneReminderStatus.FAILED);
    }
}
