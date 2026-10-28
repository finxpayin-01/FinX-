import { Page, expect } from '@playwright/test';

export interface TestUser {
  name: string;
  email: string;
  role: string;
  password: string;
}

export const QA_USERS: Record<string, TestUser> = {
  BUYER: {
    name: 'QA Buyer',
    email: 'qa_buyer@finx.test',
    role: 'BUYER',
    password: 'Password123!',
  },
  SELLER: {
    name: 'QA Seller',
    email: 'qa_seller@finx.test',
    role: 'SELLER',
    password: 'Password123!',
  },
  PROJECT_MANAGER: {
    name: 'QA PM',
    email: 'qa_pm@finx.test',
    role: 'PROJECT_MANAGER',
    password: 'Password123!',
  },
  FINANCE: {
    name: 'QA Finance',
    email: 'qa_finance@finx.test',
    role: 'FINANCE',
    password: 'Password123!',
  },
  ADMIN: {
    name: 'QA Admin',
    email: 'qa_admin@finx.test',
    role: 'ADMIN',
    password: 'Password123!',
  },
};

const tokenCache: Record<string, { accessToken: string; refreshToken: string; user: any }> = {};

/**
 * Log in via the backend API and inject tokens into localStorage before loading the page.
 */
export async function loginAs(page: Page, roleKey: keyof typeof QA_USERS) {
  const user = QA_USERS[roleKey];
  
  if (!tokenCache[roleKey]) {
    const response = await fetch('http://localhost:8080/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: user.email, password: user.password }),
    });
    
    const data = await response.json();
    if (!data.success || !data.data?.accessToken) {
      throw new Error(`Failed to log in as ${roleKey}: ${JSON.stringify(data)}`);
    }

    const { accessToken, refreshToken, user: userData } = data.data;
    tokenCache[roleKey] = { accessToken, refreshToken, user: userData };
  }

  const { accessToken, refreshToken, user: userData } = tokenCache[roleKey];

  await page.addInitScript(
    ({ accessToken, refreshToken, userData }) => {
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', refreshToken);
      localStorage.setItem('user', JSON.stringify(userData));
    },
    { accessToken, refreshToken, userData }
  );
}

/**
 * Audit console errors on page.
 */
export function attachConsoleAudit(page: Page, errors: string[]) {
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      const text = msg.text();
      // Ignore routine 403 or network errors that are intentionally tested
      errors.push(`[${page.url()}] ${text}`);
    }
  });

  page.on('pageerror', (err) => {
    errors.push(`[UNCAUGHT] ${err.message}`);
  });
}

/**
 * Audit network requests for duplicate prefixes or 500 errors.
 */
export function attachNetworkAudit(page: Page, networkIssues: string[]) {
  page.on('request', (req) => {
    const url = req.url();
    if (url.includes('/api/api/') || url.includes('/api/v1/api/') || url.includes('/v1/v1/')) {
      networkIssues.push(`[DUPLICATE_PREFIX] ${req.method()} ${url}`);
    }
  });

  page.on('response', (res) => {
    if (res.status() >= 500) {
      networkIssues.push(`[SERVER_ERROR_${res.status()}] ${res.request().method()} ${res.url()}`);
    }
  });
}
