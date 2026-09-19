package com.finx.blockchain;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.*;
import com.finx.deal.repository.DealRepository;
import com.finx.milestone.entity.*;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.exception.*;
import com.finx.payment.config.PaymentRailPolicy;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Hash;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import java.math.*;
import java.util.*;
import static com.finx.blockchain.ChainStore.*;

@Service
public class BlockchainEscrowRail {
    @org.springframework.beans.factory.annotation.Autowired
    private com.finx.payment.service.onramp.onramper.OnramperSignatureService onramper;
    @org.springframework.beans.factory.annotation.Autowired
    private ChainGateway gateway;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    public enum Operation { CREATE, ACCEPT, ALLOWANCE, FUND, SUBMIT, APPROVE, RELEASE, REFUND, DISPUTE, CANCEL, RESOLVE_VENDOR, RESOLVE_BUYER }
    public record Allocation(UUID milestoneId, String amount) {}
    public record CreateRequest(List<Allocation> allocations) {}
    public record IntentRequest(Operation operation, UUID milestoneId, String reason) {}
    private final ChainConfig config;
    private final ChainStore store;
    private final WalletService wallets;
    private final PaymentRailPolicy policy;
    private final DealRepository deals;
    private final MilestoneRepository milestones;
    private final DisputeRepository disputes;
    private final AuditService audit;

    public BlockchainEscrowRail(ChainConfig config, ChainStore store, WalletService wallets, PaymentRailPolicy policy,
            DealRepository deals, MilestoneRepository milestones, DisputeRepository disputes, AuditService audit) {
        this.config=config; this.store=store; this.wallets=wallets; this.policy=policy;
        this.deals=deals; this.milestones=milestones; this.disputes=disputes; this.audit=audit;
    }
    public Deal access(UUID dealId, UserPrincipal actor) {
        Deal deal=deals.findById(dealId).orElseThrow(() -> new ResourceNotFoundException("Deal","id",dealId));
        if (actor == null || !(actor.getRole()==Role.ADMIN || actor.getRole()==Role.FINANCE
                || deal.getBuyerId().equals(actor.getId()) || actor.getId().equals(deal.getSellerId())
                || (actor.getRole()==Role.PROJECT_MANAGER && actor.getId().equals(deal.getProjectManagerId()))))
            throw new UnauthorizedException("Not a project participant");
        return deal;
    }
    private void active() { policy.requireBlockchain(); config.requireConfigured(); }
    private static void require(boolean value, String message) { if (!value) throw new BadRequestException(message); }
    private static void key(String key) { require(key!=null && key.matches("[A-Za-z0-9:_-]{8,150}"),"Invalid Idempotency-Key"); }

