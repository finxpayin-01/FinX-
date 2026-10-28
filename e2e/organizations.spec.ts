import { test, expect } from '@playwright/test';
import { loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

test.describe('Organizations, KYC/KYB & Bank Accounts', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
    await loginAs(page, 'BUYER');
  });

  test('ORG-001: /onboarding page loads with organization setup steps', async ({ page }) => {
    await page.goto('/onboarding');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/organization|entity|business|kyc|kyb|onboarding/i);
  });

  test('ORG-002: /kyc page loads verification form', async ({ page }) => {
    await page.goto('/kyc');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/kyc|verification|identity|pan|gstin|documents/i);
  });

  test('ORG-003: Admin organizations list page /admin/organizations renders', async ({ page }) => {
    await loginAs(page, 'ADMIN');
    await page.goto('/admin/organizations');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/organization|legal name|type|pan|status/i);
  });

  test('ORG-004: Admin verifications page /admin/verifications renders', async ({ page }) => {
    await loginAs(page, 'ADMIN');
    await page.goto('/admin/verifications');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/verification|kyc|kyb|status|review|check/i);
  });
});
