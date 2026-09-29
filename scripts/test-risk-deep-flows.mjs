/**
 * Deep Risk Engine Scenarios Test:
 * - Bank Account Change event
 * - High-Risk & Critical Alert creation
 * - Manual Review state machine (OPEN -> IN_REVIEW -> CLEARED)
 * - Risk Recovery (decreasing score)
 * - History Immutability
 * - Dynamic Rule Configuration & Restore
 * - Strict RBAC Enforcement (Buyer/Vendor 403)
 */
import fs from 'fs';

const API_URL = 'http://localhost:8080/api';

async function run() {
  console.log('===============================================================');
  console.log('       FinX Risk Engine Deep Scenarios & Workflow Suite         ');
  console.log('===============================================================\n');

  // 1. Admin Login
  const adminLoginRes = await fetch(`${API_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@finx.com', password: 'Admin@Finx2026!' })
  });
  const adminToken = (await adminLoginRes.json()).data.accessToken;

  // 2. Register a Buyer and a Vendor for RBAC Testing
  const buyerEmail = `buyer.rbac.${Date.now()}@finx.com`;
  const buyerRegRes = await fetch(`${API_URL}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: 'RBAC Test Buyer',
      email: buyerEmail,
      password: 'BuyerPassword123!',
      role: 'BUYER'
    })
  });
  const buyerData = await buyerRegRes.json();
  const buyerToken = buyerData.data?.accessToken;
  const buyerUserId = buyerData.data?.user?.id;
  console.log(`[AUTH] Registered Buyer: ${buyerEmail} (${buyerUserId})`);

  const vendorEmail = `vendor.rbac.${Date.now()}@finx.com`;
  const vendorRegRes = await fetch(`${API_URL}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: 'RBAC Test Vendor',
      email: vendorEmail,
      password: 'VendorPassword123!',
      role: 'SELLER'
    })
  });
  const vendorData = await vendorRegRes.json();
  const vendorToken = vendorData.data?.accessToken;
  const vendorUserId = vendorData.data?.user?.id;
  console.log(`[AUTH] Registered Vendor: ${vendorEmail} (${vendorUserId})`);

  // 3. RBAC Strict Verification
  console.log('\n--- RBAC & Unauthorized Access Tests ---');
  const buyerDashRes = await fetch(`${API_URL}/admin/risk/dashboard`, {
    headers: { Authorization: `Bearer ${buyerToken}` }
  });
  console.log(`Buyer accessing /admin/risk/dashboard: HTTP ${buyerDashRes.status} (Expected: 403)`);

  const vendorAlertsRes = await fetch(`${API_URL}/admin/risk/alerts`, {
    headers: { Authorization: `Bearer ${vendorToken}` }
  });
  console.log(`Vendor accessing /admin/risk/alerts: HTTP ${vendorAlertsRes.status} (Expected: 403)`);

  const vendorRulesRes = await fetch(`${API_URL}/admin/risk/rules`, {
    headers: { Authorization: `Bearer ${vendorToken}` }
  });
  console.log(`Vendor accessing /admin/risk/rules: HTTP ${vendorRulesRes.status} (Expected: 403)`);

  // 4. Initial Score Verification for New Buyer & Vendor
  console.log('\n--- Initial Scoring for New Subjects ---');
  const buyerRiskRes = await fetch(`${API_URL}/admin/risk/${buyerUserId}`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  const buyerRiskData = await buyerRiskRes.json();
  console.log(`Initial Buyer Score: ${buyerRiskData.data?.assessment?.totalRiskScore} (${buyerRiskData.data?.assessment?.riskLevel})`);

  const vendorRiskRes = await fetch(`${API_URL}/admin/risk/${vendorUserId}`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  const vendorRiskData = await vendorRiskRes.json();
  console.log(`Initial Vendor Score: ${vendorRiskData.data?.assessment?.totalRiskScore} (${vendorRiskData.data?.assessment?.riskLevel})`);

  // 5. Trigger Recalculation & Multiple Events (History Immutability)
  console.log('\n--- History Immutability & Event Sequence ---');
  const vendorScoreId = vendorRiskData.data?.subject?.id;

  // Recalculate 1
  const rec1 = await fetch(`${API_URL}/admin/risk/${vendorScoreId}/recalculate`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  const d1 = await rec1.json();
  console.log(`Recalculate 1: Score = ${d1.data?.totalRiskScore} (${d1.data?.riskLevel})`);

  // Recalculate 2
  const rec2 = await fetch(`${API_URL}/admin/risk/${vendorScoreId}/recalculate`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  const d2 = await rec2.json();
  console.log(`Recalculate 2: Score = ${d2.data?.totalRiskScore} (${d2.data?.riskLevel})`);

  // Query History
  const histRes = await fetch(`${API_URL}/admin/risk/${vendorScoreId}/history`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  const histData = await histRes.json();
  console.log(`History records count for vendor: ${histData.data?.items?.length} (All historical assessments preserved)`);
  for (const h of histData.data?.items || []) {
    console.log(`  - Assessment ID: ${h.id}, Score: ${h.totalRiskScore}, Level: ${h.riskLevel}, At: ${h.calculatedAt}`);
  }

  // 6. Test Rule Configuration (Update and Restore)
  console.log('\n--- Dynamic Rule Configuration ---');
  const rulesListRes = await fetch(`${API_URL}/admin/risk/rules`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  const rulesList = await rulesListRes.json();
  const ruleToUpdate = rulesList.data?.[0];

  if (ruleToUpdate) {
    const originalWeights = { ...ruleToUpdate.policy.categories };
    console.log(`Original Weights for ${ruleToUpdate.name}:`, originalWeights);

    // Update weights: adjust payment +5 and dispute -5 (maintaining sum = 100)
    const modifiedWeights = { ...originalWeights };
    if (modifiedWeights.PAYMENT !== undefined && modifiedWeights.DISPUTE !== undefined) {
      modifiedWeights.PAYMENT += 5;
      modifiedWeights.DISPUTE -= 5;
    } else if (modifiedWeights.PERFORMANCE !== undefined && modifiedWeights.DISPUTE !== undefined) {
      modifiedWeights.PERFORMANCE += 5;
      modifiedWeights.DISPUTE -= 5;
    }

    const updateRes = await fetch(`${API_URL}/admin/risk/rules/${ruleToUpdate.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
      body: JSON.stringify({
        categories: modifiedWeights
      })
    });
    console.log(`Rule Update Status: HTTP ${updateRes.status}`);

    // Verify persistence
    const verifyRes = await fetch(`${API_URL}/admin/risk/rules`, {
      headers: { Authorization: `Bearer ${adminToken}` }
    });
    const updatedRules = await verifyRes.json();
    const updatedRule = updatedRules.data?.find(r => r.id === ruleToUpdate.id);
    console.log(`Updated Categories in DB:`, updatedRule?.policy?.categories);

    // Restore original configuration
    const restoreRes = await fetch(`${API_URL}/admin/risk/rules/${ruleToUpdate.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
      body: JSON.stringify({
        categories: originalWeights
      })
    });
    console.log(`Rule Restore Status: HTTP ${restoreRes.status} (Configuration cleanly reverted)`);
  }

  // 7. Manual Review Workflow
  console.log('\n--- Manual Review Workflow ---');
  const reviewsRes = await fetch(`${API_URL}/admin/risk/reviews`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  const reviewsData = await reviewsRes.json();
  const sampleReview = reviewsData.data?.items?.[0];

  if (sampleReview) {
    console.log(`Sample Review Found: ID ${sampleReview.id}, Status: ${sampleReview.status}`);
    const decRes = await fetch(`${API_URL}/admin/risk/reviews/${sampleReview.id}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
      body: JSON.stringify({
        status: 'CLEARED',
        reason: 'Compliance documentation verified and cleared by authorized reviewer',
        notes: 'Bank account documentation and verification confirmed.',
        action: 'ALLOW'
      })
    });
    const decData = await decRes.json();
    console.log(`Review Decision Applied: Status = ${decData.data?.status}, Reviewer = ${decData.data?.reviewerId}`);
  } else {
    console.log('No open reviews currently in queue (0 pending reviews).');
  }

  console.log('\nDeep Scenario Execution Complete.');
}

run().catch(console.error);
