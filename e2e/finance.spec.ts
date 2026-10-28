import { test, expect } from '@playwright/test';
import { loginAs, attachConsoleAudit, attachNetworkAudit } from './helpers';

test.describe('Finance Dashboard & API Contract Tests', () => {
  const consoleErrors: string[] = [];
  const networkIssues: string[] = [];

  test.beforeEach(async ({ page }) => {
    attachConsoleAudit(page, consoleErrors);
    attachNetworkAudit(page, networkIssues);
    await loginAs(page, 'ADMIN');
    await page.goto('/finance');
  });

  test('FIN-001: /finance renders overview metrics cards', async ({ page }) => {
    await page.goto('/finance');
    await page.waitForLoadState('domcontentloaded');

    // Verify presence of main headers / cards
    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/finance|settlement|volume|reconciliation/i);

    // Verify no broken duplicate path in network
    expect(networkIssues.filter((i) => i.includes('DUPLICATE_PREFIX')).length).toBe(0);
  });

  test('FIN-002: /finance/transactions renders transaction table and controls', async ({ page }) => {
    await page.goto('/finance/transactions');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/transaction|amount|status|date|provider|id/i);
  });

  test('FIN-003: /finance/reconciliation renders reconciliation overview and tabs', async ({ page }) => {
    await page.goto('/finance/reconciliation');
    await page.waitForLoadState('domcontentloaded');

    const bodyText = await page.textContent('body');
    expect(bodyText).toMatch(/reconciliation|statement|run|match|unmatched/i);
  });

  test('FIN-004: Direct API contract validation for /api/v1/finance/summary', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const token = localStorage.getItem('accessToken');
      const res = await fetch('http://localhost:8080/api/v1/finance/summary', {
        headers: { Authorization: `Bearer ${token}` }
      });
      const json = await res.json();
      return { status: res.status, data: json };
    });

    expect(result.status).toBe(200);
    expect(result.data.success).toBe(true);
    expect(result.data.data).toHaveProperty('totalSettledVolume');
    expect(result.data.data).toHaveProperty('totalInflowVolume');
    expect(result.data.data).toHaveProperty('pendingSettlementsCount');
    expect(result.data.data).toHaveProperty('totalInstructionsCount');
  });

  test('FIN-005: Direct API contract validation for /api/v1/finance/ledger', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const token = localStorage.getItem('accessToken');
      const res = await fetch('http://localhost:8080/api/v1/finance/ledger', {
        headers: { Authorization: `Bearer ${token}` }
      });
      const json = await res.json();
      return { status: res.status, data: json };
    });

    expect(result.status).toBe(200);
    expect(result.data.success).toBe(true);
    expect(result.data.data).toHaveProperty('1010-ESCROW-POOL');
    expect(result.data.data).toHaveProperty('1020-BANK-NODAL');
    expect(result.data.data).toHaveProperty('2010-ESCROW-LIABILITY');
  });

  test('FIN-006: Direct API contract validation for /api/v1/finance/transactions', async ({ page }) => {
    const result = await page.evaluate(async () => {
      const token = localStorage.getItem('accessToken');
      const res = await fetch('http://localhost:8080/api/v1/finance/transactions', {
        headers: { Authorization: `Bearer ${token}` }
      });
      const json = await res.json();
      return { status: res.status, data: json };
    });

    expect(result.status).toBe(200);
    expect(result.data.success).toBe(true);
    expect(Array.isArray(result.data.data)).toBe(true);
  });
});
