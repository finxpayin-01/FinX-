import { loginAdmin, registerUser, recalculateRisk, executeSql, querySql } from './deep-risk-validation.mjs';

async function main() {
  const adminToken = await loginAdmin();
  const ts = Date.now();

  console.log('=== TEST 1: PAYMENT BEHAVIOUR (Buyer) ===');
  const payBuyerId = (await registerUser('Pay Buyer', `pay.${ts}@finx.com`, 'BUYER')).user.id;
  const paySellerId = (await registerUser('Pay Seller', `pay.s.${ts}@finx.com`, 'SELLER')).user.id;
  const dealId = '55555555-4444-3333-2222-111111111111';

  executeSql(`
    MERGE INTO deals (id, buyer_id, seller_id, title, total_amount, currency, status, created_at, updated_at)
    KEY (id) VALUES ('${dealId}', '${payBuyerId}', '${paySellerId}', 'Pay Deal', 100000, 'INR', 'ACTIVE', now(), now());
  `);

  // 9 completed, 1 failed (ratio 0.1 <= 0.3)
  for (let i = 0; i < 9; i++) {
    executeSql(`INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at) VALUES (random_uuid(), 'REF_${ts}_${i}', '${dealId}', 10000, 'INR', 'RAZORPAY', 'COMPLETED', now(), now());`);
  }
  executeSql(`INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at) VALUES (random_uuid(), 'REF_${ts}_9', '${dealId}', 10000, 'INR', 'RAZORPAY', 'FAILED', now(), now());`);

  let r1 = await recalculateRisk(adminToken, payBuyerId);
  console.log('1 isolated failure score:', r1.totalRiskScore, 'Payment Factors:', r1.topRiskFactors.filter(f => f.category === 'PAYMENT'));

  // 4 more failed (total 5 failures / 14 attempts = 0.357 > 0.3)
  for (let i = 10; i < 14; i++) {
    executeSql(`INSERT INTO payment_instructions (id, instruction_reference, deal_id, amount, currency, provider, status, created_at, updated_at) VALUES (random_uuid(), 'REF_${ts}_${i}', '${dealId}', 10000, 'INR', 'RAZORPAY', 'FAILED', now(), now());`);
  }
  let rMulti = await recalculateRisk(adminToken, payBuyerId);
  console.log('Multiple failures score:', rMulti.totalRiskScore, 'Payment Factors:', rMulti.topRiskFactors.filter(f => f.category === 'PAYMENT'));

  console.log('\n=== TEST 2: DISPUTE BEHAVIOUR (Buyer) ===');
  const dispBuyerId = (await registerUser('Disp Buyer', `disp.${ts}@finx.com`, 'BUYER')).user.id;
  const dispSellerId = (await registerUser('Disp Seller', `disp.s.${ts}@finx.com`, 'SELLER')).user.id;
  for (let i = 0; i < 10; i++) {
    const dId = `11111111-2222-3333-4444-55555555550${i}`;
    executeSql(`
      MERGE INTO deals (id, buyer_id, seller_id, title, total_amount, currency, status, created_at, updated_at)
      KEY (id) VALUES ('${dId}', '${dispBuyerId}', '${dispSellerId}', 'Disp Deal ${i}', 50000, 'INR', 'ACTIVE', now(), now());
    `);
  }
  // 1 dispute
  executeSql(`
    INSERT INTO disputes (id, deal_id, raised_by, reason, status, created_at)
    VALUES (random_uuid(), '11111111-2222-3333-4444-555555555500', '${dispBuyerId}', 'Dispute 1', 'OPEN', now());
  `);
  let disp1 = await recalculateRisk(adminToken, dispBuyerId);
  console.log('1 dispute / 10 deals score:', disp1.totalRiskScore, 'Dispute factors:', disp1.topRiskFactors.filter(f => f.category === 'DISPUTE'));

  // 4 more disputes
  for (let i = 1; i <= 4; i++) {
    executeSql(`
      INSERT INTO disputes (id, deal_id, raised_by, reason, status, created_at)
      VALUES (random_uuid(), '11111111-2222-3333-4444-55555555550${i}', '${dispBuyerId}', 'Dispute ${i}', 'LOST', now());
    `);
  }
  let dispMulti = await recalculateRisk(adminToken, dispBuyerId);
  console.log('5 disputes / 10 deals score:', dispMulti.totalRiskScore, 'Dispute factors:', dispMulti.topRiskFactors.filter(f => f.category === 'DISPUTE'));

  console.log('\n=== TEST 3: VENDOR MILESTONE PERFORMANCE (Vendor) ===');
  const perfVendorId = (await registerUser('Perf Vendor', `perf.${ts}@finx.com`, 'SELLER')).user.id;
  const perfBuyerId = (await registerUser('Perf Buyer', `perf.b.${ts}@finx.com`, 'BUYER')).user.id;
  const perfDealId = '33333333-4444-5555-6666-777777777777';
  const mId = '44444444-5555-6666-7777-888888888888';
  executeSql(`
    MERGE INTO deals (id, buyer_id, seller_id, title, total_amount, currency, status, created_at, updated_at)
    KEY (id) VALUES ('${perfDealId}', '${perfBuyerId}', '${perfVendorId}', 'Milestone Deal', 200000, 'INR', 'ACTIVE', now(), now());
    MERGE INTO milestones (id, deal_id, title, sequence, amount, currency, status, created_at, updated_at)
    KEY (id) VALUES ('${mId}', '${perfDealId}', 'Milestone 1', 1, 50000, 'INR', 'IN_PROGRESS', now(), now());
  `);
  // 9 approved, 1 rejected
  for (let i = 0; i < 9; i++) {
    executeSql(`INSERT INTO deliverables (id, milestone_id, submitted_by, file_name, file_url, status, submitted_at) VALUES (random_uuid(), '${mId}', '${perfVendorId}', 'doc_${i}.pdf', 'http://files/doc_${i}.pdf', 'APPROVED', now());`);
  }
  executeSql(`INSERT INTO deliverables (id, milestone_id, submitted_by, file_name, file_url, status, submitted_at) VALUES (random_uuid(), '${mId}', '${perfVendorId}', 'doc_rej.pdf', 'http://files/doc_rej.pdf', 'REJECTED', now());`);
  let perf1 = await recalculateRisk(adminToken, perfVendorId);
  console.log('1 rejection / 10 deliverables score:', perf1.totalRiskScore, 'Performance factors:', perf1.topRiskFactors.filter(f => f.category === 'PERFORMANCE'));

  // 5 more rejected deliverables
  for (let i = 0; i < 5; i++) {
    executeSql(`INSERT INTO deliverables (id, milestone_id, submitted_by, file_name, file_url, status, submitted_at) VALUES (random_uuid(), '${mId}', '${perfVendorId}', 'doc_rej_${i}.pdf', 'http://files/doc_rej_${i}.pdf', 'REJECTED', now());`);
  }
  let perfMulti = await recalculateRisk(adminToken, perfVendorId);
  console.log('6 rejections / 15 deliverables score:', perfMulti.totalRiskScore, 'Performance factors:', perfMulti.topRiskFactors.filter(f => f.category === 'PERFORMANCE'));

  console.log('\n=== TEST 4: TRANSACTION ANOMALY ===');
  const anomBuyerId = (await registerUser('Anom Buyer', `anom.b.${ts}@finx.com`, 'BUYER')).user.id;
  const anomSellerId = (await registerUser('Anom Seller', `anom.s.${ts}@finx.com`, 'SELLER')).user.id;
  // 5 baseline deals created 48h ago
  for (let i = 0; i < 5; i++) {
    executeSql(`
      INSERT INTO deals (id, buyer_id, seller_id, title, total_amount, currency, status, created_at, updated_at)
      VALUES (random_uuid(), '${anomBuyerId}', '${anomSellerId}', 'Historic ${i}', 50000, 'INR', 'COMPLETED', dateadd('hour', -48, now()), dateadd('hour', -48, now()));
    `);
  }
  // Spike deal now
  executeSql(`
    INSERT INTO deals (id, buyer_id, seller_id, title, total_amount, currency, status, created_at, updated_at)
    VALUES (random_uuid(), '${anomBuyerId}', '${anomSellerId}', 'Spike Deal', 350000, 'INR', 'ACTIVE', now(), now());
  `);
  let anomRes = await recalculateRisk(adminToken, anomBuyerId);
  console.log('Transaction anomaly score:', anomRes.totalRiskScore, 'Transaction factors:', anomRes.topRiskFactors.filter(f => f.category === 'TRANSACTION'));

  console.log('\n=== TEST 5: BANK VERIFICATION SEPARATION ===');
  const bankOrgId = '77777777-6666-5555-4444-333333333333';
  const bankUser = (await registerUser('Bank Sep User', `bank.sep.${ts}@finx.com`, 'BUYER')).user.id;
  executeSql(`
    MERGE INTO organizations (id, name, legal_name, entity_type, verification_status, created_at, updated_at)
    KEY (id) VALUES ('${bankOrgId}', 'Bank Sep Org', 'Bank Sep Org Pvt Ltd', 'PRIVATE_LIMITED', 'NOT_STARTED', now(), now());
    MERGE INTO organization_members (id, organization_id, user_id, role, joined_at)
    KEY (organization_id, user_id) VALUES (random_uuid(), '${bankOrgId}', '${bankUser}', 'OWNER', now());
    UPDATE risk_scores SET organization_id='${bankOrgId}' WHERE user_id='${bankUser}';
  `);
  // Bank Account with FAILED status
  const bankAccId = '88888888-7777-6666-5555-444444444444';
  executeSql(`
    MERGE INTO organization_bank_accounts (id, organization_id, account_holder_name, account_number_masked, account_number_hash, ifsc_code, bank_name, is_primary, verification_status, account_type, created_at, updated_at)
    KEY (id)
    VALUES ('${bankAccId}', '${bankOrgId}', 'Bank Sep Org', 'XXXX1234', 'hash123', 'HDFC0001234', 'HDFC Bank', true, 'FAILED', 'CURRENT', now(), now());
  `);
  let bankFailedRes = await recalculateRisk(adminToken, bankUser);
  console.log('Bank Failed score:', bankFailedRes.totalRiskScore, 'Factors:', bankFailedRes.topRiskFactors.filter(f => f.code.includes('BANK')));

  // Now Bank Account is VERIFIED, but BANK_ACCOUNT_CHANGED event is fired
  executeSql(`
    UPDATE organization_bank_accounts SET verification_status='VERIFIED' WHERE id='${bankAccId}';
  `);
  const bankSubjId = (await (await fetch(`http://localhost:8080/api/admin/risk/${bankUser}`, { headers: { Authorization: `Bearer ${adminToken}` } })).json()).data.subject.id;
  executeSql(`
    INSERT INTO risk_events (id, subject_id, source_key, event_type, occurred_at, attempts, dead_letter)
    VALUES (random_uuid(), '${bankSubjId}', 'BANK_CHANGE_${ts}', 'BANK_ACCOUNT_CHANGED', now(), 0, false);
  `);
  let bankChangedRes = await recalculateRisk(adminToken, bankUser);
  console.log('Bank Changed score:', bankChangedRes.totalRiskScore, 'Factors:', bankChangedRes.topRiskFactors.filter(f => f.code.includes('BANK')));
}

main().catch(console.error);
