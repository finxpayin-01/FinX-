import { test, expect } from '@playwright/test';

test.describe('Google OAuth E2E Tests', () => {
  test('AUTH-GOOGLE-001: Google sign-in button is rendered on login page', async ({ page }) => {
    await page.goto('/login');
    const googleBtn = page.locator('button:has-text("Sign in with Google"), button:has-text("Continue with Google")');
    await expect(googleBtn).toBeVisible({ timeout: 10000 });
    await expect(googleBtn).toBeEnabled();
  });

  test('AUTH-GOOGLE-002: Clicking Google button initiates OAuth flow towards accounts.google.com', async ({ page }) => {
    await page.goto('/login');
    const googleBtn = page.locator('button:has-text("Sign in with Google"), button:has-text("Continue with Google")');
    await expect(googleBtn).toBeVisible();

    // Intercept authorization redirect to accounts.google.com
    let targetGoogleUrl = '';
    page.on('request', (req) => {
      if (req.url().includes('accounts.google.com/o/oauth2/v2/auth')) {
        targetGoogleUrl = req.url();
      }
    });

    await googleBtn.click();
    await page.waitForTimeout(4000);

    const currentUrl = page.url();
    const isGoogleAuth = currentUrl.includes('accounts.google.com') || targetGoogleUrl.includes('accounts.google.com');
    expect(isGoogleAuth).toBeTruthy();

    // Verify required OAuth parameters in authorization URL
    expect(targetGoogleUrl).toContain('response_type=code');
    expect(targetGoogleUrl).toContain('scope=openid');
    expect(targetGoogleUrl).toContain('prompt=select_account');
    expect(targetGoogleUrl).toContain('redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fauth%2Fgoogle%2Fcallback');
  });

  test('AUTH-GOOGLE-003: Callback page displays error message when state is invalid', async ({ page }) => {
    await page.goto('/auth/callback/google?error=invalid_state&error_description=OAuth+state+verification+failed.+Please+try+again.');
    await expect(page.locator('text=Google Authentication Failed')).toBeVisible({ timeout: 8000 });
    await expect(page.locator('text=OAuth state verification failed. Please try again.')).toBeVisible();
    await expect(page.locator('button:has-text("Return to Sign In")')).toBeVisible();
  });

  test('AUTH-GOOGLE-004: Callback page displays error message when user denies access', async ({ page }) => {
    await page.goto('/auth/callback/google?error=access_denied');
    await expect(page.locator('text=Authentication Failed')).toBeVisible({ timeout: 8000 });
    await expect(page.locator('text=Google authorization was cancelled by the user.')).toBeVisible();
  });
});
