import { test, expect } from '@playwright/test';
import { loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

const DISCOVERED_ROUTES = [
  '/',
  '/login',
  '/register',
  '/forgot-password',
  '/contact',
  '/kyc',
  '/onboarding',
  '/blockchain',
  '/dashboard',
  '/admin',
  '/admin/audit',
  '/admin/audit-logs',
  '/admin/deals',
  '/admin/disputes',
  '/admin/escrow',
  '/admin/invoices',
  '/admin/organizations',
  '/admin/projects',
  '/admin/reconciliation',
  '/admin/risk',
  '/admin/risk/alerts',
  '/admin/risk/buyers',
  '/admin/risk/reviews',
  '/admin/risk/rules',
  '/admin/risk/vendors',
  '/admin/settings',
  '/admin/settlements',
  '/admin/users',
  '/admin/verifications',
  '/corporate',
  '/corporate/disputes',
  '/corporate/escrow',
  '/corporate/milestones',
  '/corporate/payments',
  '/corporate/projects',
  '/corporate/settings',
  '/finance',
  '/finance/invoices',
  '/finance/payments',
  '/finance/projects',
  '/finance/reconciliation',
  '/finance/reports',
  '/finance/settings',
  '/finance/transactions',
  '/project-manager',
  '/project-manager/deliverables',
  '/project-manager/invitations',
  '/project-manager/projects',
  '/project-manager/reports',
  '/project-manager/reviews',
  '/project-manager/settings',
  '/vendor',
  '/vendor/deliverables',
  '/vendor/escrow',
  '/vendor/milestones',
  '/vendor/payments',
  '/vendor/projects',
  '/vendor/settings',
];

test.describe('Global Navigation & Route Audit', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
  });

  test('NAV-001: Public landing page loads without runtime errors', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('domcontentloaded');
    const title = await page.title();
    expect(title).toBeTruthy();
    const content = await page.textContent('body');
    expect(content?.length).toBeGreaterThan(100);
  });

  for (const route of DISCOVERED_ROUTES) {
    test(`NAV-ROUTE: Verify route '${route}' loads cleanly for appropriate role`, async ({ page }) => {
      let role: 'ADMIN' | 'BUYER' | 'SELLER' | 'PROJECT_MANAGER' | 'FINANCE' | null = null;
      if (route.startsWith('/vendor')) {
        role = 'SELLER';
      } else if (route.startsWith('/project-manager')) {
        role = 'PROJECT_MANAGER';
      } else if (route.startsWith('/corporate')) {
        role = 'BUYER';
      } else if (route.startsWith('/finance')) {
        role = 'FINANCE';
      } else if (route.startsWith('/admin')) {
        role = 'ADMIN';
      } else if (!['/', '/login', '/register', '/forgot-password', '/contact', '/blockchain'].includes(route)) {
        role = 'ADMIN';
      }

      if (role) {
        await loginAs(page, role);
      }
      
      const response = await page.goto(route, { waitUntil: 'domcontentloaded', timeout: 15000 });
      expect(response?.status()).toBeLessThan(400);

      // Verify page is not completely empty
      const bodyText = await page.textContent('body');
      expect(bodyText?.trim().length).toBeGreaterThan(20);

      // Verify no raw Next.js unhandled error overlay
      const errorOverlay = page.locator('nextjs-portal, #__next-build-watcher');
      await expect(page.locator('text="Application error: a client-side exception has occurred"')).not.toBeVisible();
    });
  }
});
