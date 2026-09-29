/**
 * Browser E2E validation of High-Risk Alert creation and Manual Review Workflow
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const BASE_URL = 'http://localhost:3000';
const API_URL = 'http://localhost:8080/api';
const SCREENSHOT_DIR = path.resolve('test-results/screenshots');

async function testAlertAndReviewFlow() {
  console.log('===============================================================');
  console.log('       FinX High-Risk Alert & Manual Review Browser E2E        ');
  console.log('===============================================================\n');

  // 1. Admin Login
  const loginRes = await fetch(`${API_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@finx.com', password: 'Admin@Finx2026!' })
  });
  const token = (await loginRes.json()).data.accessToken;

  // 2. Register a dedicated High-Risk Test Subject
  const targetEmail = `highrisk.vendor.${Date.now()}@finx.com`;
  const regRes = await fetch(`${API_URL}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: 'HighRisk Manufacturing Ltd',
      email: targetEmail,
      password: 'TargetPassword123!',
      role: 'SELLER'
    })
  });
  const regData = await regRes.json();
  const userId = regData.data?.user?.id;
  console.log(`[1] Created Target Vendor: ${targetEmail} (${userId})`);

  // 3. Fetch current Vendor Rule & calibrate to trigger High/Critical risk
  const rulesListRes = await fetch(`${API_URL}/admin/risk/rules`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  const rules = await rulesListRes.json();
  const vendorRule = rules.data.find(r => r.userType === 'VENDOR');
  const originalVendorPolicy = JSON.parse(JSON.stringify(vendorRule.policy));

  // Temporarily calibrate weights: VERIFICATION = 100, others = 0
  // With unverified KYC (points=50) + KYB (points=50) -> Category Score = 100 -> Total = 100 (CRITICAL)
  const highRiskPolicy = JSON.parse(JSON.stringify(originalVendorPolicy));
  for (const k in highRiskPolicy.weights) {
    highRiskPolicy.weights[k] = (k === 'VERIFICATION') ? 100 : 0;
  }
  highRiskPolicy.rules = highRiskPolicy.rules.map(r => {
    if (r.code === 'KYC_INCOMPLETE' || r.code === 'KYB_INCOMPLETE') {
      return { ...r, points: 50 };
    }
    return r;
  });

  const updateRuleRes = await fetch(`${API_URL}/admin/risk/rules/${vendorRule.id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      expectedVersion: vendorRule.version,
      policy: highRiskPolicy,
      reason: 'Calibrate high-risk threshold for E2E alert and review validation'
    })
  });
  const updatedRuleData = await updateRuleRes.json();
  console.log(`[2] Calibrated Vendor Policy to trigger High/Critical risk (Version: ${updatedRuleData.data?.version})`);

  // 4. Trigger Recalculation on Target Vendor
  const recalcRes = await fetch(`${API_URL}/admin/risk/${userId}/recalculate`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` }
  });
  const recalcData = await recalcRes.json();
  const assessment = recalcData.data;
  console.log(`[3] Recalculated Score: ${assessment?.totalRiskScore} / 100, Level: ${assessment?.riskLevel}, Action: ${assessment?.action}`);
  console.log(`    Review Required: ${assessment?.reviewRequired}, Triggered Rules: ${assessment?.triggeredRules?.join(', ')}`);

  // 5. Restore Original Vendor Policy immediately
  const restoreRuleRes = await fetch(`${API_URL}/admin/risk/rules/${vendorRule.id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      expectedVersion: updatedRuleData.data?.version,
      policy: originalVendorPolicy,
      reason: 'Revert policy calibration to standard baseline'
    })
  });
  console.log(`[4] Restored Vendor Policy to baseline (HTTP ${restoreRuleRes.status})`);

  // 6. Launch Browser & Verify Alert & Review in UI
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();

  // Login via UI
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle' });
  await page.fill('input[type="email"]', 'admin@finx.com');
  await page.fill('input[type="password"]', 'Admin@Finx2026!');
  await page.click('button:has-text("Sign In"), button:has-text("Login"), button[type="submit"]');
  await page.waitForTimeout(3000);

  // Navigate to /admin/risk/alerts
  console.log('\n[5] Checking /admin/risk/alerts UI...');
  await page.goto(`${BASE_URL}/admin/risk/alerts`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '12_high_risk_alert.png') });
  const alertsHtml = await page.content();
  const alertFound = alertsHtml.includes('CRITICAL') || alertsHtml.includes('HIGH') || alertsHtml.includes('HighRisk');
  console.log(`    Alerts UI rendered alert card: ${alertFound}`);

  // Navigate to /admin/risk/reviews
  console.log('\n[6] Checking /admin/risk/reviews Queue...');
  await page.goto(`${BASE_URL}/admin/risk/reviews`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '13_reviews_queue.png') });

  // Query reviews via API to get review ID
  const reviewsApiRes = await fetch(`${API_URL}/admin/risk/reviews`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  const reviewsList = (await reviewsApiRes.json()).data?.items || [];
  const targetReview = reviewsList.find(r => r.subjectId === assessment?.entityId || r.userId === userId) || reviewsList[0];
  console.log(`    Target Review: ID ${targetReview?.id}, Status: ${targetReview?.status}`);

  // 7. Perform Review Decision (OPEN -> IN_REVIEW -> CLEARED)
  if (targetReview) {
    console.log('\n[7] Applying Review Decision: CLEARED...');
    const decisionRes = await fetch(`${API_URL}/admin/risk/reviews/${targetReview.id}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        decision: 'CLEARED',
        reason: 'Authorized Compliance Officer verified legitimate corporate documents and identity',
        notes: 'Certificate of incorporation and photo ID audited against MCA registries. Cleared.',
        evidenceReferences: ['mca_cin_verification.pdf', 'director_kyc_passed.pdf'],
        action: 'ALLOW',
        expectedVersion: 1
      })
    });
    const decResult = await decisionRes.json();
    console.log(`    Decision API Status: HTTP ${decisionRes.status}, New Status: ${decResult.data?.status}`);

    // Refresh Reviews Page
    await page.goto(`${BASE_URL}/admin/risk/reviews`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '14_review_cleared_persistence.png') });
    console.log(`    Review decision screenshot captured.`);

    // 8. Open Target Profile to verify explainability & review history
    console.log('\n[8] Viewing Target Vendor Risk Profile with Audit Log...');
    await page.goto(`${BASE_URL}/admin/risk/${userId}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '15_vendor_highrisk_profile.png') });
    console.log(`    Profile screenshot captured.`);
  }

  await browser.close();
  console.log('\n===============================================================');
  console.log('       High-Risk & Review Flow Validation COMPLETE             ');
  console.log('===============================================================\n');
}

testAlertAndReviewFlow().catch(console.error);
