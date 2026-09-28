package com.finx.blockchain;

import com.finx.audit.service.AuditService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.crypto.Hash;
import java.math.*;
import java.util.*;
import static com.finx.blockchain.ChainStore.*;

@Service
public class ChainIndexer {
    @Configuration @EnableScheduling static class Scheduling {}
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ChainIndexer.class);
    private static final String[] STATES={"CREATED","AWAITING_FUNDING","FUNDED","ACTIVE","DISPUTED","COMPLETED","REFUNDED","CANCELLED"};
    private final ChainConfig config;
    private final ChainGateway chain;
    private final ChainStore store;
    private final AuditService audit;
    private final TransactionTemplate db;
    private volatile String health="NOT_CHECKED";
    private UUID transactionCursor=new UUID(0,0), escrowCursor=new UUID(0,0);
    public ChainIndexer(ChainConfig config,ChainGateway chain,ChainStore store,AuditService audit,PlatformTransactionManager manager) {
        this.config=config;this.chain=chain;this.store=store;this.audit=audit;this.db=new TransactionTemplate(manager);
    }
    public String health() { return health; }

    @Scheduled(fixedDelayString="${BLOCKCHAIN_POLL_MS:10000}")
    public void poll() {
        if (!config.configured()) { health="NOT_CONFIGURED";return; }
        try {
            chain.validateNetwork(); long head=chain.head(); long finalized=head-config.confirmations+1;
            if (finalized<0) return;
            var pending=store.jdbc().queryForList("select * from blockchain_transactions where chain_id = ? and id > ? and tx_hash is not null and status in ('SUBMITTED','PENDING') order by id limit 200",config.chainId,transactionCursor);
            for (var tx:pending) {
                try { track(tx,head,finalized); }
                catch(Exception ex) { log.warn("chain_receipt_pending tx={} code={}",str(tx,"id"),ex.getClass().getSimpleName()); }
                transactionCursor=uuid(tx,"id");
            }
            if(pending.size()<200)transactionCursor=new UUID(0,0);
            var escrows=store.jdbc().queryForList("select * from blockchain_escrows where chain_id = ? and id > ? and reconciliation_status <> 'REORG_REVIEW' order by id limit 200",config.chainId,escrowCursor);
            for (var e:escrows) {
                try { reconcile(e,finalized); }
                catch(Exception ex) {
                    store.jdbc().update("update blockchain_escrows set reconciliation_status = 'PENDING_RECONCILIATION' where id = ? and reconciliation_status <> 'REORG_REVIEW'",uuid(e,"id"));
                    log.warn("chain_reconciliation_failed escrow={} code={}",str(e,"id"),ex.getClass().getSimpleName());
                }
                escrowCursor=uuid(e,"id");
            }
            if(escrows.size()<200)escrowCursor=new UUID(0,0);
            health="CONNECTED";
        } catch(Exception ex) { health="RPC_OR_INDEXER_UNAVAILABLE"; log.warn("chain_poll_failed code={}",ex.getClass().getSimpleName()); }
    }

    void track(Map<String,Object> tx,long head,long finalized) throws Exception {
        String hash=str(tx,"tx_hash"); UUID id=uuid(tx,"id");
        var receipt=chain.receipt(hash);
        if (receipt.isEmpty()) {
            if (str(tx,"status").equals("CONFIRMED")) quarantine(uuid(tx,"escrow_id"),"CONFIRMED_RECEIPT_MISSING");
            else store.jdbc().update("update blockchain_transactions set status = 'PENDING' where id = ?",id);
            return;
        }
        TransactionReceipt r=receipt.get(); long block=r.getBlockNumber().longValueExact();
        if (!r.getBlockHash().equalsIgnoreCase(chain.blockHash(block))) {
            quarantine(uuid(tx,"escrow_id"),"NON_CANONICAL_RECEIPT"); return;
        }
        var actual=chain.transaction(hash);
        if (actual.isEmpty()) return;
        Transaction t=actual.get();
        boolean matches=str(tx,"from_address").equalsIgnoreCase(t.getFrom()) && str(tx,"to_address").equalsIgnoreCase(t.getTo())
                && str(tx,"calldata").equalsIgnoreCase(t.getInput()) && BigInteger.ZERO.equals(t.getValue());
        if (str(tx,"operation").startsWith("RESOLVE_")) {
            String council=chain.address(str(tx,"to_address"),"arbitrator",block);
            String milestone=org.web3j.utils.Numeric.toHexString(EscrowAbi.id(uuid(tx,"milestone_id")).getValue());
            boolean toVendor=str(tx,"operation").equals("RESOLVE_VENDOR");
            matches=council.equalsIgnoreCase(str(tx,"from_address")) && council.equalsIgnoreCase(t.getTo())
                    && r.getLogs().stream().anyMatch(event -> event.getAddress().equalsIgnoreCase(str(tx,"to_address"))
                    && event.getTopics().size()==3 && event.getTopics().get(0).equals(Hash.sha3String("DisputeResolved(bytes32,bytes32,bool)"))
                    && event.getTopics().get(2).equalsIgnoreCase(milestone) && EscrowAbi.word(event.getData(),0).equals(toVendor?BigInteger.ONE:BigInteger.ZERO));
        }
        if (!matches) { finish(tx,"FAILED",r,head,"TRANSACTION_MISMATCH");return; }
        if (block>finalized) {
            store.jdbc().update("update blockchain_transactions set status='PENDING', confirmations=? where id=?",Math.max(0,head-block+1),id);return;
        }
        if (!r.isStatusOK()) { finish(tx,"REVERTED",r,head,"EVM_REVERT");return; }
        finish(tx,"CONFIRMED",r,head,null);
    }

    private void finish(Map<String,Object> tx,String state,TransactionReceipt receipt,long head,String error) {
        db.executeWithoutResult(s -> {
            var locked=store.one("select * from blockchain_transactions where id=? for update",uuid(tx,"id"));
            if (!List.of("SUBMITTED","PENDING").contains(str(locked,"status"))) return;
            store.jdbc().update("update blockchain_transactions set status=?,block_number=?,block_hash=?,confirmations=?,error_code=?,confirmed_at=case when ?='CONFIRMED' then CURRENT_TIMESTAMP else null end,failed_at=case when ? in ('FAILED','REVERTED') then CURRENT_TIMESTAMP else null end where id=?",
                    state,receipt.getBlockNumber().longValueExact(),receipt.getBlockHash(),Math.max(0,head-receipt.getBlockNumber().longValueExact()+1),error,state,state,uuid(tx,"id"));
            audit.logEvent(uuid(tx,"actor_id"),state.equals("CONFIRMED")?"BLOCKCHAIN_TX_CONFIRMED":"BLOCKCHAIN_TX_FAILED","BLOCKCHAIN_TX",str(tx,"id"),"hash="+str(tx,"tx_hash")+"; state="+state);
        });
    }
    private void quarantine(UUID id,String reason) {
        db.executeWithoutResult(s -> {
            store.lock(id);
            store.jdbc().update("update blockchain_escrows set reconciliation_status='REORG_REVIEW' where id=?",id);
            store.jdbc().update("update blockchain_transactions set status='PENDING_RECONCILIATION',error_code=? where escrow_id=? and status='CONFIRMED'",reason,id);
            audit.logEvent(null,"CHAIN_REORG_REVIEW_REQUIRED","BLOCKCHAIN_ESCROW",id.toString(),reason);
        });
    }

    void reconcile(Map<String,Object> e,long finalized) throws Exception {
        UUID id=uuid(e,"id"); String address=str(e,"contract_address");
        if (address.isEmpty()) {
            var creations=store.jdbc().queryForList("select * from blockchain_transactions where escrow_id=? and operation='CREATE' and status='CONFIRMED'",id);
            if (creations.isEmpty()) return;
            address=EscrowAbi.addressWord(chain.call(str(e,"factory_address"),EscrowAbi.encode("escrows",EscrowAbi.id(id)),finalized),0);
            if (!ChainConfig.address(address)) throw new IllegalStateException("CREATION_NOT_FOUND");
            long creationBlock=Long.parseLong(str(creations.get(0),"block_number"));
            store.jdbc().update("update blockchain_escrows set contract_address=?,start_block=? where id=? and contract_address is null",address,creationBlock,id);
            e=store.byId(id);
        }
        long last=e.get("indexed_block")==null?Long.parseLong(str(e,"start_block"))-1:Long.parseLong(str(e,"indexed_block"));
        if (last>=0 && !str(e,"indexed_hash").isEmpty() && !str(e,"indexed_hash").equalsIgnoreCase(chain.blockHash(last))) {
            quarantine(id,"FINALIZED_CHECKPOINT_CHANGED");return;
        }
        if (last>=finalized) return;
        long end=Math.min(finalized,last+500); String endHash=chain.blockHash(end);
        verifyTerms(e,address,end);
        var allocations=store.allocations(id);
        var states=new LinkedHashMap<UUID,Integer>();
        BigInteger released=BigInteger.ZERO,refunded=BigInteger.ZERO;
        for (var a:allocations) {
            String value=chain.call(address,EscrowAbi.encode("milestones",EscrowAbi.id(uuid(a,"milestone_id"))),end);
            if (!EscrowAbi.word(value,0).equals(units(a,"amount_atomic"))) throw new IllegalStateException("ALLOCATION_MISMATCH");
            int state=EscrowAbi.word(value,1).intValueExact(); if (state<0 || state>6) throw new IllegalStateException("UNKNOWN_STATE");
            states.put(uuid(a,"milestone_id"),state);
            if (state==4) released=released.add(units(a,"amount_atomic"));
            if (state==6) refunded=refunded.add(units(a,"amount_atomic"));
        }
        BigInteger funded=chain.number(address,"fundedAmount",end);
        BigInteger chainReleased=chain.number(address,"releasedAmount",end),chainRefunded=chain.number(address,"refundedAmount",end);
        BigInteger locked=funded.subtract(released).subtract(refunded);
        BigInteger balance=EscrowAbi.word(chain.call(str(e,"token_address"),EscrowAbi.encode("balanceOf",new org.web3j.abi.datatypes.Address(address)),end),0);
        if (!released.equals(chainReleased)||!refunded.equals(chainRefunded)||locked.signum()<0||balance.compareTo(locked)<0||funded.compareTo(units(e,"total_atomic"))>0)
            throw new IllegalStateException("ACCOUNTING_MISMATCH");
        int state=chain.number(address,"status",end).intValueExact();
        if (state<0||state>=STATES.length) throw new IllegalStateException("UNKNOWN_ESCROW_STATE");
        List<Log> logs=new ArrayList<>(chain.logs(address,last+1,end));
        if (last < Long.parseLong(str(e,"start_block"))) {
            String escrowTopic=org.web3j.utils.Numeric.toHexString(EscrowAbi.id(id).getValue());
            for (var event:chain.logs(str(e,"factory_address"),last+1,end)) {
                if (event.getTopics().size()>1 && event.getTopics().get(1).equalsIgnoreCase(escrowTopic)
                        && event.getTopics().get(0).equals(Hash.sha3String("EscrowCreated(bytes32,bytes32,address,address,address,address,uint256,uint256)"))) logs.add(event);
            }
        }
        for(var event:logs) if (event.isRemoved()||!event.getBlockHash().equalsIgnoreCase(chain.blockHash(event.getBlockNumber().longValueExact())))
            throw new IllegalStateException("NON_CANONICAL_EVENT");
        if (!endHash.equalsIgnoreCase(chain.blockHash(end))) throw new IllegalStateException("REORG_DURING_READ");
        final Map<String,Object> snapshot=e; final BigInteger releasedFinal=released,refundedFinal=refunded;
        db.executeWithoutResult(s -> {
            var current=store.lock(id);
            if (!Objects.equals(current.get("indexed_block"),snapshot.get("indexed_block")) || str(current,"reconciliation_status").equals("REORG_REVIEW")) return;
            for(var event:logs) {
                Long count=store.jdbc().queryForObject("select count(*) from blockchain_events where chain_id=? and tx_hash=? and log_index=?",Long.class,config.chainId,event.getTransactionHash(),event.getLogIndex().longValueExact());
                if (count!=null && count>0) continue;
                store.jdbc().update("insert into blockchain_events(escrow_id,chain_id,tx_hash,log_index,block_number,block_hash,event_topic,event_data,event_topics) values(?,?,?,?,?,?,?,?,?)",
                        id,config.chainId,event.getTransactionHash(),event.getLogIndex().longValueExact(),event.getBlockNumber().longValueExact(),event.getBlockHash(),event.getTopics().get(0),event.getData(),new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(event.getTopics()).toString());
                audit.logEvent(null,eventName(event.getTopics().get(0)),"BLOCKCHAIN_ESCROW",id.toString(),"tx="+event.getTransactionHash()+"; block="+event.getBlockNumber()+"; log="+event.getLogIndex());
            }
            states.forEach((m,status) -> {
                store.jdbc().update("update blockchain_allocations set chain_status=? where escrow_id=? and milestone_id=?",status,id,m);
                if (status==4||status==6) {
                    int changed=store.jdbc().update("update milestones set status=? where id=? and status<>?",status==4?"COMPLETED":"CANCELLED",m,status==4?"COMPLETED":"CANCELLED");
                    if(status==4 && changed>0) audit.logEvent(null,"MILESTONE_COMPLETED","MILESTONE",m.toString(),"Confirmed on-chain milestone release");
                }
            });
            store.jdbc().update("update blockchain_escrows set funded_atomic=?,released_atomic=?,refunded_atomic=?,status=?,reconciliation_status='MATCHED',indexed_block=?,indexed_hash=? where id=?",
                    new BigDecimal(funded),new BigDecimal(releasedFinal),new BigDecimal(refundedFinal),STATES[state],end,endHash,id);
            if (funded.signum()>0) {
                String dealState=state==4?"DISPUTED":state==5?"COMPLETED":state==6?"CANCELLED":"IN_PROGRESS";
                int changed=store.jdbc().update("update deals set status=? where id=? and status<>? and not exists(select 1 from disputes where deal_id=? and status='OPEN')",dealState,uuid(snapshot,"deal_id"),dealState,uuid(snapshot,"deal_id"));
                if(state==5 && changed>0) audit.logEvent(null,"DEAL_COMPLETED","DEAL",str(snapshot,"deal_id"),"Confirmed on-chain deal completion");
            }
        });
        log.info("chain_reconciled escrow={} block={} lag={}",id,end,finalized-end);
    }
    private void verifyTerms(Map<String,Object> e,String address,long block) throws Exception {
        for(String field:List.of("buyer","vendor","token","factory")) {
            if (!chain.address(address,field,block).equalsIgnoreCase(str(e,field+"_address"))) throw new IllegalStateException("IMMUTABLE_TERMS_MISMATCH");
        }
        if (!chain.number(address,"totalAmount",block).equals(units(e,"total_atomic"))
                || !chain.number(address,"milestoneCount",block).equals(BigInteger.valueOf(store.allocations(uuid(e,"id")).size()))
                || !chain.call(address,EscrowAbi.encode("escrowId"),block).equalsIgnoreCase(org.web3j.utils.Numeric.toHexString(EscrowAbi.id(uuid(e,"id")).getValue()))
                || !chain.call(address,EscrowAbi.encode("dealIdHash"),block).equalsIgnoreCase(org.web3j.utils.Numeric.toHexString(EscrowAbi.id(uuid(e,"deal_id")).getValue())))
            throw new IllegalStateException("IMMUTABLE_TERMS_MISMATCH");
    }
    private static String eventName(String topic) {
        String[][] events={{"EscrowCreated(bytes32,bytes32,address,address,address,address,uint256,uint256)","ESCROW_CREATED"},
                {"EscrowFunded(bytes32,address,address,uint256)","ESCROW_FUNDED"},
                {"MilestoneReleased(bytes32,bytes32,address,uint256)","MILESTONE_RELEASED"},
                {"RefundIssued(bytes32,bytes32,address,uint256)","REFUND_CONFIRMED"},
                {"DisputeRaised(bytes32,bytes32,address)","DISPUTE_OPENED"},
                {"DisputeResolved(bytes32,bytes32,bool)","DISPUTE_RESOLVED"}};
        for(var event:events) if(Hash.sha3String(event[0]).equalsIgnoreCase(topic))return event[1];
        return "BLOCKCHAIN_EVENT_CONFIRMED";
    }
}
