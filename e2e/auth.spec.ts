import { test, expect } from '@playwright/test';
import { QA_USERS, loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

test.describe('Authentication & Session Tests', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
  });

  test('AUTH-001: Login page renders with expected fields', async ({ page }) => {
    await page.goto('/login');
    await expect(page).toHaveTitle(/FinX/i);

    // Verify presence of email, password inputs, submit button
    const emailInput = page.locator('input[type="email"], input[name="email"], input#email');
    const passwordInput = page.locator('input[type="password"], input[name="password"], input#password');
    const submitBtn = page.locator('button[type="submit"]');

    await expect(emailInput).toBeVisible();
    await expect(passwordInput).toBeVisible();
    await expect(submitBtn).toBeVisible();
  });

  test('AUTH-002: Login with valid credentials succeeds and establishes session', async ({ page }) => {
    await page.goto('/login');

    const emailInput = page.locator('input[type="email"], input[name="email"], input#email');
    const passwordInput = page.locator('input[type="password"], input[name="password"], input#password');
    const submitBtn = page.locator('button[type="submit"]');

    await emailInput.fill(QA_USERS.BUYER.email);
    await passwordInput.fill(QA_USERS.BUYER.password);
    await submitBtn.click();

    // Should redirect to /corporate, /dashboard, or /buyer
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });

    // Verify localStorage has accessToken
    const token = await page.evaluate(() => localStorage.getItem('accessToken'));
    expect(token).toBeTruthy();
  });

  test('AUTH-003: Login with wrong password displays error message', async ({ page }) => {
    await page.goto('/login');

    const emailInput = page.locator('input[type="email"], input[name="email"], input#email');
    const passwordInput = page.locator('input[type="password"], input[name="password"], input#password');
    const submitBtn = page.locator('button[type="submit"]');

    await emailInput.fill(QA_USERS.BUYER.email);
    await passwordInput.fill('WrongPassword999!');
    await submitBtn.click();

    // Should stay on /login and show error banner or text
    await page.waitForTimeout(1000);
    expect(page.url()).toContain('/login');
    const pageText = await page.textContent('body');
    expect(pageText).toMatch(/invalid|failed|incorrect|credentials|error/i);
  });

  test('AUTH-004: Login with empty fields triggers HTML5 / client validation', async ({ page }) => {
    await page.goto('/login');

    const submitBtn = page.locator('button[type="submit"]');
    await submitBtn.click();

    // Client-side validation should keep user on /login
    expect(page.url()).toContain('/login');
  });

  test('AUTH-005: Session persists across full page reload', async ({ page }) => {
    await loginAs(page, 'BUYER');
    await page.goto('/corporate');

    // Reload page
    await page.reload();
    await page.waitForTimeout(1000);

    // Verify still authenticated
    const token = await page.evaluate(() => localStorage.getItem('accessToken'));
    expect(token).toBeTruthy();
  });

  test('AUTH-006: Unauthenticated user is redirected or denied access to protected route', async ({ page }) => {
    // Clear tokens
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.clear();
      sessionStorage.clear();
    });

    await page.goto('/finance');
    await page.waitForTimeout(1500);

    // Should either redirect to /login or show unauthorized / access denied
    const currentUrl = page.url();
    const bodyText = await page.textContent('body');
    const isLogin = currentUrl.includes('/login');
    const isDenied = bodyText?.match(/denied|unauthorized|forbidden|sign in|login/i);
    expect(isLogin || isDenied).toBeTruthy();
  });
});
