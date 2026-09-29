import { randomUUID } from 'crypto';

const BASE_URL = 'http://localhost:8080';

async function request(path, options = {}) {
  const url = `${BASE_URL}${path}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options.headers,
    }
  });
  let data = null;
  const text = await res.text();
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }
  return { status: res.status, data };
}

async function runNegativeTests() {
  console.log('====================================================');
  console.log('FINX BANKING / SECURITY / RBAC NEGATIVE TEST SUITE');
  console.log('====================================================\n');

  const ts = Date.now();
  const results = [];

  function record(id, title, expectedStatus, actualStatus, passed, details) {
    results.push({ id, title, expectedStatus, actualStatus, passed, details });
    const mark = passed ? '✅ PASS' : '❌ FAIL';
    console.log(`[${id}] ${title}: ${mark} (Expected: ${expectedStatus}, Got: ${actualStatus})`);
    if (details) console.log(`    Details: ${typeof details === 'object' ? JSON.stringify(details) : details}`);
  }

  // SCENARIO 1: Unauthenticated request attempts banking operation
  const unauthRes = await request('/api/v1/payments/instructions', {
    method: 'POST',
    body: JSON.stringify({ dealId: '00000000-0000-0000-0000-000000000000', idempotencyKey: 'IDEMP-' + ts })
  });
  record(
    'NEG-1',
    'Unauthenticated request attempts banking operation (POST /api/v1/payments/instructions)',
    401,
    unauthRes.status,
    unauthRes.status === 401,
    unauthRes.data?.message || unauthRes.data
  );

  // Setup user accounts: Legit Buyer, Legit Vendor, Rogue Hacker
  const buyerEmail = `neg.buyer.${ts}@finx.test`;
  const vendorEmail = `neg.vendor.${ts}@finx.test`;
  const hackerEmail = `neg.hacker.${ts}@finx.test`;

  const buyerReg = await request('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ name: 'Legit Buyer Corp', email: buyerEmail, password: 'Password123!', role: 'BUYER' })
  });
  const buyerToken = buyerReg.data?.data?.accessToken;
  const buyerId = buyerReg.data?.data?.user?.id;

  const vendorReg = await request('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ name: 'Legit Vendor Tech', email: vendorEmail, password: 'Password123!', role: 'SELLER' })
  });
  const vendorToken = vendorReg.data?.data?.accessToken;
  const vendorId = vendorReg.data?.data?.user?.id;

  const hackerReg = await request('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ name: 'Rogue Actor Corp', email: hackerEmail, password: 'Password123!', role: 'BUYER' })
  });
  const hackerToken = hackerReg.data?.data?.accessToken;

  // SCENARIO 2: Buyer without required authorization attempts protected Admin banking action (Admin Ledger)
  const buyerAdminOpRes = await request('/api/v1/admin/ledger/entries', {
    method: 'GET',
    headers: { Authorization: `Bearer ${buyerToken}` }
  });
  record(
    'NEG-2',
    'Buyer without required authorization attempts protected Admin banking action (GET /api/v1/admin/ledger/entries)',
    403,
    buyerAdminOpRes.status,
    buyerAdminOpRes.status === 403,
    buyerAdminOpRes.data?.message || buyerAdminOpRes.data
  );

  // Create Organizations
  const vendorOrgRes = await request('/api/v1/organizations', {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({
      name: 'Legit Vendor Org',
      legalName: 'Legit Vendor Org LLP',
      entityType: 'LLP',
      registrationNumber: 'LLP-9999',
      pan: 'AABBL9999K',
      gstin: '29AABBL9999K1Z5',
      registeredAddress: 'Electronic City, Bangalore'
    })
  });
  const vendorOrgId = vendorOrgRes.data?.data?.id;

  const buyerOrgRes = await request('/api/v1/organizations', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      name: 'Legit Buyer Org',
      legalName: 'Legit Buyer Org Pvt Ltd',
      entityType: 'PRIVATE_LIMITED',
      registrationNumber: 'U11111MH2020PTC999999',
      pan: 'AAACL9999K',
      gstin: '27AAACL9999K1Z5',
      registeredAddress: 'BKC, Mumbai'
    })
  });
  const buyerOrgId = buyerOrgRes.data?.data?.id;

  // SCENARIO 3: Missing required DPDP consent before KYB
  const unconsentedKybRes = await request(`/api/v1/kyb/${vendorOrgId}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({
      businessPan: 'AABBL9999K',
      gstin: '29AABBL9999K1Z5',
      cinOrLlpin: 'LLP-9999',
      udyamNumber: 'UDYAM-KR-03-0099999'
    })
  });
  record(
    'NEG-3',
    'Missing required DPDP consent before KYB (POST /api/v1/kyb/{orgId})',
    400,
    unconsentedKybRes.status,
    unconsentedKybRes.status === 400 && JSON.stringify(unconsentedKybRes.data).includes('consent required'),
    unconsentedKybRes.data?.message || unconsentedKybRes.data
  );

  // Now record consent properly for vendorOrg
  await request('/api/v1/kyc/consent', {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({ verificationType: 'PAN', organizationId: vendorOrgId, purpose: 'PAN Verification', consentVersion: '1.0' })
  });
  await request('/api/v1/kyc/consent', {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({ verificationType: 'GST', organizationId: vendorOrgId, purpose: 'GST Verification', consentVersion: '1.0' })
  });
  await request('/api/v1/kyc/consent', {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({ verificationType: 'BANK', organizationId: vendorOrgId, purpose: 'Bank Verification', consentVersion: '1.0' })
  });

  // Complete KYB
  await request(`/api/v1/kyb/${vendorOrgId}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({
      businessPan: 'AABBL9999K',
      gstin: '29AABBL9999K1Z5',
      cinOrLlpin: 'LLP-9999',
      udyamNumber: 'UDYAM-KR-03-0099999'
    })
  });

  // SCENARIO 4: Cross-company access attempt: Rogue buyer attempts to modify another org bank account
  const crossCompanyBankRes = await request(`/api/v1/organizations/${vendorOrgId}/bank-accounts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${hackerToken}` },
    body: JSON.stringify({
      accountHolderName: 'Rogue Actor',
      accountNumber: '111122223333',
      ifscCode: 'HDFC0001234',
      bankName: 'HDFC Bank',
      branchName: 'Main',
      isPrimary: true
    })
  });
  record(
    'NEG-4',
    'Cross-company access attempt: Rogue buyer attempts to add bank account to vendor org',
    401,
    crossCompanyBankRes.status,
    crossCompanyBankRes.status === 401 && JSON.stringify(crossCompanyBankRes.data).includes('not a member'),
    crossCompanyBankRes.data?.message || crossCompanyBankRes.data
  );

  // Add UNVERIFIED bank account to vendor
  const addBankRes = await request(`/api/v1/organizations/${vendorOrgId}/bank-accounts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({
      accountHolderName: 'Legit Vendor Org LLP',
      accountNumber: '99' + String(ts).slice(-10),
      ifscCode: 'HDFC0001234',
      bankName: 'HDFC Bank',
      branchName: 'Main',
      isPrimary: true
    })
  });
  const bankAccountId = addBankRes.data?.data?.id;

  // Buyer creates Deal (₹5,00,000)
  const dealRes = await request('/api/v1/deals', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      title: 'Security Deal',
      description: 'Test deal for negative flows',
      buyerId: buyerId,
      sellerId: vendorId,
      buyerOrganizationId: buyerOrgId,
      sellerOrganizationId: vendorOrgId,
      totalAmount: 500000.00,
      currency: 'INR'
    })
  });
  const dealId = dealRes.data?.data?.id;

  // Vendor accepts deal
  await request(`/api/v1/deals/${dealId}/accept`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` }
  });

  // SCENARIO 5: Vendor attempts Buyer-only banking operation on Deal
  const vendorBuyerOpRes = await request('/api/v1/payments/instructions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({ dealId: dealId, idempotencyKey: 'IDEMP-VENDOR-OP-' + ts })
  });
  record(
    'NEG-5',
    'Vendor attempts Buyer-only banking operation (POST /api/v1/payments/instructions on deal)',
    401,
    vendorBuyerOpRes.status,
    vendorBuyerOpRes.status === 401 && JSON.stringify(vendorBuyerOpRes.data).includes('corporate buyer'),
    vendorBuyerOpRes.data?.message || vendorBuyerOpRes.data
  );

  // Create Milestone 1 (₹3,00,000) - Notice deal is ₹5,00,000
  const msRes = await request(`/api/v1/deals/${dealId}/milestones`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      title: 'Milestone 1',
      description: 'Initial Delivery',
      amount: 300000.00,
      currency: 'INR',
      sequence: 1
    })
  });
  const milestoneId = msRes.data?.data?.id;

  // Create Agreement Draft
  await request(`/api/v1/agreements/${dealId}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({ title: 'MSA Agreement', termsContent: 'Full commercial terms and milestone payout conditions.' })
  });

  // SCENARIO 6: Cross-company access attempt: Rogue user attempts to sign Agreement
  const rogueSignRes = await request(`/api/v1/agreements/${dealId}/sign`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${hackerToken}` },
    body: JSON.stringify({ version: 1 })
  });
  record(
    'NEG-6',
    'Cross-company access attempt: Rogue user attempts to sign deal agreement',
    401,
    rogueSignRes.status,
    rogueSignRes.status === 401,
    rogueSignRes.data?.message || rogueSignRes.data
  );

  // Legitimate signatures
  await request(`/api/v1/agreements/${dealId}/sign`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({ version: 1 })
  });
  await request(`/api/v1/agreements/${dealId}/sign`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({ version: 1 })
  });

  // SCENARIO 7: Manipulated payment amount / Invariant violation (deal total is 500k, but only 1 milestone of 300k exists)
  const invariantFundingRes = await request('/api/v1/payments/instructions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({ dealId: dealId, idempotencyKey: 'IDEMP-INVARIANT-' + ts })
  });
  record(
    'NEG-7',
    'Manipulated payment amount: Whole-deal funding blocked when sum(milestones) != deal totalAmount',
    400,
    invariantFundingRes.status,
    invariantFundingRes.status === 400 && JSON.stringify(invariantFundingRes.data).includes('Invariant violation'),
    invariantFundingRes.data?.message || invariantFundingRes.data
  );

  // Fix invariant: add Milestone 2 (₹2,00,000) so sum = ₹5,00,000
  const ms2Res = await request(`/api/v1/deals/${dealId}/milestones`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      title: 'Milestone 2',
      description: 'Final Delivery',
      amount: 200000.00,
      currency: 'INR',
      sequence: 2
    })
  });
  const milestone2Id = ms2Res.data?.data?.id;

  // Now create valid payment instruction for Milestone 1 (₹3,00,000)
  const payInstRes = await request('/api/v1/payments/instructions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({ dealId: dealId, milestoneId: milestoneId, idempotencyKey: 'IDEMP-VALID-M1-' + ts })
  });
  const instructionId = payInstRes.data?.data?.id;

  // Confirm payment instruction
  await request(`/api/v1/payments/instructions/${instructionId}/confirm`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({ utrNumber: 'UTR-CONFIRM-' + ts, providerReference: 'MOCK-PROV-' + ts })
  });

  // SCENARIO 8: Manipulated entity / non-existent ID
  const invalidIdPayoutRes = await request('/api/v1/settlements/authorize', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      milestoneId: '12345678-1234-1234-1234-123456789abc',
      authorizationNotes: 'Non existent milestone',
      idempotencyKey: 'IDEMP-NONEXISTENT-' + ts
    })
  });
  record(
    'NEG-8',
    'Manipulated milestone ID: Settlement authorization on non-existent milestone',
    404,
    invalidIdPayoutRes.status,
    invalidIdPayoutRes.status === 404,
    invalidIdPayoutRes.data?.message || invalidIdPayoutRes.data
  );

  // SCENARIO 9: Invalid payment state transition: Payout authorization attempted on UNAPPROVED milestone
  const prematurePayoutRes = await request('/api/v1/settlements/authorize', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      milestoneId: milestoneId,
      authorizationNotes: 'Premature attempt on milestone still PENDING',
      idempotencyKey: 'IDEMP-PREMATURE-' + ts
    })
  });
  record(
    'NEG-9',
    'Invalid bank account / milestone gate check: Settlement authorization blocked when bank account is UNVERIFIED',
    400,
    prematurePayoutRes.status,
    prematurePayoutRes.status === 400 && JSON.stringify(prematurePayoutRes.data).includes('Vendor verified bank account not found'),
    prematurePayoutRes.data?.message || prematurePayoutRes.data
  );

  await request(`/api/v1/organizations/${vendorOrgId}/bank-accounts/${bankAccountId}/verify`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` }
  });

  // Now attempt payout on unapproved milestone (with bank verified)
  const unapprovedPayoutRes = await request('/api/v1/settlements/authorize', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      milestoneId: milestoneId,
      authorizationNotes: 'Attempt on unapproved milestone',
      idempotencyKey: 'IDEMP-UNAPPROVED-' + ts
    })
  });
  record(
    'NEG-10',
    'Invalid payment state transition: Settlement authorization blocked when milestone is not APPROVED',
    400,
    unapprovedPayoutRes.status,
    unapprovedPayoutRes.status === 400 && JSON.stringify(unapprovedPayoutRes.data).includes('APPROVED'),
    unapprovedPayoutRes.data?.message || unapprovedPayoutRes.data
  );

  // Move milestone to APPROVED
  await request(`/api/v1/milestones/${milestoneId}/start`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` }
  });
  await request(`/api/v1/milestones/${milestoneId}/submit`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${vendorToken}` },
    body: JSON.stringify({ fileName: 'code.zip', fileUrl: 'https://s3.finx.dev/code.zip', description: 'Done' })
  });
  await request(`/api/v1/milestones/${milestoneId}/approve`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` }
  });

  // Legitimate settlement payout
  const legitPayoutRes = await request('/api/v1/settlements/authorize', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      milestoneId: milestoneId,
      authorizationNotes: 'Legit settlement approved by Finance',
      idempotencyKey: 'IDEMP-LEGIT-SETTLE-' + ts
    })
  });

  // SCENARIO 11: Duplicate payout instruction where prohibited
  const duplicatePayoutRes = await request('/api/v1/settlements/authorize', {
    method: 'POST',
    headers: { Authorization: `Bearer ${buyerToken}` },
    body: JSON.stringify({
      milestoneId: milestoneId,
      authorizationNotes: 'Duplicate payout attempt on already settled milestone',
      idempotencyKey: 'IDEMP-DIFF-KEY-' + ts
    })
  });
  record(
    'NEG-11',
    'Duplicate payment settlement instruction blocked for already settled milestone',
    400,
    duplicatePayoutRes.status,
    duplicatePayoutRes.status === 400 && (JSON.stringify(duplicatePayoutRes.data).includes('already exists in status SETTLED') || JSON.stringify(duplicatePayoutRes.data).includes('already been settled') || JSON.stringify(duplicatePayoutRes.data).includes('Insufficient escrow pool balance')),
    duplicatePayoutRes.data?.message || duplicatePayoutRes.data
  );

  console.log('\n====================================================');
  console.log('NEGATIVE TEST SUMMARY RESULTS:');
  const allPassed = results.every(r => r.passed);
  console.log(`TOTAL SCENARIOS: ${results.length}`);
  console.log(`PASSED: ${results.filter(r => r.passed).length}`);
  console.log(`FAILED: ${results.filter(r => !r.passed).length}`);
  console.log(`OVERALL RESULT: ${allPassed ? 'ALL PASSED ✅' : 'FAILURES DETECTED ❌'}`);
  console.log('====================================================');

  return { results, allPassed };
}

runNegativeTests().catch(console.error);
