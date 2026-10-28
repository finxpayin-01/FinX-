import { test, expect } from '@playwright/test';
import { loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

const VIEWPORTS = [
  { name: 'Desktop (1440x900)', width: 1440, height: 900 },
  { name: 'Laptop (1280x720)', width: 1280, height: 720 },
  { name: 'Tablet (768x1024)', width: 768, height: 1024 },
  { name: 'Mobile (390x844)', width: 390, height: 844 },
];

test.describe('Responsive Layout & Viewport Tests', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
  });

  for (const vp of VIEWPORTS) {
    test(`RESP-001: Landing page renders cleanly at ${vp.name}`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await page.goto('/');
      await page.waitForLoadState('domcontentloaded');

      const body = page.locator('body');
      await expect(body).toBeVisible();

      // Check no severe horizontal overflow
      const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
      expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 20); // Small threshold for scrollbar
    });

    test(`RESP-002: Login page renders responsive inputs at ${vp.name}`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await page.goto('/login');
      await page.waitForLoadState('domcontentloaded');

      const submitBtn = page.locator('button[type="submit"]');
      await expect(submitBtn).toBeVisible();
    });

    test(`RESP-003: Finance dashboard layout responsive at ${vp.name}`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await loginAs(page, 'ADMIN');
      await page.goto('/finance');
      await page.waitForLoadState('domcontentloaded');

      const bodyText = await page.textContent('body');
      expect(bodyText).toMatch(/finance/i);
    });
  }
});
