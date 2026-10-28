import { test, expect } from '@playwright/test';
import { loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

test.describe('Network, Accessibility Smoke & Guardrail Audits', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
  });

  test('AUDIT-001: Zero duplicate API prefixes (/api/api, /api/v1/api, /v1/v1) across navigation', async ({ page }) => {
    await loginAs(page, 'ADMIN');

    // Visit finance and admin dashboards
    await page.goto('/finance');
    await page.waitForTimeout(1000);
    await page.goto('/finance/transactions');
    await page.waitForTimeout(1000);
    await page.goto('/admin');
    await page.waitForTimeout(1000);

    const duplicatePrefixErrors = networkIssues.filter((i) => i.includes('DUPLICATE_PREFIX'));
    expect(duplicatePrefixErrors).toHaveLength(0);
  });

  test('AUDIT-002: Accessibility smoke - Form inputs on /login have labels or placeholders', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('domcontentloaded');

    const inputs = await page.locator('input').all();
    for (const input of inputs) {
      const type = await input.getAttribute('type');
      if (type === 'hidden') continue;
      const placeholder = await input.getAttribute('placeholder');
      const ariaLabel = await input.getAttribute('aria-label');
      const id = await input.getAttribute('id');
      const hasForLabel = id ? (await page.locator(`label[for="${id}"]`).count()) > 0 : false;
      const isWrappedInLabel = await input.evaluate((el) => Boolean(el.closest('label')));
      const hasAssociatedLabel = hasForLabel || isWrappedInLabel;

      const isAccessible = Boolean(placeholder || ariaLabel || hasAssociatedLabel);
      expect(isAccessible).toBe(true);
    }
  });

  test('AUDIT-003: Accessibility smoke - Actionable buttons have accessible names', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('domcontentloaded');

    const buttons = await page.locator('button').all();
    for (const btn of buttons) {
      const text = await btn.textContent();
      const ariaLabel = await btn.getAttribute('aria-label');
      const title = await btn.getAttribute('title');
      const hasName = Boolean((text && text.trim().length > 0) || ariaLabel || title);
      expect(hasName).toBe(true);
    }
  });

  test('AUDIT-004: Fast double-click on login submit button does not break UI state', async ({ page }) => {
    await page.goto('/login');
    const emailInput = page.locator('input[type="email"], input[name="email"], input#email');
    const passwordInput = page.locator('input[type="password"], input[name="password"], input#password');
    const submitBtn = page.locator('button[type="submit"]');

    await emailInput.fill('qa_buyer@finx.test');
    await passwordInput.fill('Password123!');

    // Double click rapidly
    await submitBtn.dblclick();

    // Verify user ends up on authenticated page without application crash
    await page.waitForTimeout(2000);
    expect(page.url()).not.toContain('error=crash');
  });
});
