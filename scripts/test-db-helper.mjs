import { loginAdmin, registerUser, recalculateRisk, executeSql, querySql } from './deep-risk-validation.mjs';

async function test() {
  const adminToken = await loginAdmin();
  const ts = Date.now();
  const buyerId = (await registerUser('Alert Buyer Diag', 'alert.b.diag.' + ts + '@finx.com', 'BUYER')).user.id;
  const sellerId = (await registerUser('Alert Seller Diag', 'alert.s.diag.' + ts + '@finx.com', 'SELLER')).user.id;
  const dealId = '44444444-2222-3333-4444-555555555599';

  // Verification category = 20 pts (KYC_FAILED + KYB_FAILED)
  executeSql(`
    MERGE INTO kyc_profiles (id, user_id, full_name, status, created_at, updated_at)
    KEY (user_id) VALUES (random_uuid(), '${buyerId}', 'Alert Buyer', 'FAILED', now(), now());
  `);

  // Deal
  executeSql(`
    MERGE INTO deals (id, buyer_id, seller_id, title, total_amount, currency, status, created_at, updated_at)
    KEY (id) VALUES ('${dealId}', '${buyerId}', '${sellerId}', 'Alert Deal', 100000, 'INR', 'ACTIVE', now(), now());
  `);

  // Payment category = 25 pts (Reversals + failures)
  for (let i = 0; i < 5; i++) {
    executeSql(`INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at) VALUES (random_uuid(), 'REV_${ts}_${i}', '${dealId}', 10000, 'INR', 'RAZORPAY', 'REVERSED', now(), now());`);
  }

  // Dispute category = 15 pts (Disputes)
  for (let i = 0; i < 5; i++) {
    executeSql(`INSERT INTO disputes (id, deal_id, raised_by, reason, status, created_at) VALUES (random_uuid(), '${dealId}', '${buyerId}', 'Fraud', 'LOST', now());`);
  }

  // Current score = Verification (20) + Payment (25) + Dispute (15) = 60!
  let r = await recalculateRisk(adminToken, buyerId);
  console.log('Score before crossing 60:', r.totalRiskScore, r.riskLevel, 'reviewRequired:', r.reviewRequired);

  // Add 1 security failure to push score to 66 (HIGH)
  const subjectId = (await (await fetch('http://localhost:8080/api/admin/risk/' + buyerId, { headers: { Authorization: 'Bearer ' + adminToken } })).json()).data.subject.id;
  for (let i = 0; i < 6; i++) {
    executeSql(`
      INSERT INTO risk_events (id, subject_id, source_key, event_type, occurred_at, attempts, dead_letter)
      VALUES (random_uuid(), '${subjectId}', 'SEC_${ts}_${i}', 'SECURITY_EVENT', now(), 0, false);
    `);
  }

  let rHigh = await recalculateRisk(adminToken, buyerId);
  console.log('Score after crossing 60 -> HIGH:', rHigh.totalRiskScore, rHigh.riskLevel, 'reviewRequired:', rHigh.reviewRequired, 'action:', rHigh.action);

  // Query alerts
  const alertsRes = await fetch('http://localhost:8080/api/admin/risk/alerts', { headers: { Authorization: 'Bearer ' + adminToken } });
  const alerts = (await alertsRes.json()).data.items;
  const alertForSubject = alerts.find(a => a.subjectId === subjectId);
  console.log('Alert created:', !!alertForSubject, 'severity:', alertForSubject?.severity);

  // Recalculate again with identical evidence -> test alert deduplication!
  await recalculateRisk(adminToken, buyerId);
  const alertsRes2 = await fetch('http://localhost:8080/api/admin/risk/alerts', { headers: { Authorization: 'Bearer ' + adminToken } });
  const alerts2 = (await alertsRes2.json()).data.items;
  const countForSubject = alerts2.filter(a => a.subjectId === subjectId).length;
  console.log('Alert count after second recalculation (deduplication):', countForSubject);
}

test().catch(console.error);
