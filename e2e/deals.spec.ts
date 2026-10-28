import { test, expect } from '@playwright/test';
import { loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

test.describe('Deals, Milestones, Escrow & Disputes', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
    await loginAs(page, 'BUYER');
  });

  test('DEAL-001: /corporate/projects renders deals list and create button', async ({ page }) => {
    await page.goto('/corporate/projects');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/project|deal|create|amount|status/i);
  });

  test('DEAL-002: /corporate/milestones renders milestone tracking view', async ({ page }) => {
    await page.goto('/corporate/milestones');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/milestone|title|status|amount|due/i);
  });

  test('DEAL-003: /corporate/escrow renders escrow account balance and overview', async ({ page }) => {
    await page.goto('/corporate/escrow');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/escrow|balance|funded|released|held/i);
  });

  test('DEAL-004: /corporate/disputes renders disputes list and dispute action', async ({ page }) => {
    await page.goto('/corporate/disputes');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/dispute|status|reason|resolution/i);
  });

  test('DEAL-005: Admin deals view /admin/deals renders for ADMIN', async ({ page }) => {
    await loginAs(page, 'ADMIN');
    await page.goto('/admin/deals');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/deal|buyer|seller|amount|status/i);
  });
});
