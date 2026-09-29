# FinX Milestone Submission Deadline, Reminder & Escalation Engine Report

## 1. Executive Summary

This report documents the design, implementation, and end-to-end automated verification of the **Milestone Submission Deadline, Reminder, and Escalation Engine** for the FinX B2B escrow platform.

The system ensures that every deliverable requiring vendor execution is governed by a strict, timezone-aware **Vendor Submission Deadline**, automatically schedules advance notifications (7 days, 3 days, 24 hours, and 6 hours prior), transitions unfulfilled milestones to `OVERDUE` status upon expiry, escalates to Buyer and Project Manager stakeholders at 24 hours of delay, and integrates with the FinX Vendor Delivery Risk scoring engine—all protected by distributed database locking and strict idempotency controls.

---

## 2. Architecture & Design Principles

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 MILESTONE DEADLINE ENGINE                              │
└────────────────────────────────────────────────────────────────────────────────────────┘

 [Buyer / PM Creates Milestone]
                │
                ▼ (submissionDeadline in UTC Instant)
 [V20 DB: milestones.submission_deadline] ──► [MilestoneReminderService.scheduleInitialReminders]
                                                            │
               ┌────────────────────────────────────────────┴───────────────────────────┐
               ▼                                                                        ▼
   [Pre-Deadline Reminders]                                                 [Post-Deadline Reminders]
   - 7 Days Before (Vendor)                                                 - Deadline Reached (Vendor + PM + Buyer)
   - 3 Days Before (Vendor)                                                 - Overdue 24h (Vendor + PM + Buyer)
   - 24 Hours Before (Vendor)
   - 6 Hours Before (Vendor)
               │                                                                        │
               └────────────────────────────┬───────────────────────────────────────────┘
                                            ▼
                    [Persistent Database Table: milestone_reminders]
                                            │
               ┌────────────────────────────┴───────────────────────────┐
               │  Periodic Spring Worker: MilestoneDeadlineScheduler    │
               │  - Queries: status = PENDING AND scheduledFor <= now() │
               │  - Pessimistic Concurrency Lock: SELECT FOR UPDATE     │
               └────────────────────────────┬───────────────────────────┘
                                            ▼
                           [Notification Channels Dispatcher]
                            ├── InAppNotificationSender  ──► /api/notifications & WebSocket
                            └── EmailNotificationSender  ──► Modular Extensibility
                                            │
    ┌───────────────────────────────────────┼───────────────────────────────────────────┐
    │                                       │                                           │
    ▼                                       ▼                                           ▼
[Vendor Early Submit]              [Buyer Extends Deadline]                  [Deadline Expiry]
- milestone: UNDER_REVIEW          - cancel old pending reminders            - milestone: OVERDUE
- cancel pending reminders         - increment deadline_version              - notify Vendor + PM + Buyer
- audit: MILESTONE_SUBMITTED       - generate new reminder set               - +24h: Escalation Notice
                                   - audit: MILESTONE_DEADLINE_CHANGED       - Risk Engine: delivery latency
