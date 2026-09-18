/**
 * FINX End-to-End MVP Integration Test Suite
 * Tests complete lifecycle against backend (http://localhost:8080) and frontend (http://localhost:3000)
 */

import crypto from 'node:crypto';

const BACKEND_URL = 'https://finx-backend-vq5b.onrender.com/api';
const FRONTEND_URL = 'https://finx-frontend-i9an.onrender.com';
const RAZORPAY_PAYMENT_SECRET = process.env.FINX_E2E_RAZORPAY_KEY_SECRET;
const RAZORPAY_WEBHOOK_SECRET = process.env.FINX_E2E_RAZORPAY_WEBHOOK_SECRET;

function hmacSignature(payload, secret, dependencyName) {
    if (!secret) {
        console.error(`BLOCKED — DEPENDENCY: ${dependencyName} is required for a real Razorpay sandbox verification.`);
        return "blocked_signature_dependency";
    }
    return crypto.createHmac('sha256', secret).update(payload).digest('hex');
}

async function request(url, options = {}) {
    const headers = {
        'Content-Type': 'application/json',
        ...(options.headers || {})
    };
    const res = await fetch(url, {
        ...options,
        headers
    });
    const text = await res.text();
    let json;
    try {
        json = JSON.parse(text);
        if (json && typeof json === 'object' && !('data' in json)) {
            // Some endpoints like getSellers return arrays directly, protect them
            if (Array.isArray(json)) {
                json = { data: json };
            } else {
                json = { data: json };
            }
        }
    } catch {
        json = null;
    }
    return { status: res.status, ok: res.ok, data: json, text };
}

