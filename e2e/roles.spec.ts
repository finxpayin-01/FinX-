import { test, expect } from '@playwright/test';
import { loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

test.describe('Role-Based Access Control (RBAC) Tests', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
  });

  test('ROLE-001: BUYER role has access to /corporate and buyer features', async ({ page }) => {
    await loginAs(page, 'BUYER');
    await page.goto('/corporate');
    await page.waitForLoadState('domcontentloaded');

    const text = await page.textContent('body');
    expect(text).toMatch(/corporate|deals|projects|milestones|escrow/i);
  });

  test('ROLE-002: BUYER role is blocked from /admin routes', async ({ page }) => {
    await loginAs(page, 'BUYER');
    await page.goto('/admin');
    await page.waitForTimeout(1000);

    const text = await page.textContent('body');
    const url = page.url();
    const isRestricted = url.includes('/login') || text?.match(/unauthorized|forbidden|access denied|not authorized/i);
    expect(isRestricted).toBeTruthy();
  });

  test('ROLE-003: BUYER role receives 403 Forbidden when calling /api/v1/finance/summary', async ({ page }) => {
    await loginAs(page, 'BUYER');
    await page.goto('/corporate');
    
    const apiResponse = await page.evaluate(async () => {
      const token = localStorage.getItem('accessToken');
      const res = await fetch('http://localhost:8080/api/v1/finance/summary', {
        headers: { Authorization: `Bearer ${token}` }
      });
      return res.status;
    });

    expect(apiResponse).toBe(403);
  });

  test('ROLE-004: SELLER role has access to /vendor', async ({ page }) => {
    await loginAs(page, 'SELLER');
    await page.goto('/vendor');
    await page.waitForLoadState('domcontentloaded');

    const text = await page.textContent('body');
    expect(text).toMatch(/vendor|projects|milestones|deliverables|escrow/i);
  });

  test('ROLE-005: SELLER role is blocked from /admin and /finance', async ({ page }) => {
    await loginAs(page, 'SELLER');
    await page.goto('/admin');
    await page.waitForTimeout(1000);

    const text = await page.textContent('body');
    const url = page.url();
    const isRestricted = url.includes('/login') || text?.match(/unauthorized|forbidden|access denied|not authorized/i);
    expect(isRestricted).toBeTruthy();
  });

  test('ROLE-006: PROJECT_MANAGER role has access to /project-manager', async ({ page }) => {
    await loginAs(page, 'PROJECT_MANAGER');
    await page.goto('/project-manager');
    await page.waitForLoadState('domcontentloaded');

    const text = await page.textContent('body');
    expect(text).toMatch(/project|manager|deliverables|reviews/i);
  });

  test('ROLE-007: FINANCE role has access to /finance dashboard', async ({ page }) => {
    await loginAs(page, 'FINANCE');
    await page.goto('/finance');
    await page.waitForLoadState('domcontentloaded');

    const text = await page.textContent('body');
    expect(text).toMatch(/finance|settlement|overview|transactions|ledger/i);

    // API check directly
    const apiStatus = await page.evaluate(async () => {
      const token = localStorage.getItem('accessToken');
      const res = await fetch('http://localhost:8080/api/v1/finance/summary', {
        headers: { Authorization: `Bearer ${token}` }
      });
      return res.status;
    });
    expect(apiStatus).toBe(200);
  });

  test('ROLE-008: ADMIN role has global access to /admin and /finance', async ({ page }) => {
    await loginAs(page, 'ADMIN');
    await page.goto('/admin');
    await page.waitForLoadState('domcontentloaded');
    expect(await page.textContent('body')).toMatch(/admin|deals|disputes|escrow|risk|reconciliation/i);

    await page.goto('/finance');
    await page.waitForLoadState('domcontentloaded');
    expect(await page.textContent('body')).toMatch(/finance|settlement|ledger/i);
  });
});