```

---

## 3. Files Created and Modified

### Database Migrations
- `backend/src/main/resources/db/migration/V20__milestone_deadline_reminders.sql` *(Created)*:
  - Adds `submission_deadline TIMESTAMP WITH TIME ZONE` and `deadline_version INT DEFAULT 1` to `milestones`.
  - Creates `milestone_reminders` table with primary key, foreign keys to `milestones`, `deals`, `users`.
  - Creates compound indices: `idx_milestone_reminders_due` (`status`, `scheduled_for`), `idx_milestones_overdue_scan` (`status`, `submission_deadline`), and `idx_milestone_reminders_dedup` (`milestone_id`, `recipient_id`, `reminder_type`, `deadline_version`).
  - Registers audit actions: `MILESTONE_DEADLINE_CREATED`, `MILESTONE_DEADLINE_CHANGED`, `MILESTONE_REMINDER_SENT`, `MILESTONE_BECAME_OVERDUE`, `MILESTONE_OVERDUE_ESCALATED`, `MILESTONE_SUBMITTED`.

### Backend Entities, Repositories & Services
- `backend/src/main/java/com/finx/milestone/entity/Milestone.java` *(Modified)*:
  - Added `submissionDeadline` (`Instant`) and `deadlineVersion` (`int`).
- `backend/src/main/java/com/finx/milestone/entity/MilestoneStatus.java` *(Modified)*:
  - Added `OVERDUE` state to lifecycle enum.
- `backend/src/main/java/com/finx/milestone/entity/MilestoneReminder.java` *(Created)*:
  - Persistent entity tracking reminder jobs, channel, version, execution timestamps, retry attempt count, and failure reasons.
- `backend/src/main/java/com/finx/milestone/entity/MilestoneReminderType.java` *(Created)*:
  - `SEVEN_DAYS_BEFORE`, `THREE_DAYS_BEFORE`, `ONE_DAY_BEFORE`, `SIX_HOURS_BEFORE`, `DEADLINE_REACHED`, `OVERDUE_24_HOURS`.
- `backend/src/main/java/com/finx/milestone/entity/MilestoneReminderStatus.java` *(Created)*:
  - `PENDING`, `PROCESSING`, `SENT`, `FAILED`, `CANCELLED`.
- `backend/src/main/java/com/finx/milestone/entity/NotificationChannel.java` *(Created)*:
  - `IN_APP`, `EMAIL`.
- `backend/src/main/java/com/finx/milestone/reminder/MilestoneReminderRepository.java` *(Created)*:
  - Pessimistic locking query `findByIdForUpdate` (`LockModeType.PESSIMISTIC_WRITE`).
  - Queries for scheduled execution `findDueReminders` and batch cancellation.
- `backend/src/main/java/com/finx/milestone/reminder/FinxClock.java` *(Created)*:
  - Pluggable clock abstraction supporting production system UTC and deterministic test simulation (`setFixed`, `advance`, `reset`).
- `backend/src/main/java/com/finx/milestone/reminder/NotificationChannelSender.java` *(Created)*:
  - Channel strategy interface.
- `backend/src/main/java/com/finx/milestone/reminder/InAppNotificationSender.java` *(Created)*:
  - Direct integration with FinX `NotificationService` and deep-linking routes.
- `backend/src/main/java/com/finx/milestone/reminder/EmailNotificationSender.java` *(Created)*:
  - Decoupled modular email stub ready for production SMTP / SendGrid credentials.
- `backend/src/main/java/com/finx/milestone/reminder/MilestoneReminderService.java` *(Created)*:
  - Schedule generation, pre-deadline cancellation, deadline mutation with audit logging, and overdue transitions.
- `backend/src/main/java/com/finx/milestone/reminder/MilestoneDeadlineScheduler.java` *(Created)*:
  - Scheduled worker running every 60 seconds with on-demand API trigger for testing.
- `backend/src/main/java/com/finx/milestone/repository/MilestoneRepository.java` *(Modified)*:
  - Added `findOverdueCandidates`, `findUpcomingByDealIds`, and `findOverdueByDealIds`.
- `backend/src/main/java/com/finx/milestone/service/MilestoneService.java` *(Modified)*:
  - Integrated `FinxClock`, submission deadline propagation, reminder cancellation on submission.
- `backend/src/main/java/com/finx/milestone/controller/MilestoneController.java` *(Modified)*:
  - Exposed `/api/milestones/upcoming`, `/api/milestones/overdue`, `PATCH /api/milestones/{id}/deadline`, `/api/milestones/scheduler/run`, and `/api/milestones/simulation/clock`.
- `backend/src/main/java/com/finx/risk/service/RiskEvidenceService.java` *(Modified)*:
  - Updated late milestone calculation to check `coalesce(submission_deadline, due_date)` against deliverable submission timestamp.

### Frontend Components & Pages
- `frontend/types/index.ts` *(Modified)*:
  - Added `OVERDUE` to `MilestoneStatus`; added `submissionDeadline`, `overdue`, `timeUntilDeadline`, `dealTitle`, `sellerName` to `Milestone`.
- `frontend/services/milestone.service.ts` *(Modified)*:
  - Added `getUpcomingMilestones`, `getOverdueMilestones`, `updateDeadline`, `triggerScheduler`, `setSimulationClock`.
- `frontend/components/ui/MilestoneStatusBadge.tsx` *(Modified)*:
  - Styled `OVERDUE` badge with amber-red warning border and icon.
- `frontend/components/ui/Card.tsx` *(Modified)*:
  - Supported optional `id` prop for end-to-end selector stability.
- `frontend/components/notifications/NotificationBell.tsx` *(Modified)*:
  - Added testing attributes (`#notification-unread-count`, `#notification-unread-badge`), polling fallback (15s), and deep-link routing.
- `frontend/components/milestones/UpcomingDeadlinesCard.tsx` *(Created)*:
  - Vendor dashboard widget displaying deadlines ordered by nearest due with countdown badges and direct "Submit Deliverable" CTAs.
- `frontend/components/milestones/OverdueMilestonesCard.tsx` *(Created)*:
  - Buyer / PM alert widget displaying overdue milestones, vendor identities, and direct inspect links.
- `frontend/components/milestones/UpdateDeadlineModal.tsx` *(Created)*:
  - Modal with date and time pickers, local timezone badge, and reason field.
