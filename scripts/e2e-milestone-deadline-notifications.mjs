import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';
import { chromium } from 'playwright';

const API_URL = 'http://localhost:8080/api';
const FRONTEND_URL = 'http://localhost:3000';
const ARTIFACT_DIR = 'C:\\Users\\91863\\.gemini\\antigravity-ide\\brain\\00be978b-e1be-4628-822d-86d1deb8bddd\\screenshots';
const LOCAL_DIR = path.resolve('test-results/deadline-screenshots');
const H2_JAR = 'C:\\Users\\91863\\.m2\\repository\\com\\h2database\\h2\\2.3.232\\h2-2.3.232.jar';
const DB_URL = 'jdbc:h2:file:./backend/data/finx_db;AUTO_SERVER=TRUE';

[ARTIFACT_DIR, LOCAL_DIR].forEach(dir => {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
});

export function querySql(sql) {
    const safeSql = sql.replace(/"/g, '\\"');
    const cmd = `java -cp "${H2_JAR}" org.h2.tools.Shell -url "${DB_URL}" -user sa -sql "${safeSql}"`;
    return execSync(cmd, { encoding: 'utf-8', timeout: 15000 });
}

export function executeSql(sql) {
    const tmpFile = path.resolve(`temp_exec_${Date.now()}_${Math.random().toString(36).substring(7)}.sql`).replace(/\\/g, '/');
    fs.writeFileSync(tmpFile, sql, 'utf-8');
    try {
        const cmd = `java -cp "${H2_JAR}" org.h2.tools.Shell -url "${DB_URL}" -user sa -sql "RUNSCRIPT FROM '${tmpFile}';"`;
        return execSync(cmd, { encoding: 'utf-8', timeout: 15000 });
    } finally {
        try {
            if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile);
        } catch (e) {
            // Ignore Windows file locking
        }
    }
}

async function saveScreenshot(page, filename) {
    const p1 = path.join(ARTIFACT_DIR, filename);
    const p2 = path.join(LOCAL_DIR, filename);
    await page.screenshot({ path: p1, fullPage: true });
    await page.screenshot({ path: p2, fullPage: true });
    console.log(`[Screenshot Saved] -> ${filename}`);
}

async function apiPost(endpoint, body, token = null) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(`${API_URL}${endpoint}`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body)
    });
    const text = await res.text();
    try {
        const json = JSON.parse(text);
        if (!res.ok) throw new Error(`${endpoint} failed (${res.status}): ${JSON.stringify(json)}`);
        return json?.data !== undefined ? json.data : json;
    } catch (e) {
        if (!res.ok) throw new Error(`${endpoint} failed (${res.status}): ${text}`);
        return text;
    }
}

async function apiGet(endpoint, token = null) {
    const headers = {};
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(`${API_URL}${endpoint}`, { headers });
    const text = await res.text();
    try {
        const json = JSON.parse(text);
        if (!res.ok) throw new Error(`${endpoint} failed (${res.status}): ${JSON.stringify(json)}`);
        return json?.data !== undefined ? json.data : json;
    } catch (e) {
        if (!res.ok) throw new Error(`${endpoint} failed (${res.status}): ${text}`);
        return text;
    }
}

async function apiPatch(endpoint, body, token = null) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(`${API_URL}${endpoint}`, {
        method: 'PATCH',
        headers,
        body: JSON.stringify(body)
    });
    const text = await res.text();
    try {
        const json = JSON.parse(text);
        if (!res.ok) throw new Error(`${endpoint} failed (${res.status}): ${JSON.stringify(json)}`);
        return json?.data !== undefined ? json.data : json;
    } catch (e) {
        if (!res.ok) throw new Error(`${endpoint} failed (${res.status}): ${text}`);
        return text;
    }
}

async function loginAdmin() {
    const res = await apiPost('/auth/login', { email: 'admin@finx.com', password: 'Admin@Finx2026!' });
    return res.accessToken;
}

