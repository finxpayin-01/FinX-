import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const fixture = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const output = path.dirname(process.argv[2]);
const base = 'http://localhost:3108';
const api = 'http://localhost:8087/api';
const results = [];
const browser = await chromium.launch({ headless: true });
const contexts = [];
const shot = (page, name) => page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true });
async function login(role) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } }); contexts.push(context);
    const page = await context.newPage(); page.on('dialog', d => d.accept());
    await page.goto(`${base}/login`);
    await page.getByPlaceholder('you@company.com').fill(`${role}@invoice-fixture.local`);
    await page.getByPlaceholder('Enter your password').fill('InvoiceFixture123!');
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await page.waitForURL(url => !url.pathname.startsWith('/login'));
    results.push(`${role} real UI login PASS`); return page;
}
async function request(page, method, url, data) {
    return page.evaluate(async ({ api, method, url, data }) => {
        const response = await fetch(api+url, { method, headers: { Authorization: `Bearer ${localStorage.getItem('accessToken')}`, 'Content-Type': 'application/json' }, ...(data ? { body: JSON.stringify(data) } : {}) });
        return { status: response.status, body: await response.json() };
    }, { api, method, url, data });
}
function pdf(text) {
    const objects = ['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
    const stream=`BT /F1 12 Tf 50 720 Td (${text}) Tj ET`; objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
    let body='%PDF-1.4\n'; const offsets=[0]; objects.forEach((o,i)=>{ offsets.push(Buffer.byteLength(body)); body+=`${i+1} 0 obj\n${o}\nendobj\n`; });
    const xref=Buffer.byteLength(body); body+=`xref\n0 6\n0000000000 65535 f \n`+offsets.slice(1).map(o=>String(o).padStart(10,'0')+' 00000 n \n').join('')+`trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
    return Buffer.from(body);
}
const original = pdf('INV-E2E-100 Apex Engineering to Horizon Manufacturing INR 250000');
const workspace = id => `${base}/vendor/projects/${fixture.dealId}/milestones/${id}`;
async function upload(page, index, number, amount, bytes, capture=false) {
    await page.goto(workspace(fixture.milestoneIds[index]));
    await page.getByRole('button', { name: 'Upload Tax Invoice', exact:true }).click();
    await page.getByLabel('Invoice document', {exact:true}).setInputFiles({ name:'invoice.pdf', mimeType:'application/pdf', buffer:bytes });
    await page.getByLabel('Invoice number', {exact:true}).fill(number);
    await page.getByLabel('Invoice total', {exact:true}).fill(amount);
    await page.getByLabel('Invoice date', {exact:true}).fill(new Date().toISOString().slice(0,10));
    for (const [field,value] of Object.entries({ vendorLegalName:fixture.vendorName, vendorPan:fixture.vendorPan, vendorGstin:fixture.vendorGstin, buyerLegalName:fixture.buyerName, buyerGstin:fixture.buyerGstin })) await page.getByLabel(field,{exact:true}).fill(value);
    if(capture) await shot(page,'01_vendor_invoice_upload');
    const response=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith(`/milestones/${fixture.milestoneIds[index]}/invoices`));
    await page.getByRole('button',{name:'Upload & Verify',exact:true}).click();
    if(capture) await shot(page,'02_invoice_processing');
    const res=await response; assert.equal(res.status(),201,await res.text());
    const result=(await res.json()).data;
    await page.getByText(number,{exact:true}).waitFor();
    return result;
}
try {
    const vendor=await login('vendor');
    await vendor.goto(`${base}/vendor/projects`);
    await vendor.getByText('Invoice browser acceptance project',{exact:true}).first().waitFor();
    await vendor.goto(`${base}/vendor/projects/${fixture.dealId}`);
    await vendor.getByText('Invoice milestone 1',{exact:true}).first().waitFor();
    const first=await upload(vendor,0,'INV-E2E-100','250000',original,true);
    assert.equal(first.matchingStatus,'STRONG_MATCH'); assert.equal(first.extractionStatus,'MANUAL_ENTRY');
    assert.equal(first.status,'MATCHED'); assert.equal(first.matchScore,100);
    await shot(vendor,'03_strong_match'); await shot(vendor,'04_match_breakdown'); results.push('Upload, server hash, manual extraction, strong match PASS');
    const pm=await login('pm');
    const notifications=await request(pm,'GET','/notifications');
    assert.equal(notifications.status,200); assert.match(JSON.stringify(notifications.body),/Invoice Verification Review Required/);
    await pm.goto(`${base}/project-manager/reviews/${fixture.milestoneIds[0]}`);
    await pm.getByRole('button',{name:'Approve Invoice',exact:true}).waitFor(); await shot(pm,'05_pm_review');
    const approval=pm.waitForResponse(r=>r.request().method()==='POST' && r.url().includes('/decision'));
    await pm.getByRole('button',{name:'Approve Invoice',exact:true}).click(); assert.equal((await approval).status(),200);
    await pm.getByRole('button',{name:'Approve Invoice',exact:true}).waitFor({state:'hidden'}); await shot(pm,'06_invoice_approved');
    results.push('Assigned PM notification and human approval PASS');
    const duplicate=await upload(vendor,1,'INV-E2E-100','250000',original);
    assert.equal(duplicate.duplicateConfidence,'EXACT_DUPLICATE'); assert.equal(duplicate.status,'REVIEW_REQUIRED');
    assert.equal(duplicate.documentHash,first.documentHash); await shot(vendor,'07_duplicate_detected'); results.push('Same document reused across milestones PASS');
    const mismatch=await upload(vendor,2,'INV-E2E-200','340000',pdf('INV-E2E-200 INR 340000'));
    assert.equal(mismatch.status,'REVIEW_REQUIRED'); assert.ok(mismatch.checks.some(c=>c.reasonCode==='INVOICE_AMOUNT_MISMATCH')); await shot(vendor,'08_amount_mismatch');
    await vendor.reload(); await vendor.getByTestId('notification-bell-btn').click();
    await vendor.getByText('Invoice Approved',{exact:true}).first().waitFor(); await shot(vendor,'09_vendor_notification'); results.push('Mismatch and vendor approval notification PASS');
    const other=await login('other-vendor');
    const denied=await request(other,'GET',`/invoices/${first.id}`); assert.equal(denied.status,401);
    assert.equal((await request(other,'GET',`/invoices/${first.id}/checks`)).status,401); results.push('Vendor B cross-company invoice/check access blocked PASS');
    const buyer=await login('buyer'); await buyer.goto(`${base}/corporate/projects/${fixture.dealId}/milestones/${fixture.milestoneIds[0]}`);
    await buyer.getByRole('region',{name:'Invoice verification'}).getByText('INV-E2E-100',{exact:true}).waitFor(); results.push('Buyer own-deal verification visibility PASS');
    const admin=await login('admin'); await admin.goto(`${base}/admin/invoices`); await admin.getByText('INV-E2E-200',{exact:true}).waitFor(); await shot(admin,'10_admin_invoice_dashboard');
    const dashboard=await request(admin,'GET','/admin/invoices/dashboard'); assert.equal(dashboard.body.data.totalInvoicesProcessed,3); assert.equal(dashboard.body.data.duplicatesDetected,1);
    results.push('Admin dashboard actual counts PASS');
} catch(error) {
    results.push(`FAIL: ${error.message}`); console.error(error); process.exitCode=1;
} finally {
    fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({ results, success:!process.exitCode },null,2));
    console.log(results.join('\n')); await browser.close();
}