    @Transactional
    public Map<String,Object> create(UUID dealId, CreateRequest request, String key, UserPrincipal actor) {
        active(); key(key);
        Deal deal=access(dealId,actor);
        require(actor.getId().equals(deal.getBuyerId()),"Only the buyer can propose escrow terms");
        deals.findByIdForUpdate(dealId).orElseThrow();
        require(request!=null && request.allocations()!=null && !request.allocations().isEmpty()
                && request.allocations().size()<=64,"An escrow needs 1-64 token allocations");
        var proposed = new TreeMap<UUID,BigInteger>();
        for (var a: request.allocations()) {
            require(a!=null && a.milestoneId()!=null,"Milestone is required");
            require(proposed.put(a.milestoneId(),EscrowAbi.atomic(a.amount(),config.decimals))==null,"Duplicate milestone");
        }
        if (store.exists(dealId)) {
            var e=store.escrow(dealId);
            var original=new TreeMap<UUID,BigInteger>();
            store.allocations(uuid(e,"id")).forEach(a -> original.put(uuid(a,"milestone_id"),units(a,"amount_atomic")));
            require(original.equals(proposed),"Escrow terms are immutable; repeated creation has different allocations");
            return get(dealId,actor);
        }
        require(deal.getStatus()==DealStatus.ACCEPTED || deal.getStatus()==DealStatus.MILESTONES_PENDING
                || deal.getStatus()==DealStatus.AWAITING_FUNDING,"Project must be accepted before escrow creation");
        var schedule=milestones.findByDealIdOrderBySequenceAsc(dealId);
        require(schedule.size()==proposed.size() && schedule.stream().allMatch(m -> proposed.containsKey(m.getId())
                && m.getStatus()!=MilestoneStatus.COMPLETED && m.getStatus()!=MilestoneStatus.CANCELLED),"Token schedule must cover every unsettled project milestone");
        long fiatPayments=store.jdbc().queryForObject("select count(*) from payments where deal_id = ?",Long.class,dealId);
        require(fiatPayments==0,"Historical fiat projects cannot be converted to blockchain escrow");
        String buyer=wallets.address(deal.getBuyerId()),vendor=wallets.address(deal.getSellerId());
        require(!buyer.equals(vendor),"Buyer and vendor wallets must differ");
        BigInteger total=proposed.values().stream().reduce(BigInteger.ZERO,BigInteger::add);
        require(total.bitLength()<=256,"Total exceeds uint256");
        UUID id=UUID.randomUUID();
        store.jdbc().update("insert into blockchain_escrows(id,deal_id,chain_id,factory_address,token_address,token_symbol,token_decimals,buyer_address,vendor_address,total_atomic,start_block) values(?,?,?,?,?,?,?,?,?,?,?)",
                id,dealId,config.chainId,config.factory.toLowerCase(Locale.ROOT),config.token.toLowerCase(Locale.ROOT),config.symbol,config.decimals,buyer,vendor,new BigDecimal(total),config.startBlock);
        proposed.forEach((m,a) -> store.jdbc().update("insert into blockchain_allocations(escrow_id,milestone_id,amount_atomic) values(?,?,?)",id,m,new BigDecimal(a)));
        audit.logEvent(actor.getId(),"ESCROW_TERMS_CREATED","BLOCKCHAIN_ESCROW",id.toString(),"Awaiting operator deployment and both wallet acceptances; deal="+dealId);
        return get(dealId,actor);
    }

    public Map<String,Object> get(UUID dealId, UserPrincipal actor) {
        access(dealId,actor);
        if (!store.exists(dealId)) return Map.of("exists",false,"config",config.publicConfig());
        var e=store.escrow(dealId); UUID id=uuid(e,"id");
        return Map.of("exists",true,"config",config.publicConfig(),"escrow",view(e),
                "allocations",store.allocations(id).stream().map(ChainStore::view).toList(),
                "transactions",store.jdbc().queryForList("select * from blockchain_transactions where escrow_id = ? order by created_at desc limit 100",id).stream().map(ChainStore::view).toList());
    }