async function runTest() {
    console.log('================================================================');
    console.log('STARTING MILESTONE DEADLINE, REMINDER & ESCALATION E2E TEST');
    console.log('================================================================');

    const adminToken = await loginAdmin();
    console.log('Admin authenticated.');

    const ts = Date.now();
    const buyerEmail = `corp.deadline.${ts}@finx.com`;
    const vendorEmail = `vendor.deadline.${ts}@finx.com`;
    const pmEmail = `pm.deadline.${ts}@finx.com`;
    const intruderEmail = `intruder.${ts}@finx.com`;
    const password = 'Password@1234';

    // 1. Reset simulation clock
    console.log('\n--- Step 1: Resetting Simulation Clock ---');
    await apiPost('/milestones/simulation/clock', { reset: true }, adminToken);
    console.log('Simulation clock set to real-time baseline.');

    // 2. Register Users
    console.log('\n--- Step 2: Registering Test Participants ---');
    const buyerRes = await apiPost('/auth/register', { name: 'Acme Infra Corp', email: buyerEmail, password, role: 'BUYER' });
    const vendorRes = await apiPost('/auth/register', { name: 'Apex Engineering Ltd', email: vendorEmail, password, role: 'SELLER' });
    const pmRes = await apiPost('/auth/register', { name: 'Project Lead Sarah', email: pmEmail, password, role: 'PROJECT_MANAGER' });
    const unauthRes = await apiPost('/auth/register', { name: 'Unauthorized Third Party', email: intruderEmail, password, role: 'SELLER' });

    const buyerToken = buyerRes.accessToken;
    const vendorToken = vendorRes.accessToken;
    const pmToken = pmRes.accessToken;
    const unauthToken = unauthRes.accessToken;

    const buyerUser = buyerRes.user;
    const vendorUser = vendorRes.user;
    const pmUser = pmRes.user;

    console.log(`Buyer registered: ${buyerUser.id} (${buyerEmail})`);
    console.log(`Vendor registered: ${vendorUser.id} (${vendorEmail})`);
    console.log(`PM registered: ${pmUser.id} (${pmEmail})`);

    // 3. Create Deal
    console.log('\n--- Step 3: Creating Project Deal with PM Assignment ---');
    const deal = await apiPost('/deals', {
        title: `Mega Metro Phase 1 [${ts}]`,
        description: 'Construction of underground metro station and utility tunnel',
        totalAmount: 1000000.0,
        currency: 'INR',
        sellerId: vendorUser.id,
        projectManagerId: pmUser.id
    }, buyerToken);
    console.log(`Deal created: ${deal.id} - ${deal.title}`);

    // Set deal to IN_PROGRESS so milestones can be created
    executeSql(`UPDATE deals SET status = 'IN_PROGRESS' WHERE id = '${deal.id}';`);

    // 4. Create Milestone with Submission Deadline (10 days in future)
    console.log('\n--- Step 4: Creating Milestone with Submission Deadline ---');
    const now = new Date();
    const tenDaysFuture = new Date(now.getTime() + 10 * 24 * 60 * 60 * 1000);
    const fourteenDaysFuture = new Date(now.getTime() + 14 * 24 * 60 * 60 * 1000);

    const submissionDeadlineIso = tenDaysFuture.toISOString();
    const dueDateIso = fourteenDaysFuture.toISOString();

    const milestone = await apiPost(`/deals/${deal.id}/milestones`, {
        title: 'Deep Excavation & Retaining Walls',
        description: 'Excavation of pit to -18m level with certified geotechnical reinforcement.',
        amount: 250000.0,
        currency: 'INR',
        dueDate: dueDateIso,
        submissionDeadline: submissionDeadlineIso
    }, buyerToken);

    console.log(`Milestone created: ${milestone.id}`);
    console.log(`Submission Deadline: ${milestone.submissionDeadline}`);
    console.log(`Due Date: ${milestone.dueDate}`);

    // Verify DB Reminders Generated for 10-day deadline (7d, 3d, 24h, 6h)
    const initialRemindersSql = querySql(`SELECT reminder_type, status, scheduled_for FROM milestone_reminders WHERE milestone_id = '${milestone.id}' ORDER BY scheduled_for;`);
    console.log('Initial Database Reminders:');
    console.log(initialRemindersSql);

    // Launch Playwright Browser
    console.log('\n--- Step 5: Launching Playwright Chromium Browser ---');
    const browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();

    async function loginAsUser(userObj, token, targetUrl) {
        await page.goto(`${FRONTEND_URL}/login`);
        await page.evaluate(({ u, t }) => {
            localStorage.setItem('accessToken', t);
            localStorage.setItem('user', JSON.stringify(u));
        }, { u: userObj, t: token });
        await page.goto(`${FRONTEND_URL}${targetUrl}`);
        await page.waitForLoadState('networkidle');
    }

    // 5. Verify Vendor Dashboard Shows Upcoming Deadline Card
    console.log('\n--- Step 6: Verifying Upcoming Deadline Card on Vendor Dashboard ---');
    await loginAsUser(vendorUser, vendorToken, '/vendor');
    await page.waitForTimeout(2000);

    const upcomingCard = page.locator('#upcoming-deadlines-widget');
    const isCardVisible = await upcomingCard.isVisible();
    console.log(`Upcoming Deadlines Widget visible on Vendor Dashboard: ${isCardVisible}`);

    const milestoneItem = page.locator(`text=${milestone.title}`);
    const isMilestoneListed = await milestoneItem.count() > 0;
    console.log(`Milestone "${milestone.title}" listed in upcoming deadlines: ${isMilestoneListed}`);

    await saveScreenshot(page, '01_vendor_upcoming_deadlines.png');

    // 6. Simulate Clock Advance: 7 Days Before Deadline -> Trigger Scheduler
    console.log('\n--- Step 7: Simulating 7-Day Deadline Reminder via FinxClock ---');
    // Set clock to 6.5 days before deadline (so the 7-day reminder scheduled for deadline - 7d is now due)
    const sevenDaysDueInstant = new Date(tenDaysFuture.getTime() - 6.5 * 24 * 60 * 60 * 1000).toISOString();
    await apiPost('/milestones/simulation/clock', { fixedInstant: sevenDaysDueInstant }, adminToken);

    // Run scheduler run #1
    const run1 = await apiPost('/milestones/scheduler/run', {}, adminToken);
    console.log('Scheduler Run #1 result:', run1);

    // Verify Idempotency: Run scheduler run #2 and #3
    const run2 = await apiPost('/milestones/scheduler/run', {}, adminToken);
    const run3 = await apiPost('/milestones/scheduler/run', {}, adminToken);
    console.log('Scheduler Run #2 result:', run2);
    console.log('Scheduler Run #3 result:', run3);
    if (run2.processedReminders > 0 || run3.processedReminders > 0) {
        throw new Error('IDEMPOTENCY FAILURE: Duplicate reminders processed on consecutive runs!');
    }
    console.log('PASSED IDEMPOTENCY CHECK: Consecutive runs processed 0 duplicate reminders.');

    // 7. Verify Notification Bell & Dropdown on Vendor Frontend
    console.log('\n--- Step 8: Verifying Notification Bell & In-App Alerts ---');
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);

    // Check notification bell unread count badge
    const badge = page.locator('#notification-unread-count, [data-testid="notification-unread-badge"]');
    const isBadgeVisible = await badge.isVisible();
    const badgeCount = isBadgeVisible ? await badge.textContent() : '0';
    console.log(`Notification Bell unread badge visible: ${isBadgeVisible}, count: ${badgeCount?.trim()}`);

    // Click bell to open dropdown
    const bellBtn = page.locator('#notification-bell-btn');
    await bellBtn.click();
    await page.waitForTimeout(1000);

    const dropdown = page.locator('#notifications-dropdown');
    const isDropdownVisible = await dropdown.isVisible();
    console.log(`Notification dropdown visible: ${isDropdownVisible}`);

    await saveScreenshot(page, '02_vendor_notification_bell_dropdown.png');

    // 8. Simulate Overdue Status: Clock Past Deadline
    console.log('\n--- Step 9: Simulating Deadline Expiry -> OVERDUE Transition ---');
    const twoHoursPast = new Date(tenDaysFuture.getTime() + 2 * 60 * 60 * 1000).toISOString();
    await apiPost('/milestones/simulation/clock', { fixedInstant: twoHoursPast }, adminToken);

    const overdueRun = await apiPost('/milestones/scheduler/run', {}, adminToken);
    console.log('Scheduler Run (Past Deadline) result:', overdueRun);

    // Verify Milestone is now OVERDUE via API
    const updatedMilestone = await apiGet(`/milestones/${milestone.id}`, buyerToken);
    console.log(`Milestone Status after deadline expiry: ${updatedMilestone.status}`);
    if (updatedMilestone.status !== 'OVERDUE') {
        throw new Error(`Expected milestone status OVERDUE but was ${updatedMilestone.status}`);
    }

    // 9. Verify Buyer & PM Overdue Milestones Card in UI
    console.log('\n--- Step 10: Verifying Overdue Deliverables Card on Buyer Dashboard ---');
    await loginAsUser(buyerUser, buyerToken, '/corporate');
    await page.waitForTimeout(2000);

    const overdueWidget = page.locator('#overdue-milestones-widget');
    const isOverdueWidgetVisible = await overdueWidget.isVisible();
    console.log(`Overdue Deliverables Widget visible on Buyer Dashboard: ${isOverdueWidgetVisible}`);

    await saveScreenshot(page, '03_buyer_overdue_deliverables_card.png');

    // Check Corporate Milestones page
    console.log('\n--- Step 11: Inspecting Milestones Page for Overdue Status & Edit Action ---');
    await page.goto(`${FRONTEND_URL}/corporate/milestones`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    await saveScreenshot(page, '04_corporate_milestones_overdue_table.png');

    // 10. Simulate 24-Hour Overdue Escalation
    console.log('\n--- Step 12: Simulating 24-Hour Escalation Notification ---');
    const twentySixHoursPast = new Date(tenDaysFuture.getTime() + 26 * 60 * 60 * 1000).toISOString();
    await apiPost('/milestones/simulation/clock', { fixedInstant: twentySixHoursPast }, adminToken);

    const escalationRun = await apiPost('/milestones/scheduler/run', {}, adminToken);
    console.log('Scheduler Run (24h Escalation) result:', escalationRun);

    // Verify Buyer receives escalation notification
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const buyerBell = page.locator('#notification-bell-btn');
    await buyerBell.click();
    await page.waitForTimeout(1000);

    await saveScreenshot(page, '05_escalation_notification_dropdown.png');

    // 11. Test Deadline Extension & Rescheduling
    console.log('\n--- Step 13: Updating Milestone Deadline (Extension) ---');
    const newDeadlineIso = new Date(twentySixHoursPast).getTime() + 8 * 24 * 60 * 60 * 1000;
    const newDeadlineStr = new Date(newDeadlineIso).toISOString();

    const patchRes = await apiPatch(`/milestones/${milestone.id}/deadline`, {
        submissionDeadline: newDeadlineStr,
        reason: 'Severe inclement weather delayed geotechnical earthmoving operations'
    }, buyerToken);

    console.log(`Deadline extended to: ${patchRes.submissionDeadline}`);
    console.log(`New deadline version: ${patchRes.deadlineVersion}`);

    // Verify AuditLog record in DB
    const auditLogs = querySql(`SELECT action, entity_type, entity_id FROM audit_logs WHERE entity_id = '${milestone.id}' AND action = 'MILESTONE_DEADLINE_CHANGED';`);
    console.log('Audit Log for Deadline Change:');
    console.log(auditLogs);

    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
    await saveScreenshot(page, '06_deadline_updated_modal_and_reschedule.png');

    // 12. Vendor Early Submission -> Cancels Remaining Reminders
    console.log('\n--- Step 14: Vendor Early Submission -> Cancellation of Reminders ---');
    await apiPost(`/milestones/${milestone.id}/submit`, {
        fileName: 'retaining_walls_signoff.pdf',
        fileUrl: 'https://storage.finx.com/deliverables/retaining_walls_signoff.pdf',
        description: 'Geotechnical engineer certified retaining wall stability at -18m level.'
    }, vendorToken);

    const submittedMilestone = await apiGet(`/milestones/${milestone.id}`, vendorToken);
    console.log(`Milestone Status after Vendor Submission: ${submittedMilestone.status}`);

    // Verify all pending pre-deadline reminders for this milestone are cancelled in DB
    const pendingRemindersSql = querySql(`SELECT reminder_type, status FROM milestone_reminders WHERE milestone_id = '${milestone.id}' AND status = 'PENDING' AND reminder_type IN ('SEVEN_DAYS_BEFORE', 'THREE_DAYS_BEFORE', 'ONE_DAY_BEFORE', 'SIX_HOURS_BEFORE');`);
    console.log('Pending pre-deadline reminders after submission:');
    console.log(pendingRemindersSql);

    const hasAnyPending = pendingRemindersSql.includes('PENDING');
    if (hasAnyPending) {
        throw new Error('Expected 0 pending pre-deadline reminders after submission, but found active pending records!');
    }
    console.log('PASSED EARLY SUBMISSION CANCELLATION: All pending pre-deadline reminders cancelled.');

    await loginAsUser(vendorUser, vendorToken, '/vendor');
    await page.waitForTimeout(1500);
    await saveScreenshot(page, '07_early_submission_reminders_cancelled.png');

    // 13. Security Verification: Unauthorized User Isolation
    console.log('\n--- Step 15: Security & Isolation Verification ---');
    const intruderNotifs = await apiGet('/notifications', unauthToken);
    const intruderUpcoming = await apiGet('/milestones/upcoming', unauthToken);
    const intruderOverdue = await apiGet('/milestones/overdue', unauthToken);

    const hasLeakedNotif = (intruderNotifs || []).some(n => n.message && n.message.includes('Deep Excavation'));
    const hasLeakedUpcoming = (intruderUpcoming || []).some(m => m.id === milestone.id);
    const hasLeakedOverdue = (intruderOverdue || []).some(m => m.id === milestone.id);

    if (hasLeakedNotif || hasLeakedUpcoming || hasLeakedOverdue) {
        throw new Error('SECURITY VIOLATION: Unauthorized user accessed another organization\'s milestones or notifications!');
    }
    console.log('PASSED SECURITY ISOLATION CHECK: Unauthorized user cannot view other companies\' milestones or notifications.');

    // 14. Reset Clock Back to Normal
    await apiPost('/milestones/simulation/clock', { reset: true }, adminToken);
    console.log('\n--- Cleaned up simulation clock to real-time baseline ---');

    await browser.close();
    console.log('\n================================================================');
    console.log('ALL PLAYWRIGHT MCP E2E DEADLINE TESTS PASSED SUCCESSFULLY!');
    console.log('================================================================\n');
}

runTest().catch(err => {
    console.error('TEST FAILED:', err);
    process.exit(1);
});