- `frontend/app/corporate/milestones/page.tsx` *(Modified)*:
  - Added submission deadline date & time pickers to Create Milestone Modal.
  - Added Deadlines column displaying formatted submission deadline, relative badges, and "Deadline" edit button.
- `frontend/app/vendor/page.tsx` *(Modified)*:
  - Embedded `<UpcomingDeadlinesCard />`.
- `frontend/app/corporate/page.tsx` *(Modified)*:
  - Embedded `<OverdueMilestonesCard roleContext="BUYER" />`.
- `frontend/app/project-manager/page.tsx` *(Modified)*:
  - Embedded `<OverdueMilestonesCard roleContext="PROJECT_MANAGER" />`.

---

## 4. Concurrency Protection & Idempotency Strategy

1. **Database Locking**:
   - The scheduler claims due reminders via `MilestoneReminderRepository.findByIdForUpdate(id)` using JPA `LockModeType.PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`).
   - If two instances select the same reminder simultaneously, one transaction blocks until the first completes.
   - The claiming transaction validates `reminder.getStatus() == MilestoneReminderStatus.PENDING` and immediately sets status to `PROCESSING` within the same transaction boundary.
2. **Deduplication Key**:
   - Every reminder record is identified by `(milestoneId, recipientId, reminderType, deadlineVersion)`.
   - Re-running the scheduler multiple times on identical data claims 0 duplicate records.
3. **Idempotency Test Validation**:
   - In E2E automated test runs, `MilestoneDeadlineScheduler` was invoked 3 times back-to-back at the exact same simulated timestamp:
     - Run #1: 1 reminder processed and sent.
     - Run #2: 0 reminders processed.
     - Run #3: 0 reminders processed.

---

## 5. End-to-End Verification Trace

### Trace A: Happy Path (Early Submission)
1. **Creation**: Corporate Buyer creates Milestone "Deep Excavation & Retaining Walls" with submission deadline set to 10 days in future.
2. **Scheduling**: System automatically generates 4 pre-deadline reminder jobs (`SEVEN_DAYS_BEFORE`, `THREE_DAYS_BEFORE`, `ONE_DAY_BEFORE`, `SIX_HOURS_BEFORE`) for the Vendor, plus post-deadline alerts.
3. **Advance Clock to 7 Days Before**: Scheduler processes the 7-day reminder. In-App notification delivered to Vendor dashboard with route `/vendor/projects/{dealId}`.
4. **Vendor Notification Bell**: Shows badge `1 new`. Dropdown displays "Milestone submission due in 7 days".
5. **Early Submission**: Vendor uploads deliverable `retaining_walls_signoff.pdf`. Milestone transitions to `UNDER_REVIEW`.
6. **Cancellation**: System immediately executes `cancelPendingPreDeadlineReminders(milestoneId)`, marking all remaining pending pre-deadline reminders as `CANCELLED`. DB verification confirmed 0 pending pre-deadline reminders.

### Trace B: Overdue & Escalation Path
1. **Clock Expiry**: Simulated clock advanced 2 hours past the submission deadline without submission.
2. **Status Transition**: Scheduler runs and transitions Milestone status to `OVERDUE`.
3. **Multi-Party Alert**: `DEADLINE_REACHED` notifications generated for Vendor, Buyer, and PM.
4. **Dashboard Alert**: Buyer and PM dashboards display `Overdue Deliverables Alert` card listing delayed milestone.
5. **24-Hour Escalation**: Clock advanced by 26 hours. Scheduler generates `OVERDUE_24_HOURS` escalation alert delivered to Buyer and PM.
6. **Deadline Extension**: Corporate Buyer updates deadline by +8 days with reason *"Inclement weather"*.
7. **Audit Record**: Written to `audit_logs` table (`action=MILESTONE_DEADLINE_CHANGED`, `entityType=MILESTONE`). Old reminders cancelled; new version 2 reminders scheduled.

---

## 6. Automated Test Results

### 1. Backend Unit & Integration Tests
**Command:** `mvnw test -Dtest=MilestoneReminderEngineTest,MilestoneServiceTest`
- `MilestoneReminderEngineTest`:
  - `testScheduleReminders_TenDaysOut`: **PASSED** (7d, 3d, 24h, 6h scheduled)
  - `testScheduleReminders_FiveDaysOut`: **PASSED** (3d, 24h, 6h scheduled)
  - `testScheduleReminders_TwoDaysOut`: **PASSED** (24h, 6h scheduled)
  - `testScheduleReminders_EightHoursOut`: **PASSED** (6h scheduled)
  - `testScheduleReminders_ThreeHoursOut`: **PASSED** (no obsolete pre-deadline reminders)
  - `testEarlySubmissionCancelsReminders`: **PASSED** (cancels pre-deadline reminders)
  - `testDeadlineExtension`: **PASSED** (cancels old, reschedules with version increment, audits)
  - `testDeadlineShortened`: **PASSED** (re-evaluates future reminders)
  - `testOverdueTransitionAndNotifications`: **PASSED** (transitions to OVERDUE, notifies Vendor+PM+Buyer)
  - `testTerminalStatesProtection`: **PASSED** (APPROVED, PAID, COMPLETED protected)
  - `testSchedulerIdempotency`: **PASSED** (repeated runs yield 0 duplicates)
  - `testConcurrencyLocking`: **PASSED** (simultaneous workers: exactly 1 claims reminder)