    @Transactional
    public Map<String,Object> intent(UUID dealId, IntentRequest request, String key, UserPrincipal actor) {
        active(); key(key); Deal deal=access(dealId,actor);
        require(request!=null && request.operation()!=null,"Operation required");
        var e=store.lock(uuid(store.escrow(dealId),"id")); UUID escrowId=uuid(e,"id");
        require(Long.parseLong(str(e,"chain_id"))==config.chainId,"Escrow is on a different configured network");
        String reason=Objects.toString(request.reason(),"").trim();
        require(reason.length()<=1000,"Reason too long");
        String fingerprint=Hash.sha3String(dealId+":"+request.operation()+":"+request.milestoneId()+":"+reason);
        var duplicate=store.jdbc().queryForList("select * from blockchain_transactions where actor_id = ? and idempotency_key = ?",actor.getId(),key);
        if (!duplicate.isEmpty()) {
            require(str(duplicate.get(0),"request_hash").equals(fingerprint),"Idempotency key reused with different request");
            return view(duplicate.get(0));
        }
        boolean buyer=actor.getId().equals(deal.getBuyerId()),vendor=actor.getId().equals(deal.getSellerId());
        boolean resolution=request.operation()==Operation.RESOLVE_VENDOR || request.operation()==Operation.RESOLVE_BUYER;
        String from=request.operation()==Operation.CREATE ? config.creator.toLowerCase(Locale.ROOT)
                : resolution ? config.arbitrator.toLowerCase(Locale.ROOT) : wallets.address(actor.getId());
        String target=str(e,"contract_address"); String data; BigInteger amount=BigInteger.ZERO;
        var allocations=store.allocations(escrowId);
        Milestone milestone=null; Map<String,Object> allocation=null;
        if (request.milestoneId()!=null) {
            milestone=milestones.findById(request.milestoneId()).orElseThrow();
            require(milestone.getDealId().equals(dealId),"Milestone is not in this project");
            allocation=allocations.stream().filter(a -> uuid(a,"milestone_id").equals(request.milestoneId())).findFirst().orElseThrow();
            amount=units(allocation,"amount_atomic");
        }
        boolean disputed=deal.getStatus()==DealStatus.DISPUTED || disputes.existsByDealIdAndStatus(dealId,DisputeStatus.OPEN)
                || str(e,"status").equals("DISPUTED");
        if (request.operation()!=Operation.CREATE) {
            require(ChainConfig.address(target),"Escrow deployment is not confirmed");
            require(str(e,"reconciliation_status").equals("MATCHED"),"Escrow is pending reconciliation");
        }
        switch(request.operation()) {
            case CREATE -> {
                require(actor.getRole()==Role.ADMIN,"Factory deployment requires an authorized operator");
                require(target.isEmpty(),"Escrow already deployed"); target=str(e,"factory_address"); data=EscrowAbi.create(e,allocations);
            }
            case ACCEPT -> { require(buyer||vendor,"Only parties can accept"); data=EscrowAbi.encode("acceptAgreement"); }
            case ALLOWANCE -> {
                require(buyer,"Only buyer can authorize funding"); target=str(e,"token_address"); amount=units(e,"total_atomic");
                data=EscrowAbi.encode("approve",new Address(str(e,"contract_address")),new Uint256(amount));
            }
            case FUND -> {
                require(buyer && !disputed,"Only buyer can fund an undisputed escrow");
                require(str(e,"status").equals("AWAITING_FUNDING"),"Both parties must accept before funding");
                amount=units(e,"total_atomic"); data=EscrowAbi.encode("fundEscrow");
            }
            case SUBMIT -> {
                require(vendor && milestone!=null && (milestone.getStatus()==MilestoneStatus.UNDER_REVIEW || milestone.getStatus()==MilestoneStatus.APPROVED),"Vendor must submit deliverables first");
                data=EscrowAbi.encode("submitMilestone",EscrowAbi.id(request.milestoneId()));
            }
            case APPROVE -> {
                require(buyer && milestone!=null && milestone.getStatus()==MilestoneStatus.APPROVED && !disputed,"Buyer wallet approval requires reviewed deliverables and no dispute");
                data=EscrowAbi.encode("approveMilestone",EscrowAbi.id(request.milestoneId()));
            }
            case RELEASE -> {
                require((buyer||vendor) && milestone!=null && milestone.getStatus()==MilestoneStatus.APPROVED && !disputed,"Release requires approved deliverables, party wallet and no dispute");
                require(str(allocation,"chain_status").equals("3"),"On-chain buyer approval is not confirmed");
                data=EscrowAbi.encode("releaseMilestone",EscrowAbi.id(request.milestoneId()));
            }
            case REFUND -> {
                require(vendor && milestone!=null && !reason.isBlank() && !disputed,"Voluntary refund requires vendor, reason and no dispute");
                data=EscrowAbi.encode("refundMilestone",EscrowAbi.id(request.milestoneId()));
            }
            case DISPUTE -> {
                require((buyer||vendor) && milestone!=null && !reason.isBlank(),"Dispute requires a party, milestone and reason");
                require(List.of("1","2","3","5").contains(str(allocation,"chain_status")),"Only funded, unsettled milestones may be disputed");
                if (!disputes.existsByMilestoneIdAndStatus(request.milestoneId(),DisputeStatus.OPEN)) {
                    var dispute=disputes.save(new com.finx.dispute.entity.Dispute(dealId,request.milestoneId(),actor.getId(),reason));
                    audit.logEvent(actor.getId(),"DISPUTE_OPENED","DISPUTE",Objects.toString(dispute.getId()),"On-chain freeze pending; deal="+dealId);
                }
                deal.setStatus(DealStatus.DISPUTED); deals.save(deal);
                data=EscrowAbi.encode("raiseDispute",EscrowAbi.id(request.milestoneId()));
            }
            case CANCEL -> { require(buyer||vendor,"Only parties can cancel"); data=EscrowAbi.encode("cancelEscrow"); }
            case RESOLVE_VENDOR, RESOLVE_BUYER -> {
                require(actor.getRole()==Role.ADMIN && milestone!=null && !reason.isBlank() && ChainConfig.address(config.arbitrator),"Resolution requires authorized admin, milestone, reason and configured council");
                require(str(allocation,"chain_status").equals("5"),"Only disputed allocations may be resolved");
                data=EscrowAbi.encode("resolveDispute",EscrowAbi.id(request.milestoneId()),new Bool(request.operation()==Operation.RESOLVE_VENDOR));
            }
            default -> throw new BadRequestException("Unsupported operation");
        }
        String operationKey=escrowId+":"+request.operation()+":"+Objects.toString(request.milestoneId(),"")+":"+(request.operation()==Operation.ACCEPT?actor.getId():"");
        var existing=store.jdbc().queryForList("select * from blockchain_transactions where operation_key = ?",operationKey);
        if (!existing.isEmpty()) {
            var previous=existing.get(0);
            if (!List.of("FAILED","REVERTED").contains(str(previous,"status"))) return view(previous);
            store.jdbc().update("update blockchain_transactions set operation_key=? where id=?",operationKey+":attempt:"+str(previous,"id"),uuid(previous,"id"));
        }
        UUID id=UUID.randomUUID();
        store.jdbc().update("insert into blockchain_transactions(id,escrow_id,milestone_id,actor_id,actor_role,operation,operation_key,idempotency_key,request_hash,chain_id,from_address,to_address,calldata,amount_atomic,reason) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,escrowId,request.milestoneId(),actor.getId(),actor.getRole().name(),request.operation().name(),operationKey,key,fingerprint,config.chainId,from,target,data,new BigDecimal(amount),reason);
        audit.logEvent(actor.getId(),request.operation()+"_INTENT_CREATED","BLOCKCHAIN_TX",id.toString(),"role="+actor.getRole()+"; deal="+dealId+"; milestone="+request.milestoneId()+"; reason="+reason);
        return view(store.one("select * from blockchain_transactions where id = ?",id));
    }

    @Transactional
    public Map<String,Object> submitted(UUID id, String hash, UserPrincipal actor) {
        active(); require(hash!=null && hash.matches("0x[0-9a-fA-F]{64}"),"Invalid transaction hash");
        var tx=store.one("select * from blockchain_transactions where id = ? for update",id);
        require(uuid(tx,"actor_id").equals(actor.getId()),"Only the intent owner can attach a transaction");
        require(str(tx,"tx_hash").isEmpty() || str(tx,"tx_hash").equalsIgnoreCase(hash),"Transaction already attached; replacement requires reconciliation");
        if (str(tx,"tx_hash").isEmpty()) {
            store.jdbc().update("update blockchain_transactions set tx_hash = ?, status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP where id = ?",hash.toLowerCase(Locale.ROOT),id);
            audit.logEvent(actor.getId(),"BLOCKCHAIN_TX_SUBMITTED","BLOCKCHAIN_TX",id.toString(),hash);
        }
        return view(store.one("select * from blockchain_transactions where id = ?",id));
    }
    public Map<String,Object> transaction(UUID id, UserPrincipal actor) {
        var tx=store.one("select * from blockchain_transactions where id = ?",id);
        access(uuid(store.byId(uuid(tx,"escrow_id")),"deal_id"),actor); return view(tx);
    }
    public Map<String,Object> replace(UUID id,String hash,UserPrincipal actor) {
        active();require(hash!=null && hash.matches("0x[0-9a-fA-F]{64}"),"Invalid replacement hash");
        var old=store.one("select * from blockchain_transactions where id=?",id);
        require(uuid(old,"actor_id").equals(actor.getId()),"Only the intent owner can track a replacement");
        require(!str(old,"operation").startsWith("RESOLVE_"),"Council replacements require council reconciliation");
        try {
            gateway.head();
            var before=gateway.transaction(str(old,"tx_hash")).orElseThrow();
            var after=gateway.transaction(hash).orElseThrow();
            require(before.getNonce().equals(after.getNonce()) && before.getFrom().equalsIgnoreCase(after.getFrom())
                    && str(old,"from_address").equalsIgnoreCase(after.getFrom()) && str(old,"to_address").equalsIgnoreCase(after.getTo())
                    && str(old,"calldata").equalsIgnoreCase(after.getInput()) && after.getValue().signum()==0,"Replacement must preserve sender, nonce, destination and calldata");
        } catch(BadRequestException e) {throw e;}
        catch(Exception e) {throw new BadRequestException("Replacement cannot yet be verified; pending reconciliation");}
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s -> {
            var locked=store.one("select * from blockchain_transactions where id=? for update",id);
            require(List.of("SUBMITTED","PENDING").contains(str(locked,"status")),"Only pending transactions may be replaced");
            require(!hash.equalsIgnoreCase(str(locked,"tx_hash")),"Replacement hash must differ");
            UUID next=UUID.randomUUID();String key=str(locked,"operation_key");
            store.jdbc().update("update blockchain_transactions set status='REPLACED',operation_key=? where id=?",key+":attempt:"+id,id);
            store.jdbc().update("insert into blockchain_transactions(id,escrow_id,milestone_id,actor_id,actor_role,operation,operation_key,idempotency_key,request_hash,chain_id,from_address,to_address,calldata,amount_atomic,reason,tx_hash,status,submitted_at) select ?,escrow_id,milestone_id,actor_id,actor_role,operation,?,?,request_hash,chain_id,from_address,to_address,calldata,amount_atomic,reason,?,'SUBMITTED',CURRENT_TIMESTAMP from blockchain_transactions where id=?",
                    next,key,"replacement:"+next,hash.toLowerCase(Locale.ROOT),id);
            audit.logEvent(actor.getId(),"BLOCKCHAIN_TX_REPLACED","BLOCKCHAIN_TX",next.toString(),"previous="+id+"; hash="+hash);
            return view(store.one("select * from blockchain_transactions where id=?",next));
        });
    }
    public List<Map<String,Object>> admin() {
        return store.jdbc().queryForList("select * from blockchain_escrows order by created_at desc limit 200").stream().map(ChainStore::view).toList();
    }
    public List<Map<String,Object>> adminTransactions() {
        return store.jdbc().queryForList("select t.*, e.deal_id from blockchain_transactions t join blockchain_escrows e on e.id=t.escrow_id order by t.created_at desc limit 500").stream().map(ChainStore::view).toList();
    }
    public String providerStatus() {
        return config.testnet && onramper != null && onramper.widgetConfigured() ? "SANDBOX_NO_SETTLEMENT" : "NOT_CONFIGURED";
    }
    @Transactional
    public Map<String,Object> funding(UUID dealId,String key,UserPrincipal actor) {
        active(); key(key); Deal deal=access(dealId,actor);
        require(deal.getBuyerId().equals(actor.getId()),"Only buyer can request funding");
        var e=store.lock(uuid(store.escrow(dealId),"id"));
        var existing=store.jdbc().queryForList("select * from blockchain_funding_intents where actor_id = ? and idempotency_key = ?",actor.getId(),key);
        if (!existing.isEmpty()) {
            require(uuid(existing.get(0),"escrow_id").equals(uuid(e,"id")),"Idempotency key belongs to another project"); return view(existing.get(0));
        }
        UUID id=UUID.randomUUID();
        store.jdbc().update("insert into blockchain_funding_intents(id,escrow_id,actor_id,idempotency_key,provider,destination_wallet,amount_atomic,status) values(?,?,?,?,?,?,?,?)",
                id,uuid(e,"id"),actor.getId(),key,config.testnet?"TEST_TOKEN_WALLET":"ONRAMP_NOT_CONFIGURED",str(e,"buyer_address"),new BigDecimal(units(e,"total_atomic")),"AWAITING_WALLET_FUNDING");
        if (config.testnet && onramper != null && onramper.widgetConfigured()) {
            String url=onramper.walletFundingWidget(id,str(e,"buyer_address"));
            store.jdbc().update("update blockchain_funding_intents set provider='ONRAMPER_SANDBOX_NO_SETTLEMENT',checkout_url=? where id=?",url,id);
        }
        audit.logEvent(actor.getId(),"FUNDING_INTENT_CREATED","BLOCKCHAIN_ESCROW",str(e,"id"),"Provider conversion is separate from contract funding");
        return view(store.one("select * from blockchain_funding_intents where id = ?",id));
    }
}