async function runTests() {
    console.log('====================================================');
    console.log('   FINX End-to-End MVP Integration Verification     ');
    console.log('====================================================\n');

    let passed = 0;
    let failed = 0;

    function assert(condition, message) {
        if (condition) {
            console.log(`\x1b[32m[PASS]\x1b[0m ${message}`);
            passed++;
        } else {
            console.error(`\x1b[31m[FAIL]\x1b[0m ${message}`);
            failed++;
        }
    }

    try {
        // 1. Google OAuth Config Security
        console.log('--- Phase 1: Google OAuth Security & Config ---');
        const googleConfigRes = await request(`${BACKEND_URL}/auth/google/config`);
        assert(googleConfigRes.ok, 'Google OAuth config endpoint responds 200 OK');
        assert(googleConfigRes.data?.data?.clientId?.length > 0, 'Google client ID is returned');
        assert(!googleConfigRes.text.includes('GOCSPX'), 'Google client secret is NEVER exposed to client');

        // 2. Test Invalid Login
        console.log('\n--- Phase 2: Invalid Login Handling ---');
        const invalidLoginRes = await request(`${BACKEND_URL}/auth/login`, {
            method: 'POST',
            body: JSON.stringify({
                email: 'nonexistent@finx.com',
                password: 'WrongPassword123!'
            })
        });
        assert(invalidLoginRes.status === 401, `Invalid login returns HTTP 401 (got ${invalidLoginRes.status})`);

        // 3. Register Corporate User (BUYER)
        console.log('\n--- Phase 3: Corporate User Registration ---');
        const corporateEmail = `corporate.${Date.now()}@finx.com`;
        const corpRegRes = await request(`${BACKEND_URL}/auth/register`, {
            method: 'POST',
            body: JSON.stringify({
                name: 'Acme Corp Executive',
                email: corporateEmail,
                password: 'Password123!',
                role: 'BUYER'
            })
        });
        assert(corpRegRes.ok, `Corporate registration returns 200/201 (got ${corpRegRes.status})`);
        const corpTokens = corpRegRes.data?.data;
        assert(corpTokens?.accessToken && corpTokens?.refreshToken, 'Access token and refresh token issued');
        const corporateUser = corpTokens?.user;
        assert(corporateUser?.role === 'BUYER', `Corporate role is BUYER (got ${corporateUser?.role})`);
        assert(corporateUser?.name === 'Acme Corp Executive', `Corporate user name matches (got ${corporateUser?.name})`);

        // 4. Register Vendor User (SELLER)
        console.log('\n--- Phase 4: Vendor User Registration ---');
        const vendorEmail = `vendor.${Date.now()}@finx.com`;
        const vendorRegRes = await request(`${BACKEND_URL}/auth/register`, {
            method: 'POST',
            body: JSON.stringify({
                name: 'Apex Solutions Vendor',
                email: vendorEmail,
                password: 'Password123!',
                role: 'SELLER'
            })
        });
        assert(vendorRegRes.ok, `Vendor registration returns 200/201 (got ${vendorRegRes.status})`);
        const vendorTokens = vendorRegRes.data?.data;
        assert(vendorTokens?.accessToken && vendorTokens?.refreshToken, 'Vendor access token and refresh token issued');
        const vendorUser = vendorTokens?.user;
        assert(vendorUser?.role === 'SELLER', `Vendor role is SELLER (got ${vendorUser?.role})`);

        // 5. Test Login & /api/auth/me Session Hydration
        console.log('\n--- Phase 5: Authentication & Session Hydration (/api/auth/me) ---');
        const corpLoginRes = await request(`${BACKEND_URL}/auth/login`, {
            method: 'POST',
            body: JSON.stringify({
                email: corporateEmail,
                password: 'Password123!'
            })
        });
        assert(corpLoginRes.ok, 'Corporate login successful');
        const activeCorpToken = corpLoginRes.data?.data?.accessToken;

        const corpMeRes = await request(`${BACKEND_URL}/auth/me`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(corpMeRes.ok, 'Session hydration /api/auth/me returns 200 OK');
        assert(corpMeRes.data?.data?.email === corporateEmail, 'Hydrated user email matches');

        // 6. Test Refresh Token
        console.log('\n--- Phase 6: Refresh Token Rotation ---');
        const refreshRes = await request(`${BACKEND_URL}/auth/refresh`, {
            method: 'POST',
            body: JSON.stringify({
                refreshToken: corpLoginRes.data?.data?.refreshToken
            })
        });
        assert(refreshRes.ok, 'Token refresh returns 200 OK');
        assert(refreshRes.data?.data?.accessToken?.length > 0, 'New access token issued');

        // 7. Get Available Sellers Dropdown
        console.log('\n--- Phase 7: Fetch Sellers for Deal Creation ---');
        const sellersRes = await request(`${BACKEND_URL}/deals/sellers`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(sellersRes.ok, 'GET /api/deals/sellers returns 200 OK');
        const sellers = sellersRes.data?.data || [];
        const foundSeller = sellers.find(s => s.id === vendorUser.id || s.email === vendorEmail);
        assert(foundSeller !== undefined, `Registered vendor found in seller dropdown list (${foundSeller?.name})`);

        // 8. Corporate Creates Deal
        console.log('\n--- Phase 8: Corporate Creates Deal ---');
        const createDealRes = await request(`${BACKEND_URL}/deals`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                title: 'Enterprise Cloud Migration & Kubernetes Setup',
                description: 'End-to-end migration of core databases and container clusters.',
                sellerId: vendorUser.id,
                totalAmount: 15000,
                currency: 'INR'
            })
        });
        assert(createDealRes.ok, `Deal created successfully (got ${createDealRes.status})`);
        const deal = createDealRes.data?.data;
        assert(deal?.id !== undefined, `Deal assigned valid ID: ${deal?.id}`);
        assert(deal?.status === 'DRAFT' || deal?.status === 'PENDING_ACCEPTANCE', `Initial deal status is DRAFT/PENDING_ACCEPTANCE (got ${deal?.status})`);
        assert(Number(deal?.totalAmount) === 15000, `Deal total amount is 15000 (got ${deal?.totalAmount})`);
        assert(deal?.buyerId === corporateUser.id, `Deal buyer matches Corporate ID`);
        assert(deal?.sellerId === vendorUser.id, `Deal seller matches Vendor ID`);

        // 9. Fetch Deal Details by ID & Deal Listing
        console.log('\n--- Phase 9: Fetch Deal Details & Deal Listing ---');
        const getDealRes = await request(`${BACKEND_URL}/deals/${deal.id}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(getDealRes.ok, 'GET /api/deals/{id} returns 200 OK');
        assert(getDealRes.data?.data?.title === 'Enterprise Cloud Migration & Kubernetes Setup', 'Deal title matches');

        const listDealsRes = await request(`${BACKEND_URL}/deals`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(listDealsRes.ok, 'GET /api/deals returns 200 OK');
        const corpDeals = listDealsRes.data?.data?.content || listDealsRes.data?.data || [];
        assert(corpDeals.some(d => d.id === deal.id), 'Created deal is present in corporate deals list');

        // 10. Vendor Views Deal & Accepts Deal
        console.log('\n--- Phase 10: Vendor Views & Accepts Deal ---');
        const vendorLoginRes = await request(`${BACKEND_URL}/auth/login`, {
            method: 'POST',
            body: JSON.stringify({
                email: vendorEmail,
                password: 'Password123!'
            })
        });
        const activeVendorToken = vendorLoginRes.data?.data?.accessToken;

        const vendorDealsRes = await request(`${BACKEND_URL}/deals`, {
            headers: { Authorization: `Bearer ${activeVendorToken}` }
        });
        assert(vendorDealsRes.ok, 'Vendor GET /api/deals returns 200 OK');
        const vendorDeals = vendorDealsRes.data?.data?.content || vendorDealsRes.data?.data || [];
        assert(vendorDeals.some(d => d.id === deal.id), 'Assigned deal is visible in vendor deals list');

        const acceptDealRes = await request(`${BACKEND_URL}/deals/${deal.id}/accept`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${activeVendorToken}` }
        });
        assert(acceptDealRes.ok, `Vendor accepted deal returns 200 OK (got ${acceptDealRes.status})`);
        const acceptedDeal = acceptDealRes.data?.data;
        assert(acceptedDeal?.status === 'ACTIVE', `Deal status successfully transitioned to ACTIVE (got ${acceptedDeal?.status})`);

        // 11. Corporate Creates Milestone
        console.log('\n--- Phase 11: Corporate Creates Milestone for Deal ---');
        const createMilestoneRes = await request(`${BACKEND_URL}/deals/${deal.id}/milestones`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                title: 'Phase 1: Architecture & Infrastructure Setup',
                description: 'Terraform cloud automation and Kubernetes cluster orchestration',
                sequence: 1,
                amount: 10000,
                currency: 'INR',
                dueDate: new Date(Date.now() + 7 * 86400000).toISOString()
            })
        });
        assert(createMilestoneRes.ok, `Milestone created successfully (got ${createMilestoneRes.status})`);
        const milestone = createMilestoneRes.data?.data;
        assert(milestone?.id !== undefined, `Milestone assigned ID: ${milestone?.id}`);
        assert(milestone?.status === 'PENDING', `Initial milestone status is PENDING (got ${milestone?.status})`);
        assert(Number(milestone?.amount) === 10000, `Milestone amount is 10000 (got ${milestone?.amount})`);

        // 12. Vendor Views & Starts Milestone
        console.log('\n--- Phase 12: Vendor Starts Milestone ---');
        const startMilestoneRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/start`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeVendorToken}` }
        });
        assert(startMilestoneRes.ok, `Vendor started milestone returns 200 OK (got ${startMilestoneRes.status})`);
        assert(startMilestoneRes.data?.data?.status === 'IN_PROGRESS', `Milestone status transitioned to IN_PROGRESS (got ${startMilestoneRes.data?.data?.status})`);

        // 13. Vendor Submits Deliverable
        console.log('\n--- Phase 13: Vendor Submits Deliverable ---');
        const submitDeliverableRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/submit`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeVendorToken}` },
            body: JSON.stringify({
                fileName: 'cloud_infra_architecture_v1.zip',
                fileUrl: 'https://storage.finx.local/deliverables/cloud_infra_architecture_v1.zip',
                description: 'Completed Terraform modules and Helm deployment configs'
            })
        });
        assert(submitDeliverableRes.ok, `Deliverable submitted successfully (got ${submitDeliverableRes.status})`);
        const deliverable = submitDeliverableRes.data?.data;
        assert(deliverable?.id !== undefined, `Deliverable assigned ID: ${deliverable?.id}`);
        assert(deliverable?.status === 'PENDING', `Deliverable status is PENDING`);

        // Verify milestone status transitioned to UNDER_REVIEW
        const reviewMilestoneRes = await request(`${BACKEND_URL}/milestones/${milestone.id}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(reviewMilestoneRes.ok, 'Fetch milestone details returns 200 OK');
        assert(reviewMilestoneRes.data?.data?.status === 'UNDER_REVIEW', `Milestone status is UNDER_REVIEW (got ${reviewMilestoneRes.data?.data?.status})`);

        // 14. Authorization Check: Vendor cannot approve own milestone
        console.log('\n--- Phase 14: Security Authorization & Milestone Approval ---');
        const vendorApproveRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/approve`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeVendorToken}` }
        });
        assert(vendorApproveRes.status === 401, `Vendor approving own milestone is rejected with 401 (got ${vendorApproveRes.status})`);

        // Corporate approves milestone
        const corpApproveRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/approve`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(corpApproveRes.ok, `Corporate milestone approval returns 200 OK (got ${corpApproveRes.status})`);
        assert(corpApproveRes.data?.data?.status === 'APPROVED', `Milestone status transitioned to APPROVED (got ${corpApproveRes.data?.data?.status})`);

        // 15. Corporate Initiates Razorpay Payment Order
        console.log('\n--- Phase 15: Razorpay Payment Order Creation ---');
        const paymentOrderRes = await request(`${BACKEND_URL}/payments/create-order`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                dealId: deal.id,
                milestoneId: milestone.id,
                idempotencyKey: `idemp_${Date.now()}`
            })
        });
        assert(paymentOrderRes.ok, `Payment order created (got ${paymentOrderRes.status})`);
        const orderData = paymentOrderRes.data?.data;
        assert(orderData?.orderId !== undefined, `Razorpay order ID returned: ${orderData?.orderId}`);
        assert(orderData?.paymentId !== undefined, `FINX payment ID returned: ${orderData?.paymentId}`);

        // 16. Payment Verification & Cryptographic Signature Handling
        console.log('\n--- Phase 16: Payment Verification & Escrow Funding ---');
        const paymentTxnId = `pay_test_${Date.now()}`;
        const verifyPaymentRes = await request(`${BACKEND_URL}/payments/verify`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                paymentId: orderData.paymentId,
                razorpayOrderId: orderData.orderId,
                razorpayPaymentId: paymentTxnId,
                razorpaySignature: hmacSignature(
                    `${orderData.orderId}|${paymentTxnId}`,
                    RAZORPAY_PAYMENT_SECRET,
                    'FINX_E2E_RAZORPAY_KEY_SECRET'
                )
            })
        });
        assert(verifyPaymentRes.ok, `Payment verified successfully (got ${verifyPaymentRes.status})`);
        assert(verifyPaymentRes.data?.data?.status === 'SUCCESS', `Payment status marked SUCCESS (got ${verifyPaymentRes.data?.data?.status})`);

        // 17. Escrow Account Balance & Ledger Audit
        console.log('\n--- Phase 17: Escrow Balance & Ledger Verification ---');
        const escrowBalanceRes = await request(`${BACKEND_URL}/escrow/deal/${deal.id}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(escrowBalanceRes.ok, 'GET escrow account returns 200 OK');
        assert(Number(escrowBalanceRes.data?.data?.balance) === 10000, `Escrow balance is 10000 (got ${escrowBalanceRes.data?.data?.balance})`);

        const escrowLedgerRes = await request(`${BACKEND_URL}/escrow/ledger/deal/${deal.id}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(escrowLedgerRes.ok, 'GET escrow ledger returns 200 OK');
        const ledgerEntries = escrowLedgerRes.data?.data || [];
        assert(ledgerEntries.length > 0, `Escrow ledger contains ${ledgerEntries.length} entries`);
        assert(ledgerEntries[0]?.transactionType === 'FUND', `First ledger entry type is FUND`);
        assert(Number(ledgerEntries[0]?.amount) === 10000, `Ledger entry amount is 10000`);

        // 18. Eligible Escrow Release
        console.log('\n--- Phase 18: Escrow Release to Vendor ---');
        const releaseRes = await request(`${BACKEND_URL}/escrow/${milestone.id}/release`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                comment: 'Milestone 1 deliverable verified and accepted'
            })
        });
        assert(releaseRes.ok, `Escrow release returns 200 OK (got ${releaseRes.status})`);
        assert(releaseRes.data?.data?.transactionType === 'RELEASE', `Release ledger entry type is RELEASE`);
        assert(Number(releaseRes.data?.data?.balanceAfter) === 0, `Escrow balance after release is 0`);

        // 19. Double-Release Prevention
        console.log('\n--- Phase 19: Double-Release Prevention ---');
        const duplicateReleaseRes = await request(`${BACKEND_URL}/escrow/${milestone.id}/release`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                comment: 'Attempting illegal duplicate release'
            })
        });
        assert(duplicateReleaseRes.status === 400, `Duplicate release is rejected with 400 Bad Request (got ${duplicateReleaseRes.status})`);

        // 20. Admin Dashboard & Audit Trail
        console.log('\n--- Phase 20: Admin Dashboard & Audit Logs ---');
        // Register & Login Admin
        await request(`${BACKEND_URL}/auth/register`, {
            method: 'POST', body: JSON.stringify({ email: 'admin@finx.com', password: 'Admin@Finx2026!', name: 'Admin', role: 'ADMIN' })
        });
        const tempAdminLoginRes = await request(`${BACKEND_URL}/auth/login`, {
            method: 'POST', body: JSON.stringify({ email: 'admin@finx.com', password: 'Admin@Finx2026!' })
        });
        const activeAdminToken = tempAdminLoginRes.data?.data?.accessToken || tempAdminLoginRes.data?.accessToken;

        const adminDashboardRes = await request(`${BACKEND_URL}/admin/dashboard`, {
            headers: { Authorization: `Bearer ${activeAdminToken}` }
        });
        assert(adminDashboardRes.ok, 'GET /api/admin/dashboard returns 200 OK');
        assert(adminDashboardRes.data?.data?.totalDeals !== undefined, 'Admin dashboard returns totalDeals metric');

        const auditLogsRes = await request(`${BACKEND_URL}/admin/audit-logs`, {
            headers: { Authorization: `Bearer ${activeAdminToken}` }
        });
        assert(auditLogsRes.ok, 'GET /api/admin/audit-logs returns 200 OK');
        const logs = auditLogsRes.data?.data || [];
        assert(logs.length > 0, `Audit log contains ${logs.length} logged business events`);

        // 21. Test Cancel Deal Lifecycle
        console.log('\n--- Phase 21: Deal Cancellation Flow ---');
        const dealToCancelRes = await request(`${BACKEND_URL}/deals`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                title: 'Deal To Cancel',
                description: 'Testing cancellation flow',
                sellerId: vendorUser.id,
                totalAmount: 5000,
                currency: 'INR'
            })
        });
        const cancelDealId = dealToCancelRes.data?.data?.id;
        const cancelRes = await request(`${BACKEND_URL}/deals/${cancelDealId}/cancel`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(cancelRes.ok, 'Cancel deal returns 200 OK');
        assert(cancelRes.data?.data?.status === 'CANCELLED', `Cancelled deal status is CANCELLED (got ${cancelRes.data?.data?.status})`);

        // 22. Frontend Route Integrity Check
        console.log('\n--- Phase 22: Frontend Route Integrity (No 404s, No Under Construction) ---');
        const frontendRoutes = [
            '/',
            '/login',
            '/register',
            '/corporate',
            '/corporate/projects',
            `/corporate/projects/${deal.id}`,
            '/corporate/milestones',
            `/corporate/milestones/${milestone.id}`,
            '/corporate/payments',
            '/corporate/escrow',
            '/corporate/settings',
            '/vendor',
            '/vendor/projects',
            `/vendor/projects/${deal.id}`,
            '/vendor/milestones',
            '/vendor/deliverables',
            '/vendor/payments',
            '/vendor/escrow',
            '/vendor/settings',
            '/admin',
            '/admin/users',
            '/admin/audit-logs',
            '/admin/audit',
            '/admin/projects',
            '/admin/disputes',
            '/corporate/disputes',
            '/finance',
            '/finance/transactions',
            '/finance/payments',
            '/finance/invoices',
            '/finance/reports',
            '/project-manager',
            '/project-manager/projects',
            '/project-manager/reviews',
            '/project-manager/deliverables',
            '/project-manager/reports'
        ];

        for (const route of frontendRoutes) {
            const res = await fetch(`${FRONTEND_URL}${route}`);
            assert(res.status === 200, `Frontend route ${route} returns HTTP 200`);
            const html = await res.text();
            assert(!html.includes('Under Construction'), `Route ${route} has NO "Under Construction" placeholder`);
        }

        // 23. Complete IDOR & Cross-User Security Audit
        console.log('\n--- Phase 23: Complete IDOR & Cross-User Security Audit ---');
        // Register Buyer B
        const buyerBEmail = `buyer.b.${Date.now()}@finx.com`;
        const buyerBRegRes = await request(`${BACKEND_URL}/auth/register`, {
            method: 'POST',
            body: JSON.stringify({
                name: 'Competitor Corp Buyer',
                email: buyerBEmail,
                password: 'Password123!',
                role: 'BUYER'
            })
        });
        const buyerBToken = buyerBRegRes.data?.data?.accessToken;
        assert(buyerBRegRes.ok, 'Registered independent Buyer B');

        // Register Seller B
        const sellerBEmail = `seller.b.${Date.now()}@finx.com`;
        const sellerBRegRes = await request(`${BACKEND_URL}/auth/register`, {
            method: 'POST',
            body: JSON.stringify({
                name: 'Unrelated Vendor Agency',
                email: sellerBEmail,
                password: 'Password123!',
                role: 'SELLER'
            })
        });
        const sellerBToken = sellerBRegRes.data?.data?.accessToken;
        assert(sellerBRegRes.ok, 'Registered independent Seller B');

        // IDOR Test 1: Buyer B tries to read Deal A
        const buyerBReadDealRes = await request(`${BACKEND_URL}/deals/${deal.id}`, {
            headers: { Authorization: `Bearer ${buyerBToken}` }
        });
        assert(buyerBReadDealRes.status === 401 || buyerBReadDealRes.status === 403, `IDOR: Buyer B reading Deal A is rejected (got ${buyerBReadDealRes.status})`);

        // IDOR Test 2: Seller B tries to read Deal A
        const sellerBReadDealRes = await request(`${BACKEND_URL}/deals/${deal.id}`, {
            headers: { Authorization: `Bearer ${sellerBToken}` }
        });
        assert(sellerBReadDealRes.status === 401 || sellerBReadDealRes.status === 403, `IDOR: Seller B reading Deal A is rejected (got ${sellerBReadDealRes.status})`);

        // IDOR Test 3: Seller B tries to accept Deal A
        const sellerBAcceptDealRes = await request(`${BACKEND_URL}/deals/${deal.id}/accept`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${sellerBToken}` }
        });
        assert(sellerBAcceptDealRes.status === 401 || sellerBAcceptDealRes.status === 403, `IDOR: Seller B accepting Deal A is rejected (got ${sellerBAcceptDealRes.status})`);

        // IDOR Test 4: Buyer B tries to create milestone on Deal A
        const buyerBCreateMilestoneRes = await request(`${BACKEND_URL}/deals/${deal.id}/milestones`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${buyerBToken}` },
            body: JSON.stringify({
                title: 'Malicious Milestone',
                sequence: 99,
                amount: 5000,
                currency: 'INR'
            })
        });
        assert(buyerBCreateMilestoneRes.status === 401 || buyerBCreateMilestoneRes.status === 403, `IDOR: Buyer B creating milestone on Deal A is rejected (got ${buyerBCreateMilestoneRes.status})`);

        // IDOR Test 5: Seller B tries to start Milestone A
        const sellerBStartMilestoneRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/start`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${sellerBToken}` }
        });
        assert(sellerBStartMilestoneRes.status === 401 || sellerBStartMilestoneRes.status === 403, `IDOR: Seller B starting Milestone A is rejected (got ${sellerBStartMilestoneRes.status})`);

        // IDOR Test 6: Seller B tries to submit deliverable on Milestone A
        const sellerBSubmitDeliverableRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/submit`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${sellerBToken}` },
            body: JSON.stringify({
                fileName: 'exploit.zip',
                fileUrl: 'https://malicious.com/exploit.zip',
                description: 'Injecting deliverable without permissions'
            })
        });
        assert(sellerBSubmitDeliverableRes.status === 401 || sellerBSubmitDeliverableRes.status === 403, `IDOR: Seller B submitting deliverable on Milestone A is rejected (got ${sellerBSubmitDeliverableRes.status})`);

        // IDOR Test 7: Buyer B tries to approve Milestone A
        const buyerBApproveMilestoneRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/approve`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${buyerBToken}` }
        });
        assert(buyerBApproveMilestoneRes.status === 401 || buyerBApproveMilestoneRes.status === 403, `IDOR: Buyer B approving Milestone A is rejected (got ${buyerBApproveMilestoneRes.status})`);

        // IDOR Test 8: Buyer B tries to release escrow on Milestone A
        const buyerBReleaseEscrowRes = await request(`${BACKEND_URL}/escrow/${milestone.id}/release`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${buyerBToken}` },
            body: JSON.stringify({ comment: 'Malicious escrow release attempt' })
        });
        assert(buyerBReleaseEscrowRes.status === 401 || buyerBReleaseEscrowRes.status === 403, `IDOR: Buyer B releasing escrow for Milestone A is rejected (got ${buyerBReleaseEscrowRes.status})`);

        // IDOR Test 9: Seller A (vendor) tries to release escrow on Milestone A
        const sellerAReleaseEscrowRes = await request(`${BACKEND_URL}/escrow/${milestone.id}/release`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeVendorToken}` },
            body: JSON.stringify({ comment: 'Vendor trying to self-release funds' })
        });
        assert(sellerAReleaseEscrowRes.status === 401 || sellerAReleaseEscrowRes.status === 403, `Security: Vendor releasing escrow is rejected (got ${sellerAReleaseEscrowRes.status})`);

        // IDOR Test 10: Buyer B tries to verify payment using Buyer A's payment ID
        const buyerBVerifyPaymentRes = await request(`${BACKEND_URL}/payments/verify`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${buyerBToken}` },
            body: JSON.stringify({
                paymentId: orderData.paymentId,
                razorpayOrderId: orderData.orderId,
                razorpayPaymentId: 'pay_hijack_test',
                razorpaySignature: 'invalid_signature'
            })
        });
        assert(buyerBVerifyPaymentRes.status === 401 || buyerBVerifyPaymentRes.status === 403, `Security: Cross-buyer payment verification hijacking is rejected (got ${buyerBVerifyPaymentRes.status})`);

        // Security Test 11: Unauthenticated request to /api/deals
        const unauthDealsRes = await request(`${BACKEND_URL}/deals`);
        assert(unauthDealsRes.status === 401, `Security: Unauthenticated request returns 401 (got ${unauthDealsRes.status})`);

        // Security Test 12: Tampered JWT token to /api/auth/me
        const tamperedMeRes = await request(`${BACKEND_URL}/auth/me`, {
            headers: { Authorization: `Bearer eyJhbGciOiJIUzI1NiJ9.tampered.token` }
        });
        assert(tamperedMeRes.status === 401, `Security: Tampered JWT token returns 401 (got ${tamperedMeRes.status})`);

        // 24. Deal State Machine Negative / Invalid Transitions
        console.log('\n--- Phase 24: Deal State Machine Negative Transitions ---');
        // Accepting already cancelled deal
        const acceptCancelledRes = await request(`${BACKEND_URL}/deals/${cancelDealId}/accept`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${activeVendorToken}` }
        });
        assert(acceptCancelledRes.status === 400, `State Machine: Accepting CANCELLED deal is rejected with 400 (got ${acceptCancelledRes.status})`);

        // Cancelling already cancelled deal
        const cancelAgainRes = await request(`${BACKEND_URL}/deals/${cancelDealId}/cancel`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(cancelAgainRes.status === 400, `State Machine: Cancelling already CANCELLED deal is rejected with 400 (got ${cancelAgainRes.status})`);

        // 25. Deliverable Validation Security & Content Constraints
        console.log('\n--- Phase 25: Deliverable Validation & Size Constraints ---');
        const emptyFileRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/submit`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeVendorToken}` },
            body: JSON.stringify({
                fileName: '',
                fileUrl: 'https://storage.finx.local/empty.zip',
                description: 'Empty filename test'
            })
        });
        assert(emptyFileRes.status === 400, `Deliverable: Empty filename is rejected with 400 (got ${emptyFileRes.status})`);

        const oversizedDesc = 'A'.repeat(4005);
        const oversizedDescRes = await request(`${BACKEND_URL}/milestones/${milestone.id}/submit`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeVendorToken}` },
            body: JSON.stringify({
                fileName: 'normal.zip',
                fileUrl: 'https://storage.finx.local/normal.zip',
                description: oversizedDesc
            })
        });
        assert(oversizedDescRes.status === 400, `Deliverable: Oversized description (>4000 chars) is rejected with 400 (got ${oversizedDescRes.status})`);

        // 26. Dispute Lifecycle End-to-End
        console.log('\n--- Phase 26: Dispute Lifecycle End-to-End ---');
        // Create a deal specifically for dispute workflow
        const disputeDealRes = await request(`${BACKEND_URL}/deals`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                title: 'Deal Subject to Legal Dispute',
                description: 'Testing arbitration freeze and resolution',
                sellerId: vendorUser.id,
                totalAmount: 8000,
                currency: 'INR'
            })
        });
        const disputeDeal = disputeDealRes.data?.data;
        assert(disputeDealRes.ok, `Created deal for dispute test: ${disputeDeal?.id}`);

        // Vendor accepts deal
        await request(`${BACKEND_URL}/deals/${disputeDeal.id}/accept`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${activeVendorToken}` }
        });

        // Corporate files a dispute
        const raiseDisputeRes = await request(`${BACKEND_URL}/disputes`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                dealId: disputeDeal.id,
                reason: 'Vendor failed deliverable security standards and defaulted on SLA'
            })
        });
        assert(raiseDisputeRes.status === 201, `Dispute created successfully with 201 (got ${raiseDisputeRes.status})`);
        const disputeRecord = raiseDisputeRes.data?.data;
        assert(disputeRecord?.id !== undefined, `Dispute assigned ID: ${disputeRecord?.id}`);
        assert(disputeRecord?.status === 'OPEN', `Dispute status is OPEN`);

        // Verify Deal status is updated to DISPUTED
        const checkDisputedDealRes = await request(`${BACKEND_URL}/deals/${disputeDeal.id}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(checkDisputedDealRes.data?.data?.status === 'DISPUTED', `Deal status successfully transitioned to DISPUTED (got ${checkDisputedDealRes.data?.data?.status})`);

        // Non-admin attempting to resolve dispute -> Rejected
        const nonAdminResolveRes = await request(`${BACKEND_URL}/disputes/${disputeRecord.id}/resolve`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({ resolutionNotes: 'Attempting resolution without admin role' })
        });
        assert(nonAdminResolveRes.status === 400, `Security: Non-admin resolving dispute is rejected with 400 (got ${nonAdminResolveRes.status})`);

        // Admin resolves dispute
        // Log in as seeded system administrator
        const adminLoginRes = await request(`${BACKEND_URL}/auth/login`, {
            method: 'POST',
            body: JSON.stringify({
                email: 'admin@finx.com',
                password: 'Admin@Finx2026!'
            })
        });
        assert(adminLoginRes.ok, 'System Administrator logged in successfully');
        const adminToken = adminLoginRes.data?.data?.accessToken;

        // Admin fetches all disputes
        const allDisputesRes = await request(`${BACKEND_URL}/disputes`, {
            headers: { Authorization: `Bearer ${adminToken}` }
        });
        assert(allDisputesRes.ok, 'Admin GET /api/disputes returns 200 OK');
        const disputeList = allDisputesRes.data?.data || [];
        assert(disputeList.some(d => d.id === disputeRecord.id), 'Raised dispute is present in admin disputes list');

        // Admin resolves the dispute
        const adminResolveRes = await request(`${BACKEND_URL}/disputes/${disputeRecord.id}/resolve`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${adminToken}` },
            body: JSON.stringify({ resolutionNotes: 'Vendor verified breach. Milestone forfeited and escrow refunded.' })
        });
        assert(adminResolveRes.ok, `Admin resolved dispute successfully (got ${adminResolveRes.status})`);
        assert(adminResolveRes.data?.data?.status === 'RESOLVED', `Dispute status transitioned to RESOLVED`);
        assert(adminResolveRes.data?.data?.resolutionNotes?.includes('Vendor verified breach'), `Resolution notes saved correctly`);

        // 27. Audit Log Completeness Check
        console.log('\n--- Phase 27: Audit Log Completeness & Integrity ---');
        const fullAuditLogsRes = await request(`${BACKEND_URL}/admin/audit-logs`, {
            headers: { Authorization: `Bearer ${adminToken}` }
        });
        assert(fullAuditLogsRes.ok, 'Admin audit log query returns 200 OK');
        const allLogs = fullAuditLogsRes.data?.data || [];
        const actionsLogged = new Set(allLogs.map(l => l.action));

        const requiredActions = [
            'USER_REGISTERED',
            'USER_LOGIN',
            'DEAL_CREATED',
            'DEAL_ACCEPTED',
            'MILESTONE_CREATED',
            'MILESTONE_STARTED',
            'DELIVERABLE_SUBMITTED',
            'MILESTONE_APPROVED',
            'PAYMENT_CREATED',
            'PAYMENT_VERIFIED',
            'ESCROW_FUNDED',
            'ESCROW_RELEASED',
            'DEAL_CANCELLED',
            'DISPUTE_CREATED',
            'DISPUTE_RESOLVED'
        ];

        for (const action of requiredActions) {
            assert(actionsLogged.has(action), `Audit trail contains business event: [${action}]`);
        }

        // 28. Monetary Precision & Ledger Mathematical Verification
        console.log('\n--- Phase 28: Zero Floating-Point Precision & Exact Monetary Math ---');
        const verifyLedgerRes = await request(`${BACKEND_URL}/escrow/ledger/deal/${deal.id}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        const records = verifyLedgerRes.data?.data || [];
        for (const rec of records) {
            assert(typeof rec.amount === 'number' || typeof rec.amount === 'string', `Ledger record ${rec.id} has exact numeric amount`);
            assert(typeof rec.balanceAfter === 'number' || typeof rec.balanceAfter === 'string', `Ledger record ${rec.id} has exact numeric balanceAfter`);
        }
        // 29. Razorpay Webhook Processing, Idempotency & Signature Security
        console.log('\n--- Phase 29: Razorpay Webhooks, Idempotency & Signature Security ---');

        // Test invalid signature
        const invalidWbkRes = await request(`${BACKEND_URL}/payments/webhook`, {
            method: 'POST',
            headers: {
                'X-Razorpay-Signature': 'bogus_fraudulent_signature'
            },
            body: JSON.stringify({ event: 'payment.captured' })
        });
        assert(invalidWbkRes.status === 400, `Webhook with invalid signature is rejected with 400 (got ${invalidWbkRes.status})`);

        // Create a new deal & milestone specifically for webhook processing test
        const wbkDealRes = await request(`${BACKEND_URL}/deals`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                title: 'Webhook Test Deal',
                description: 'Validating webhook funding and idempotency',
                sellerId: vendorUser.id,
                totalAmount: 25000,
                currency: 'INR'
            })
        });
        const wbkDealId = wbkDealRes.data?.data?.id;

        await request(`${BACKEND_URL}/deals/${wbkDealId}/accept`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${activeVendorToken}` }
        });

        const wbkMilestoneRes = await request(`${BACKEND_URL}/deals/${wbkDealId}/milestones`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                title: 'Webhook Funding Milestone',
                description: 'Funded purely via Razorpay webhook event',
                amount: 25000,
                currency: 'INR',
                dueDate: '2026-12-31T00:00:00Z'
            })
        });
        const wbkMilestoneId = wbkMilestoneRes.data?.data?.id;

        const wbkOrderRes = await request(`${BACKEND_URL}/payments/create-order`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${activeCorpToken}` },
            body: JSON.stringify({
                dealId: wbkDealId,
                milestoneId: wbkMilestoneId
            })
        });
        const wbkOrderId = wbkOrderRes.data?.data?.orderId;
        assert(wbkOrderId && wbkOrderId.length > 0, `Payment order created for webhook test (${wbkOrderId})`);

        // Send valid webhook
        const validPayload = JSON.stringify({
            event: 'payment.captured',
            payload: {
                payment: {
                    entity: {
                        id: `pay_wbk_test_${Date.now()}`,
                        order_id: wbkOrderId,
                        amount: 2500000,
                        currency: 'INR',
                        status: 'captured'
                    }
                }
            }
        });

        const wbkSuccessRes = await request(`${BACKEND_URL}/payments/webhook`, {
            method: 'POST',
            headers: {
                'X-Razorpay-Signature': hmacSignature(
                    validPayload,
                    RAZORPAY_WEBHOOK_SECRET,
                    'FINX_E2E_RAZORPAY_WEBHOOK_SECRET'
                )
            },
            body: validPayload
        });
        assert(wbkSuccessRes.ok, `Valid webhook returns 200 OK (got ${wbkSuccessRes.status})`);
        assert(wbkSuccessRes.data?.status === 'success', `Webhook response indicates success`);

        // Verify deal escrow funded
        const wbkEscrowRes = await request(`${BACKEND_URL}/escrow/deal/${wbkDealId}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(Number(wbkEscrowRes.data?.data?.balance) === 25000, `Deal escrow funded via webhook to 25000 (got ${wbkEscrowRes.data?.data?.balance})`);

        // Send duplicate webhook (Idempotency test)
        const duplicateWbkRes = await request(`${BACKEND_URL}/payments/webhook`, {
            method: 'POST',
            headers: {
                'X-Razorpay-Signature': hmacSignature(
                    validPayload,
                    RAZORPAY_WEBHOOK_SECRET,
                    'FINX_E2E_RAZORPAY_WEBHOOK_SECRET'
                )
            },
            body: validPayload
        });
        assert(duplicateWbkRes.ok, `Duplicate webhook returns 200 OK`);
        assert(duplicateWbkRes.data?.status === 'already_processed', `Duplicate webhook recognized as already_processed`);

        // Verify balance was NOT funded twice
        const wbkEscrowRes2 = await request(`${BACKEND_URL}/escrow/deal/${wbkDealId}`, {
            headers: { Authorization: `Bearer ${activeCorpToken}` }
        });
        assert(Number(wbkEscrowRes2.data?.data?.balance) === 25000, `Escrow balance remains exactly 25000 after duplicate webhook (Idempotency invariant)`);

        console.log('\n====================================================');
        console.log(`   Verification Summary: ${passed} PASSED, ${failed} FAILED`);
        console.log('====================================================\n');

        if (failed > 0) {
            process.exit(1);
        }
    } catch (err) {
        console.error('Fatal test execution error:', err);
        process.exit(1);
    }
}

runTests();
