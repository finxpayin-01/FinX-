import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

async function runFundingReadinessVerification() {
  console.log('====================================================');
  console.log('   FINX FUNDING READINESS & GOVERNANCE E2E TEST     ');
  console.log('====================================================\n');

  const screenshotsDir = path.join(process.cwd(), 'test-results', 'funding-verification');
  if (!fs.existsSync(screenshotsDir)) {
    fs.mkdirSync(screenshotsDir, { recursive: true });
  }

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();

  const results = {
    loginSuccess: false,
    projectFinancialsInr: false,
    escrowHeldZero: false,
    governanceChecklistVisible: false,
    fundingLockedBadgeVisible: false,
    milestone1ApprovedUnfunded: false,
    milestone2PendingFundingUnfunded: false,
    milestone2FundButtonLocked: false,
    milestoneDetailsPageGated: false,
    paymentsPageHonestZeroFunded: false,
    paymentCheckoutGatedAndDisabled: false
  };

  try {
    // STEP 1: Login as Demo Buyer
    console.log('[STEP 1] Logging in as demo.buyer@example.com...');
    await page.goto('http://localhost:3000/login', { waitUntil: 'networkidle' });
    await page.fill('input[type="email"]', 'demo.buyer@example.com');
    await page.fill('input[type="password"]', 'Password123!');
    await page.click('button[type="submit"]');

    await page.waitForURL(url => url.pathname.includes('/corporate') || url.pathname.includes('/dashboard'), { timeout: 10000 });
    console.log('  -> Logged in successfully. Current URL:', page.url());
    results.loginSuccess = true;

    // STEP 2: Navigate to Demo Project Details Page
    const dealId = 'a5a23608-0f62-4a51-944c-990ceb8875b2';
    console.log(`\n[STEP 2] Navigating to Project Details page: /corporate/projects/${dealId}...`);
    await page.goto(`http://localhost:3000/corporate/projects/${dealId}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    await page.screenshot({ path: path.join(screenshotsDir, '01_project_details_governance.png'), fullPage: true });

    const pageText = await page.textContent('body');

    // 2A: Verify Financials Card displays ₹1,00,000 INR
    if (pageText.includes('1,00,000') && !pageText.includes('$100,000')) {
      results.projectFinancialsInr = true;
      console.log('  -> [PASS] Total Deal Value correctly rendered in INR (₹1,00,000).');
    } else {
      console.log('  -> [WARN] Financials text check failed.');
    }

    // 2B: Verify Escrow Vault Held is ₹0 (Unfunded)
    if (pageText.includes('Escrow Vault Held') && pageText.includes('Unfunded (₹0 held)')) {
      results.escrowHeldZero = true;
      console.log('  -> [PASS] Escrow Vault Held correctly indicates ₹0 (Unfunded).');
    } else {
      console.log('  -> [WARN] Could not find Escrow Vault Held unfunded badge.');
    }

    // 2C: Verify Governance & Funding Readiness Checklist
    const hasLockTitle = pageText.includes('Vendor Accepted · Escrow Funding Locked');
    const hasBuyerOrgCheck = pageText.includes('Buyer Corporate Entity') && pageText.includes('Pending Verification / Unbound');
    const hasSellerOrgCheck = pageText.includes('Vendor Corporate Entity') && pageText.includes('Pending Verification / Unbound');
    const hasAgreementCheck = pageText.includes('Bilateral Agreement') && pageText.includes('Draft (Unsigned)');
    const hasSandboxRail = pageText.includes('Mock Banking Sandbox');

    if (hasLockTitle && hasBuyerOrgCheck && hasSellerOrgCheck && hasAgreementCheck && hasSandboxRail) {
      results.governanceChecklistVisible = true;
      results.fundingLockedBadgeVisible = true;
      console.log('  -> [PASS] Funding Readiness & Governance checklist rendered with all 5 verification states:');
      console.log('     ✓ Vendor Scope: Accepted');
      console.log('     ✓ Buyer Corporate Entity: Pending Verification / Unbound');
      console.log('     ✓ Vendor Corporate Entity: Pending Verification / Unbound');
      console.log('     ✓ Bilateral Agreement: Draft (Unsigned)');
      console.log('     ✓ Banking Nodal Rail: Mock Banking Sandbox');
    } else {
      console.log('  -> [WARN] Governance checklist elements missing. Summary:', {
        hasLockTitle, hasBuyerOrgCheck, hasSellerOrgCheck, hasAgreementCheck, hasSandboxRail
      });
    }

    // 2D: Verify Milestone Status Distinctions in Table
    if (pageText.includes('Deliverables Approved · Escrow Unfunded (₹0 held)')) {
      results.milestone1ApprovedUnfunded = true;
      console.log('  -> [PASS] Milestone 1 accurately distinguished as "Deliverables Approved · Escrow Unfunded (₹0 held)".');
    } else {
      console.log('  -> [WARN] Milestone 1 unfunded description not found.');
    }

    if (pageText.includes('Pending Funding (Unfunded)')) {
      results.milestone2PendingFundingUnfunded = true;
      console.log('  -> [PASS] Milestone 2 accurately distinguished as "Pending Funding (Unfunded)".');
    } else {
      console.log('  -> [WARN] Milestone 2 unfunded description not found.');
    }

    // 2E: Verify Fund Button is Disabled / Locked
    const lockedButton = page.locator('button:has-text("Fund (Locked)")').first();
    const isLockedDisabled = await lockedButton.isDisabled().catch(() => false);
    if (isLockedDisabled) {
      results.milestone2FundButtonLocked = true;
      console.log('  -> [PASS] Milestone 2 Fund button is disabled: "Fund (Locked)".');
    } else {
      console.log('  -> [WARN] Fund (Locked) button was not disabled or not found.');
    }

    // STEP 3: Navigate to Milestone 1 Details Page
    const milestone1Id = 'd8ae2384-7884-4a65-acfd-c8752a02b360';
    console.log(`\n[STEP 3] Navigating to Milestone 1 Details: /corporate/projects/${dealId}/milestones/${milestone1Id}...`);
    await page.goto(`http://localhost:3000/corporate/projects/${dealId}/milestones/${milestone1Id}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);

    await page.screenshot({ path: path.join(screenshotsDir, '02_milestone1_approved_unfunded.png'), fullPage: true });

    const m1Text = await page.textContent('body');
    const m1FundLocked = await page.locator('button:has-text("Fund Milestone (Locked)")').first().isDisabled().catch(() => false);
    const m1Notice = m1Text.includes('Escrow account holds ₹0.00 — funding is required before vendor release can be executed');

    if (m1FundLocked && m1Notice) {
      results.milestoneDetailsPageGated = true;
      console.log('  -> [PASS] Milestone 1 details page prevents unauthorized release on unfunded escrow and locks funding.');
    } else {
      console.log('  -> [WARN] Milestone 1 details gating check:', { m1FundLocked, m1Notice });
    }

    // STEP 4: Navigate to Project Payments Hub
    console.log(`\n[STEP 4] Navigating to Project Payments Hub: /corporate/projects/${dealId}/payments...`);
    await page.goto(`http://localhost:3000/corporate/projects/${dealId}/payments`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);

    await page.screenshot({ path: path.join(screenshotsDir, '03_project_payments_hub.png'), fullPage: true });

    const paymentsText = await page.textContent('body');
    const hasZeroCurrentlyFunded = paymentsText.includes('Currently Funded (Escrow Vault)') && paymentsText.includes('₹0');
    const hasApprovedUnfundedSchedule = paymentsText.includes('Approved (Escrow Unfunded)');
    const notFalselySecured = !paymentsText.includes('Secured in Escrow');

    if (hasZeroCurrentlyFunded && hasApprovedUnfundedSchedule && notFalselySecured) {
      results.paymentsPageHonestZeroFunded = true;
      console.log('  -> [PASS] Project Payments Hub honestly reports ₹0 Currently Funded and "Approved (Escrow Unfunded)".');
    } else {
      console.log('  -> [WARN] Payments Hub check:', { hasZeroCurrentlyFunded, hasApprovedUnfundedSchedule, notFalselySecured });
    }

    // STEP 5: Direct Access to Checkout Page
    const milestone2Id = '6685340e-9ea4-4e40-9048-4d6fbae59e47';
    console.log(`\n[STEP 5] Navigating to Checkout Page: /corporate/projects/${dealId}/milestones/${milestone2Id}/payment...`);
    await page.goto(`http://localhost:3000/corporate/projects/${dealId}/milestones/${milestone2Id}/payment`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);

    await page.screenshot({ path: path.join(screenshotsDir, '04_checkout_governance_locked.png'), fullPage: true });

    const checkoutText = await page.textContent('body');
    const hasPolicyWarning = checkoutText.includes('Escrow Funding Blocked by Governance Policy');
    const checkoutButtonLocked = await page.locator('button:has-text("Funding Prerequisites Incomplete (Locked)")').first().isDisabled().catch(() => false);

    if (hasPolicyWarning && checkoutButtonLocked) {
      results.paymentCheckoutGatedAndDisabled = true;
      console.log('  -> [PASS] Direct checkout blocked by governance policy with disabled payment button.');
    } else {
      console.log('  -> [WARN] Checkout page gating check:', { hasPolicyWarning, checkoutButtonLocked });
    }

  } catch (err) {
    console.error('\n[EXCEPTION ENCOUNTERED]:', err.message);
  } finally {
    await browser.close();
  }

  console.log('\n====================================================');
  console.log('   VERIFICATION SUMMARY');
  console.log('====================================================');
  console.table(results);

  const allPassed = Object.values(results).every(v => v === true);
  if (allPassed) {
    console.log('\n>>> ALL 11 GOVERNANCE & FUNDING FLOW VERIFICATIONS PASSED SUCCESSFULLY! <<<');
  } else {
    console.log('\n>>> SOME VERIFICATIONS WERE NOT SATISFIED <<<');
  }
}

runFundingReadinessVerification();
