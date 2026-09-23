import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import assert from 'node:assert/strict';
import { JsonRpcProvider, Wallet, HDNodeWallet, NonceManager, ContractFactory, Contract, id, MaxUint256 } from 'ethers';

const root=resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const contracts=resolve(root,'blockchain/contracts');
const require=createRequire(import.meta.url);
const report={ environment:'LOCAL_EVM_TEST_TOKENS', publicTestnet:false, startedAt:new Date().toISOString(), status:'RUNNING', checks:[], razorpayVerification:'Disabled credentials, rejected endpoint and no fiat records; unit tests verify no provider interactions', providers:'On-ramp and off-ramp not configured; test tokens minted locally' };
const children=[];
let provider, scratch, backendLog='';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function freePort(){const s=createServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function stopChild(child) {
    if (child.exitCode !== null) return;
    if (process.platform === 'win32') {
        const killer=spawn('taskkill',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore',windowsHide:true});
        await new Promise(r=>killer.once('exit',r));
    } else child.kill('SIGTERM');
    if(child.exitCode===null) await Promise.race([new Promise(r=>child.once('exit',r)),sleep(10000)]);
}
async function waitFor(fn,label,seconds=60){for(let i=0;i<seconds*2;i++){try{const value=await fn();if(value)return value;}catch{}await sleep(500);}throw new Error(`Timeout: ${label}`);}
function check(name,condition){assert.ok(condition,name);report.checks.push({name,status:'PASS'});console.log(`PASS ${name}`);}
async function artifact(file,name){return JSON.parse(await readFile(resolve(contracts,'out',file,`${name}.json`),'utf8'));}
try {
    scratch=await mkdtemp(join(tmpdir(),'finx-chain-e2e-'));
    const rpcPort=await freePort(), apiPort=await freePort();
    const mnemonic=Wallet.createRandom().mnemonic.phrase;
    const arch={x64:'amd64',arm64:'arm64'}[process.arch];
    const pkg=`@foundry-rs/anvil-${process.platform}-${arch}`;
    const binary=join(dirname(require.resolve(`${pkg}/package.json`)),'bin',process.platform==='win32'?'anvil.exe':'anvil');
    const anvil=spawn(binary,['--host','127.0.0.1','--port',String(rpcPort),'--chain-id','31337','--mnemonic',mnemonic,'--silent'],{stdio:'ignore',windowsHide:true});children.push(anvil);
    provider=new JsonRpcProvider(`http://127.0.0.1:${rpcPort}`,31337,{staticNetwork:true,cacheTimeout:-1});
    provider.pollingInterval=100;
    await waitFor(()=>provider.getBlockNumber().then(()=>true),'local EVM');
    const wallets=[0,1,2,3].map(n=>HDNodeWallet.fromPhrase(mnemonic,undefined,`m/44'/60'/0'/0/${n}`).connect(provider));
    const [operator,buyerWallet,vendorWallet,strangerWallet]=wallets;
    const operatorSigner=new NonceManager(operator);
    const deploy=async(file,name,args=[])=>{const a=await artifact(file,name);const c=await new ContractFactory(a.abi,a.bytecode.object,operatorSigner).deploy(...args);await c.waitForDeployment();return c;};
    const governance=await deploy('LocalCouncil.sol','LocalCouncil');
    const arbitrator=await deploy('LocalCouncil.sol','LocalCouncil');
    const council=await deploy('LocalCouncil.sol','LocalCouncil');
    const token=await deploy('Fixtures.sol','TestToken',[6]);
    const registry=await deploy('SupportedAssetRegistry.sol','SupportedAssetRegistry',[governance.target,86400]);
    await (await governance.execute(registry.target,registry.interface.encodeFunctionData('configureAsset',[token.target,[6,true,true,true,1,MaxUint256]]))).wait();
    const factory=await deploy('FinXEscrowFactory.sol','FinXEscrowFactory',[governance.target,86400,registry.target,arbitrator.target,council.target]);
    await (await governance.execute(factory.target,factory.interface.encodeFunctionData('grantRole',[id('CREATOR_ROLE'),operator.address]))).wait();
    check('Contracts deployed to a real local EVM',true);
    const password=`Local!A9${randomBytes(16).toString('hex')}`;
    const jdbc=`jdbc:h2:file:${join(scratch,'database').replaceAll('\\','/')};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`;
    const env={...process.env,SPRING_CONFIG_IMPORT:'',SPRING_PROFILES_ACTIVE:'blockchain-e2e',PORT:String(apiPort),
        SPRING_DATASOURCE_URL:jdbc,SPRING_DATASOURCE_USERNAME:'sa',SPRING_DATASOURCE_PASSWORD:'',
        JWT_SECRET:randomBytes(48).toString('hex'),FINX_ADMIN_INITIAL_PASSWORD:password,RATE_LIMIT_ENABLED:'false',
        PAYMENT_RAIL:'BLOCKCHAIN',BLOCKCHAIN_ENABLED:'true',BLOCKCHAIN_RPC_URL:`http://127.0.0.1:${rpcPort}`,
        BLOCKCHAIN_CHAIN_ID:'31337',ESCROW_FACTORY_ADDRESS:factory.target,USDC_CONTRACT_ADDRESS:token.target,
        BLOCKCHAIN_CREATOR_ADDRESS:operator.address,BLOCKCHAIN_ARBITRATOR_ADDRESS:arbitrator.target,
        BLOCKCHAIN_TOKEN_SYMBOL:'TEST',BLOCKCHAIN_TOKEN_DECIMALS:'6',BLOCKCHAIN_CONFIRMATIONS:'3',BLOCKCHAIN_POLL_MS:'500',
        BLOCKCHAIN_START_BLOCK:'0',BLOCKCHAIN_TESTNET:'true',BLOCKCHAIN_NETWORK_NAME:'Local EVM',
        RAZORPAY_KEY_ID:'',RAZORPAY_KEY_SECRET:'',RAZORPAY_WEBHOOK_SECRET:'',ONRAMP_API_KEY:'',ONRAMP_WEBHOOK_SECRET:'',
        GOOGLE_CLIENT_ID:'',GOOGLE_CLIENT_SECRET:'',MAILERSEND_API_KEY:'',MAILERSEND_DOMAIN:''};
    const launchBackend=()=>{
        const child=spawn('java',['-jar',resolve(root,'backend/target/finx-backend-1.0.0.jar'),
            '--spring.config.import=','--spring.datasource.driver-class-name=org.h2.Driver',
            '--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect','--spring.jpa.hibernate.ddl-auto=none',
            '--spring.flyway.enabled=true','--logging.level.root=WARN','--logging.level.com.finx=INFO',
            '--spring.mail.host=127.0.0.1','--spring.mail.port=1'],{cwd:scratch,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
        child.stdout.on('data',b=>{backendLog=(backendLog+b.toString()).slice(-18000);});child.stderr.on('data',b=>{backendLog=(backendLog+b.toString()).slice(-18000);});
        children.push(child);return child;
    };
    let backend=launchBackend();
    const base=`http://127.0.0.1:${apiPort}/api`;
    await waitFor(async()=>{const r=await fetch(`${base}/health`);return r.ok;},'backend startup',120);
    async function api(path,{actor,method='GET',body,key,expect}={}){
        const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(actor?{Authorization:`Bearer ${actor.accessToken}`} : {}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined});
        if(expect){assert.equal(response.status,expect,`${method} ${path}`);return;}
        const payload=await response.json();
        if(!response.ok)throw new Error(`${method} ${path}: HTTP ${response.status} ${payload.message || ''}`);
        return payload.data ?? payload;
    }
    const register=role=>api('/auth/register',{method:'POST',body:{name:`Local ${role}`,email:`${role.toLowerCase()}-${randomUUID()}@example.test`,password,role}});
    const buyer=await register('BUYER'),vendor=await register('SELLER'),outsider=await register('BUYER');
    const admin=await api('/auth/login',{method:'POST',body:{email:'admin@finx.com',password}});
    check('Buyer/vendor registration and admin login',!!buyer.accessToken && !!vendor.accessToken && !!admin.accessToken);
    for(const [actor,wallet] of [[buyer,buyerWallet],[vendor,vendorWallet]]) {
        const challenge=await api('/blockchain/wallet/challenge',{actor,method:'POST',body:{address:wallet.address}});
        const signature=await wallet.signMessage(challenge.message);
        await api('/blockchain/wallet/verify',{actor,method:'POST',body:{signature}});
    }
    check('Wallet ownership verified using signed nonce challenges',true);
    const deal=await api('/deals',{actor:buyer,method:'POST',body:{title:'Local blockchain E2E',sellerId:vendor.user.id,totalAmount:10000,currency:'INR'}});
    await api(`/deals/${deal.id}/accept`,{actor:vendor,method:'POST'});
    const milestones=[];
    for(let n=1;n<=2;n++) milestones.push(await api(`/deals/${deal.id}/milestones`,{actor:buyer,method:'POST',body:{title:`Milestone ${n}`,amount:5000,currency:'INR',sequence:n}}));
    const path=`/blockchain/deals/${deal.id}`;
    await api(path,{actor:outsider,expect:401});
    await api(path,{actor:buyer,method:'POST',key:randomUUID(),body:{allocations:milestones.map(m=>({milestoneId:m.id,amount:'2.5'}))}});
    check('Escrow creation and project IDOR protection',true);
    const snapshot=()=>api(path,{actor:buyer});
    const waitState=async state=>waitFor(async()=>{const s=await snapshot();return s.escrow.status===state && s.escrow.reconciliation_status==='MATCHED';},state);
    const mine=async()=>{for(let n=0;n<3;n++)await provider.send('evm_mine',[]);};
    async function prepare(actor,operation,milestoneId){return api(`${path}/intents`,{actor,method:'POST',key:randomUUID(),body:{operation,milestoneId,reason:'Local E2E authorized action'}});}
    async function execute(actor,wallet,operation,milestoneId){
        const intent=await prepare(actor,operation,milestoneId);
        const sent=operation.startsWith('RESOLVE_') ? await arbitrator.execute(intent.to_address,intent.calldata)
            : await wallet.sendTransaction({to:intent.to_address,data:intent.calldata});
        await api(`/blockchain/transactions/${intent.id}`,{actor,method:'POST',body:{txHash:sent.hash}});
        const pending=await api(`/blockchain/transactions/${intent.id}`,{actor});
        check(`${operation} is not successful on submission`,['SUBMITTED','PENDING'].includes(pending.status));
        await sent.wait();await mine();
        await waitFor(async()=>{const t=await api(`/blockchain/transactions/${intent.id}`,{actor});if(['FAILED','REVERTED'].includes(t.status))throw new Error(t.status);return t.status==='CONFIRMED';},`${operation} confirmation`);
        return {intent,sent};
    }
    await execute(admin,operatorSigner,'CREATE');await waitState('CREATED');
    await execute(buyer,buyerWallet,'ACCEPT');await execute(vendor,vendorWallet,'ACCEPT');await waitState('AWAITING_FUNDING');
    await api(`${path}/funding-intent`,{actor:buyer,method:'POST',key:randomUUID()});
    await (await token.mint(buyerWallet.address,5_000_000n)).wait();
    await execute(buyer,buyerWallet,'ALLOWANCE');await execute(buyer,buyerWallet,'FUND');await waitState('FUNDED');
    let state=await snapshot();assert.equal(state.escrow.funded_atomic,'5000000');
    check('Exact token funding indexed; INR commercial value remains separate',true);
    const escrowAbi=(await artifact('FinXEscrow.sol','FinXEscrow')).abi;
    const escrow=new Contract(state.escrow.contract_address,escrowAbi,provider);
    await assert.rejects(()=>escrow.connect(strangerWallet).releaseMilestone(id(`finx:${milestones[0].id}`)));
    check('Unauthorized on-chain release rejected',true);
    for(const m of milestones){
        await api(`/milestones/${m.id}/start`,{actor:vendor,method:'POST'});
        await api(`/milestones/${m.id}/submit`,{actor:vendor,method:'POST',body:{fileName:'test.txt',fileUrl:'https://example.test/test.txt',description:'Local E2E deliverable'}});
        await api(`/milestones/${m.id}/approve`,{actor:buyer,method:'POST'});
        await execute(vendor,vendorWallet,'SUBMIT',m.id);
        await execute(buyer,buyerWallet,'APPROVE',m.id);
        await waitFor(async()=> (await snapshot()).allocations.find(a=>a.milestone_id===m.id).chain_status==='3','allocation approval');
    }
    const release=await execute(buyer,buyerWallet,'RELEASE',milestones[0].id);
    await waitFor(async()=> (await snapshot()).escrow.released_atomic==='2500000','released funds projection');
    assert.equal(await token.balanceOf(vendorWallet.address),2_500_000n);
    await assert.rejects(()=>escrow.connect(buyerWallet).releaseMilestone(id(`finx:${milestones[0].id}`)));
    check('Vendor received actual tokens; double release reverts',true);
    const dispute=await api('/disputes',{actor:buyer,method:'POST',body:{dealId:deal.id,milestoneId:milestones[1].id,reason:'Local test dispute'}});
    await execute(buyer,buyerWallet,'DISPUTE',milestones[1].id);await waitState('DISPUTED');
    await api(`${path}/intents`,{actor:buyer,method:'POST',key:randomUUID(),body:{operation:'RELEASE',milestoneId:milestones[1].id},expect:400});
    check('Disputed project blocks release',true);
    await execute(admin,operatorSigner,'RESOLVE_BUYER',milestones[1].id);await waitState('COMPLETED');
    await api(`/disputes/${dispute.id}/resolve`,{actor:admin,method:'PATCH',body:{resolutionNotes:'Confirmed council refund to original buyer'}});
    assert.equal(await token.balanceOf(buyerWallet.address),2_500_000n);
    state=await snapshot();assert.equal(state.escrow.refunded_atomic,'2500000');
    check('Authorized council refund confirmed and dispute case closed',true);
    const audit=await api('/admin/audit-logs',{actor:admin});
    check('Confirmed release and refund audit records exist',audit.some(a=>a.action==='MILESTONE_RELEASED') && audit.some(a=>a.action==='REFUND_CONFIRMED'));
    const config=await api('/payments/config');assert.equal(config.rail,'BLOCKCHAIN');
    await api('/payments/create-order',{actor:buyer,method:'POST',body:{dealId:deal.id,milestoneId:milestones[0].id},expect:400});
    const payments=await api(`/payments/deal/${deal.id}`,{actor:buyer});assert.equal(payments.length,0);
    check('Razorpay paused; no fiat payment records created',true);
    const before=JSON.stringify(state.allocations);
    await stopChild(backend);backend=launchBackend();
    await waitFor(async()=>{const r=await fetch(`${base}/health`);return r.ok;},'backend restart',120);
    await mine();await sleep(1500);
    state=await snapshot();assert.equal(JSON.stringify(state.allocations),before);assert.equal(state.escrow.released_atomic,'2500000');
    check('Restart preserves indexed state without duplicate credit',true);
    report.status='PASS';report.dealId=deal.id;report.escrow=state.escrow.contract_address;report.releaseHash=release.sent.hash;
    report.final={fundedAtomic:state.escrow.funded_atomic,releasedAtomic:state.escrow.released_atomic,refundedAtomic:state.escrow.refunded_atomic};
} catch(error) {
    report.status='FAIL';report.error=error.message;
    console.error(error.message);
    // Only local, credential-free operational errors; do not persist backend logs.
    const operational=backendLog.split('\n').filter(line=>/ERROR|Exception|Caused by/.test(line)).slice(-12);
    console.error(operational.join('\n'));
    process.exitCode=1;
} finally {
    if(provider)provider.destroy();
    for(const child of children.reverse())await stopChild(child);
    if(scratch && dirname(scratch)===resolve(tmpdir()) && scratch.includes('finx-chain-e2e-')) await rm(scratch,{recursive:true,force:true}).catch(()=>{});
    report.finishedAt=new Date().toISOString();
    await writeFile(resolve(root,'e2e_blockchain_report.json'),JSON.stringify(report,null,2)+'\n');
    console.log(`E2E ${report.status}: ${report.checks.length} checks. Report: e2e_blockchain_report.json`);
    process.exit(process.exitCode || 0);
}
