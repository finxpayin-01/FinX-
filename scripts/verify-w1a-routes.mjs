import { chromium } from 'playwright';

const FRONTEND_URL = 'http://localhost:3000';
const BACKEND_URL = 'http://localhost:8080';

async function main() {
    console.log('Testing W1-A targeted routes for double /api/api prefix...');

    // Login as ADMIN to get token
    const res = await fetch(`${BACKEND_URL}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: 'admin@finx.com', password: 'Admin@Finx2026!' })
    });
    const authData = await res.json();
    const token = authData.data.accessToken;
    const user = authData.data.user;

    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();

    await page.addInitScript(({ token, user }) => {
        localStorage.setItem('accessToken', token);
        localStorage.setItem('user', JSON.stringify(user));
    }, { token, user });

    const routesToTest = [
        '/onboarding',
        '/admin/deals',
        '/admin/settlements',
        '/admin/reconciliation',
        '/admin/organizations',
    ];

    let doubleApiCount = 0;
    const interceptedUrls = [];

    page.on('request', req => {
        const url = req.url();
        if (url.includes('/api/')) {
            interceptedUrls.push(url);
            if (url.includes('/api/api/')) {
                doubleApiCount++;
                console.error(`DOUBLE API DETECTED: ${url}`);
            }
        }
    });

    for (const r of routesToTest) {
        console.log(`Navigating to ${r}...`);
        try {
            await page.goto(`${FRONTEND_URL}${r}`, { waitUntil: 'domcontentloaded', timeout: 10000 });
            await page.waitForTimeout(1500);
        } catch (e) {
            console.error(`Error on ${r}:`, e.message);
        }
    }

    await browser.close();

    console.log('\n--- INTERCEPTED API CALLS ---');
    [...new Set(interceptedUrls)].forEach(u => console.log(' ', u));

    if (doubleApiCount > 0) {
        console.error(`\nFAILED: Detected ${doubleApiCount} requests with /api/api/ prefix!`);
        process.exit(1);
    } else {
        console.log('\nSUCCESS: ZERO /api/api/ requests detected across all targeted routes!');
        process.exit(0);
    }
}

main().catch(err => {
    console.error('Test execution error:', err);
    process.exit(1);
});