- `MilestoneServiceTest`: **8/8 PASSED**
- **Total Backend Tests Run:** 20, **Failures:** 0, **Errors:** 0.

### 2. Frontend Production Build & Type-Check
**Command:** `npm run build`
- Turbopack compilation: **PASSED** in 3.7s
- TypeScript type-check across 62 routes: **0 errors**

### 3. Playwright MCP End-to-End Suite
**Command:** `node scripts/e2e-milestone-deadline-notifications.mjs`
- 15/15 validation steps executed and **PASSED**:
  - `[PASS]` Reset simulation clock to baseline
  - `[PASS]` Register Buyer, Vendor, PM, Intruder
  - `[PASS]` Create project deal with PM assignment
  - `[PASS]` Create milestone with submission deadline
  - `[PASS]` Initial database reminders verified in H2 DB (8 rows)
  - `[PASS]` Vendor dashboard shows Upcoming Submission Deadlines widget
  - `[PASS]` 7-Day deadline reminder processed via FinxClock
  - `[PASS]` Scheduler idempotency: 0 duplicates on runs 2 and 3
  - `[PASS]` Notification bell badge & dropdown verified in UI
  - `[PASS]` Deadline expiry transitions milestone to OVERDUE
  - `[PASS]` Buyer dashboard displays Overdue Deliverables Alert widget
  - `[PASS]` Milestones table displays OVERDUE badge and Deadline edit action
  - `[PASS]` 24-hour escalation notification generated and verified
  - `[PASS]` Deadline extension generates version 2 reminders & logs audit record
  - `[PASS]` Early deliverable submission cancels all pending pre-deadline reminders (0 rows)
  - `[PASS]` Security isolation: Unauthorized user cannot view other organizations' milestones or notifications

### 4. Existing Risk Engine Regression Suite
**Command:** `node scripts/e2e-risk-suite.mjs`
- **Execution Complete. Passed: 21, Failed: 0.**

---

## 7. Artifact Screenshots

The following artifacts have been captured and saved to the artifact repository (`screenshots/`):

1. **`01_vendor_upcoming_deadlines.png`**:
   - Vendor dashboard displaying the "Upcoming Submission Deadlines" card with countdown badge and project context.
2. **`02_vendor_notification_bell_dropdown.png`**:
   - Notification bell unread count badge and notification dropdown displaying milestone deadline alerts.
3. **`03_buyer_overdue_deliverables_card.png`**:
   - Buyer dashboard displaying the "Overdue Deliverables Alert" widget with delayed deliverable counter.
4. **`04_corporate_milestones_overdue_table.png`**:
   - Corporate Milestones table showing `OVERDUE` status badge, relative timing indicator, and "Deadline" edit button.
5. **`05_escalation_notification_dropdown.png`**:
   - Stakeholder notification dropdown showing 24-hour overdue escalation alert.
6. **`06_deadline_updated_modal_and_reschedule.png`**:
   - Modal interface allowing Buyer/PM to extend the submission deadline with reason capture and timezone preview.
7. **`07_early_submission_reminders_cancelled.png`**:
   - Post-submission state confirming all obsolete pre-deadline reminders have been cancelled.

---

## 8. Known Limitations & Future Enhancements

1. **Notification Preferences**:
   - The current engine sends in-app notifications and provides an email channel abstraction. A user-level channel preference settings screen (`/vendor/settings`, `/corporate/settings`) can be layered on top to allow users to opt out of specific notification intervals (e.g. 7-day vs 24-hour).
2. **WebSocket Push**:
   - In-app notification delivery currently utilizes real-time polling (15s interval) and on-action fetches. Integrating STOMP/WebSocket will enable sub-second badge updates without polling.
3. **Custom Deal Reminder Calendars**:
   - The default schedule (`7d`, `3d`, `24h`, `6h`) is standard across all milestones. Future releases can allow corporate buyers to define custom reminder schedules per deal (e.g. 14d, 5d, 12h for complex EPC infrastructure contracts).
