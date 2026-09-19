import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root=fileURLToPath(new URL('../../../',import.meta.url));
const out=resolve(root,'scratch/blockchain-ui');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({channel:process.env.UI_BROWSER || 'msedge',headless:true});
const report={environment:'MOCKED_API_UI_ONLY',checks:[]};
const config={configured:true,chainId:'31337',network:'Local EVM',token:'0x'+'4'.repeat(40),symbol:'TEST',decimals:6,confirmations:3,testnet:true,explorer:''};
const deal={id:'project-1',title:'Supplier delivery - September'};
const milestone={id:'milestone-1',title:'Delivery verification',amount:5000,currency:'INR',status:'APPROVED'};
let activePage;
try {
    for(const role of ['BUYER','SELLER','ADMIN','PROJECT_MANAGER']) for(const viewport of [{width:1440,height:1000},{width:390,height:844}]) {
        let status='PENDING';
        const user={id:'user-1',name:'Local UI Fixture',email:'ui@example.test',role};
        const escrow={id:'escrow-1',deal_id:deal.id,status:'ACTIVE',reconciliation_status:'MATCHED',total_atomic:'5000000',funded_atomic:'5000000',released_atomic:'0',refunded_atomic:'0',buyer_address:'0x'+'1'.repeat(40),vendor_address:'0x'+'2'.repeat(40),contract_address:'0x'+'5'.repeat(40),token_symbol:'TEST',token_decimals:'6'};
        const transaction=()=>({id:'tx-1',deal_id:deal.id,operation:'RELEASE',status,actor_id:'user-1',tx_hash:'0x'+'a'.repeat(64),confirmations:status==='CONFIRMED'?'3':'1',from_address:escrow.buyer_address,to_address:escrow.contract_address,calldata:'0x',chain_id:'31337'});
        const context=await browser.newContext({viewport});
        await context.addInitScript(user=>{localStorage.setItem('accessToken','local-ui-fixture');localStorage.setItem('user',JSON.stringify(user));},user);
        await context.route('**/*',async route=>{
            const url=new URL(route.request().url());
            if(!['127.0.0.1','localhost'].includes(url.hostname))return route.abort();
            if(!url.pathname.startsWith('/api/'))return route.continue();
            let data=[];
            const path=url.pathname.slice(4);
            if(path==='/auth/me')data=user;
            else if(path==='/deals')data=[deal];
            else if(path==='/payments/config')data={rail:'BLOCKCHAIN',configured:false,razorpayPaused:true};
            else if(path==='/blockchain/admin')data={escrows:[escrow],transactions:[transaction()],rpcHealth:'CONNECTED',providerStatus:'NOT_CONFIGURED'};
            else if(path===`/blockchain/deals/${deal.id}`)data={exists:true,config,escrow,allocations:[{milestone_id:milestone.id,amount_atomic:'5000000',chain_status:'3'}],transactions:[transaction()]};
            else if(path.endsWith('/milestones'))data=[milestone];
            else if(path.includes('count'))data=0;
            return route.fulfill({contentType:'application/json',headers:{'access-control-allow-origin':'*','access-control-allow-headers':'*'},body:JSON.stringify({success:true,data})});
        });
        const page=await context.newPage();
        activePage=page;
        const errors=[];page.on('pageerror',e=>errors.push(e.message));
        await page.goto(process.env.UI_URL || 'http://127.0.0.1:3001/blockchain');
        await page.getByRole('combobox').first().selectOption(deal.id);
        await page.getByRole('heading',{name:'Transactions',exact:true}).waitFor();
        await page.getByText('PENDING',{exact:true}).first().waitFor();
        assert.equal(await page.getByText('Payment Successful',{exact:true}).count(),0);
        assert.equal(await page.locator('script[src*="razorpay"]').count(),0);
        if(role==='PROJECT_MANAGER')assert.equal(await page.getByRole('button',{name:'Release',exact:true}).count(),0);
        status='CONFIRMED';
        await page.getByRole('button',{name:'Refresh escrow',exact:true}).click();
        await page.getByText('CONFIRMED',{exact:true}).first().waitFor();
        if(role==='ADMIN')await page.getByLabel('Transaction status').selectOption('REVERTED');
        const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth || Array.from(document.querySelectorAll('main')).some(e=>e.scrollWidth>e.clientWidth));
        assert.equal(overflow,false,`${role} ${viewport.width}: layout overflow`);
        assert.deepEqual(errors,[]);
        await page.screenshot({path:resolve(out,`${role.toLowerCase()}-${viewport.width}.png`),fullPage:true});
        status='REVERTED';
        await page.getByRole('button',{name:'Refresh escrow',exact:true}).click();
        await page.getByText('REVERTED',{exact:true}).first().waitFor();
        await page.getByRole('heading',{name:'Transactions',exact:true}).scrollIntoViewIfNeeded();
        await page.screenshot({path:resolve(out,`${role.toLowerCase()}-${viewport.width}-transactions.png`),fullPage:true});
        report.checks.push({role,viewport,status:'PASS',assertions:'Pending, confirmed and reverted; no Razorpay; PM read-only; no page errors or viewport overflow'});
        await context.close();
    }
    report.status='PASS';
} catch(error) {
    report.status='FAIL'; report.error=error.message;
    if(activePage && !activePage.isClosed()) {
        console.error((await activePage.locator('body').innerText()).slice(0,1800));
        await activePage.screenshot({path:resolve(out,'failure.png'),fullPage:true});
    }
    throw error;
} finally {
    await browser.close();
    await writeFile(resolve(root,'e2e_blockchain_ui_report.json'),JSON.stringify(report,null,2));
}
console.log(JSON.stringify(report,null,2));
