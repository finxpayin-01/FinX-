import { chromium } from 'playwright';
import path from 'path';

async function capture() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();

  await page.goto('http://localhost:3000/login', { waitUntil: 'networkidle' });
  await page.fill('input[type="email"]', 'admin@finx.com');
  await page.fill('input[type="password"]', 'Admin@Finx2026!');
  await page.click('button:has-text("Sign In"), button:has-text("Login"), button[type="submit"]');
  await page.waitForTimeout(3000);

  // Reviews page
  await page.goto('http://localhost:3000/admin/risk/reviews', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.resolve('test-results/screenshots/14_reviews_cleared_state.png') });

  // Alerts page
  await page.goto('http://localhost:3000/admin/risk/alerts', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.resolve('test-results/screenshots/15_alerts_updated_state.png') });

  // Vendor profile
  await page.goto('http://localhost:3000/admin/risk/815214e8-053a-4a48-85d3-60c1d548ca3f', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.resolve('test-results/screenshots/16_vendor_cleared_profile.png') });

  await browser.close();
  console.log('Screenshots 14, 15, 16 captured successfully.');
}

capture().catch(console.error);
