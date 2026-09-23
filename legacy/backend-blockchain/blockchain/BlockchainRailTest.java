package com.finx.blockchain;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.*;
import com.finx.deal.entity.*;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.milestone.entity.*;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.payment.config.PaymentRailPolicy;
import com.finx.payment.service.*;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.core.methods.response.*;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.finx.blockchain.ChainStore.*;
import static com.finx.blockchain.BlockchainEscrowRail.*;

class BlockchainRailTest {
    ChainStore store; ChainConfig config; BlockchainEscrowRail rail; ChainGateway gateway;
    ChainIndexer indexer; WalletService wallets; DealRepository deals; MilestoneRepository milestones;
    DisputeRepository disputes; AuditService audit; TransactionTemplate db;
    UUID buyer=UUID.randomUUID(),vendor=UUID.randomUUID(),dealId=UUID.randomUUID(),milestoneId=UUID.randomUUID(),admin=UUID.randomUUID();
    Deal deal; Milestone milestone;
    String buyerAddress="0x"+"1".repeat(40),vendorAddress="0x"+"2".repeat(40),escrowAddress="0x"+"5".repeat(40);
    UserPrincipal actor(UUID id,Role role) {return new UserPrincipal(id,"Test","test@example.test","",role,UserStatus.ACTIVE,List.of());}

    @BeforeEach void setup() {
        var source=new JdbcDataSource(); source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("create table users(id uuid primary key)");
        jdbc.execute("create table deals(id uuid primary key,status varchar(50))");
        jdbc.execute("create table milestones(id uuid primary key,status varchar(50))");
        jdbc.execute("create table payments(id uuid primary key,deal_id uuid)");
        jdbc.execute("create table disputes(id uuid primary key,deal_id uuid,status varchar(50))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V10__blockchain_escrow.sql")).execute(source);
        for(UUID user:List.of(buyer,vendor,admin)) jdbc.update("insert into users values(?)",user);
        jdbc.update("insert into deals values(?,'ACCEPTED')",dealId);
        jdbc.update("insert into milestones values(?,'PENDING')",milestoneId);
        store=new ChainStore(jdbc);config=new ChainConfig();config.enabled=true;config.rpcUrl="http://localhost:1";config.chainId=31337;
        config.factory="0x"+"3".repeat(40);config.token="0x"+"4".repeat(40);config.creator="0x"+"6".repeat(40);
        config.arbitrator="0x"+"7".repeat(40);config.decimals=6;config.confirmations=3;config.symbol="TEST";config.network="Local";config.explorer="";config.walletDomain="localhost";
        deals=mock(DealRepository.class);milestones=mock(MilestoneRepository.class);disputes=mock(DisputeRepository.class);audit=mock(AuditService.class);wallets=mock(WalletService.class);
        when(wallets.address(buyer)).thenReturn(buyerAddress);when(wallets.address(vendor)).thenReturn(vendorAddress);
        deal=new Deal("Test","",buyer,vendor,new BigDecimal("10000"),"INR",DealStatus.ACCEPTED);deal.setId(dealId);
        milestone=new Milestone(dealId,"Test","",1,new BigDecimal("10000"),"INR",null);milestone.setId(milestoneId);
        when(deals.findById(dealId)).thenReturn(Optional.of(deal));when(deals.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestones.findById(milestoneId)).thenReturn(Optional.of(milestone));when(milestones.findByDealIdOrderBySequenceAsc(dealId)).thenReturn(List.of(milestone));
        rail=new BlockchainEscrowRail(config,store,wallets,new PaymentRailPolicy("BLOCKCHAIN"),deals,milestones,disputes,audit);
        var manager=new DataSourceTransactionManager(source);db=new TransactionTemplate(manager);
        gateway=mock(ChainGateway.class);indexer=new ChainIndexer(config,gateway,store,audit,manager);
    }
    Map<String,Object> create() {return db.execute(s -> rail.create(dealId,new CreateRequest(List.of(new Allocation(milestoneId,"1.234567"))),"creation-123",actor(buyer,Role.BUYER)));}
    Map<String,Object> intent(Operation operation,UUID milestone,UserPrincipal actor,String key) {
        return db.execute(s -> rail.intent(dealId,new IntentRequest(operation,milestone,"Test reason"),key,actor));
    }
    void deployed() {
        create();store.jdbc().update("update blockchain_escrows set contract_address=?,status='AWAITING_FUNDING',reconciliation_status='MATCHED'",escrowAddress);
    }
    @Test void decimalAccountingNeverRoundsOrAssumes18Decimals() {
        assertEquals(new BigInteger("1234567"),EscrowAbi.atomic("1.234567",6));
        assertThrows(RuntimeException.class,() -> EscrowAbi.atomic("0.0000001",6));
        assertThrows(RuntimeException.class,() -> EscrowAbi.atomic("0",6));
        assertThrows(RuntimeException.class,() -> EscrowAbi.atomic("-1",6));
        assertThrows(RuntimeException.class,() -> EscrowAbi.atomic("1e100",6));
    }
    @Test void creationIsIdempotentAndDoesNotConvertInrToTokens() {
        create();create();assertEquals(1L,store.jdbc().queryForObject("select count(*) from blockchain_escrows",Long.class));
        assertEquals(new BigInteger("1234567"),units(store.escrow(dealId),"total_atomic"));
        assertEquals(BigInteger.ZERO,units(store.escrow(dealId),"funded_atomic"));
        assertThrows(RuntimeException.class,() -> rail.create(dealId,new CreateRequest(List.of(new Allocation(milestoneId,"2"))),"creation-123",actor(buyer,Role.BUYER)));
    }
    @Test void changingGlobalRailCannotMixProjectAccounting() {
        create();
        var guard=new ChainWorkflowGuard(store,new PaymentRailPolicy("RAZORPAY"));
        assertThrows(RuntimeException.class,() -> guard.fiatOnly(dealId));
        assertThrows(RuntimeException.class,() -> guard.mutableSchedule(dealId));
        assertThrows(RuntimeException.class,() -> guard.funded(dealId));
        assertDoesNotThrow(() -> guard.fiatOnly(UUID.randomUUID()));
    }
    @Test void concurrentFundingRequestsShareOneIntent() throws Exception {
        deployed();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(() -> {gate.await();return intent(Operation.FUND,null,actor(buyer,Role.BUYER),"concurrent-1");});
            var b=pool.submit(() -> {gate.await();return intent(Operation.FUND,null,actor(buyer,Role.BUYER),"concurrent-2");});
            gate.countDown();
            assertEquals(a.get().get("id"),b.get().get("id"));
            assertEquals(1L,store.jdbc().queryForObject("select count(*) from blockchain_transactions",Long.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void unrelatedUserCannotReadOrCreateEscrow() {
        create();assertThrows(RuntimeException.class,() -> rail.get(dealId,actor(UUID.randomUUID(),Role.BUYER)));
        assertThrows(RuntimeException.class,() -> rail.create(dealId,new CreateRequest(List.of()),"creation-124",actor(vendor,Role.SELLER)));
    }
    @Test void fundingIntentIsOwnedAndIdempotent() {
        create();var a=rail.funding(dealId,"funding-123",actor(buyer,Role.BUYER));
        assertEquals(a,rail.funding(dealId,"funding-123",actor(buyer,Role.BUYER)));
        assertThrows(RuntimeException.class,() -> rail.funding(dealId,"funding-124",actor(vendor,Role.SELLER)));
    }
    @Test void intentReusesOperationAndRejectsChangedKeyPayload() {
        deployed();var a=intent(Operation.FUND,null,actor(buyer,Role.BUYER),"intent-123");
        assertEquals(a,intent(Operation.FUND,null,actor(buyer,Role.BUYER),"intent-124"));
        assertThrows(RuntimeException.class,() -> intent(Operation.ACCEPT,null,actor(buyer,Role.BUYER),"intent-123"));
        assertEquals(BigInteger.ZERO,units(store.escrow(dealId),"funded_atomic"));
    }
    @Test void pmHasReadAccessButNoReleaseAuthority() {
        deployed();deal.setProjectManagerId(admin);milestone.setStatus(MilestoneStatus.APPROVED);when(wallets.address(admin)).thenReturn(config.creator);
        assertTrue((Boolean)rail.get(dealId,actor(admin,Role.PROJECT_MANAGER)).get("exists"));
        assertThrows(RuntimeException.class,() -> intent(Operation.RELEASE,milestoneId,actor(admin,Role.PROJECT_MANAGER),"intent-pm1"));
    }
    @Test void disputeBlocksNormalRelease() {
        deployed();milestone.setStatus(MilestoneStatus.APPROVED);deal.setStatus(DealStatus.DISPUTED);
        assertThrows(RuntimeException.class,() -> intent(Operation.RELEASE,milestoneId,actor(buyer,Role.BUYER),"intent-dispute"));
    }
    @Test void financialEndpointsNeverInvokeRazorpayWhenPaused() {
        var razorpay=mock(RazorpayService.class);var escrow=mock(EscrowService.class);
        var payment=new PaymentService(null,null,null,razorpay,escrow,null,null);
        assertThrows(RuntimeException.class,() -> payment.createPaymentOrder(null,null));
        assertThrows(RuntimeException.class,() -> payment.verifyPayment(null,null));
        assertThrows(RuntimeException.class,() -> payment.processWebhook("{}","signature"));
        assertEquals("BLOCKCHAIN",payment.getPaymentConfig().get("rail"));
        verifyNoInteractions(razorpay,escrow);
    }
    Map<String,Object> submitted() {
        deployed();var tx=intent(Operation.FUND,null,actor(buyer,Role.BUYER),"intent-track");
        return rail.submitted(uuid(tx,"id"),"0x"+"a".repeat(64),actor(buyer,Role.BUYER));
    }
    TransactionReceipt receipt(Map<String,Object> tx,boolean success) throws Exception {
        var r=new TransactionReceipt();r.setTransactionHash(str(tx,"tx_hash"));r.setBlockNumber("0xa");r.setBlockHash("0x"+"b".repeat(64));r.setStatus(success?"0x1":"0x0");
        var t=new Transaction();t.setFrom(str(tx,"from_address"));t.setTo(str(tx,"to_address"));t.setInput(str(tx,"calldata"));t.setValue("0x0");
        when(gateway.receipt(str(tx,"tx_hash"))).thenReturn(Optional.of(r));when(gateway.transaction(str(tx,"tx_hash"))).thenReturn(Optional.of(t));when(gateway.blockHash(10)).thenReturn(r.getBlockHash());return r;
    }
    @Test void submissionAndInsufficientConfirmationsNeverCreditFunding() throws Exception {
        var tx=submitted();receipt(tx,true);indexer.track(tx,10,8);
        assertEquals("PENDING",str(store.one("select * from blockchain_transactions where id=?",uuid(tx,"id")),"status"));
        assertEquals(BigInteger.ZERO,units(store.escrow(dealId),"funded_atomic"));
    }
    @Test void revertedReceiptIsNotSettlement() throws Exception {
        var tx=submitted();receipt(tx,false);indexer.track(tx,12,10);
        assertEquals("REVERTED",str(store.one("select * from blockchain_transactions where id=?",uuid(tx,"id")),"status"));
        assertEquals(BigInteger.ZERO,units(store.escrow(dealId),"funded_atomic"));
    }
    @Test void foreignTransactionFailsEvenWithSuccessfulReceipt() throws Exception {
        var tx=submitted();receipt(tx,true);var t=gateway.transaction(str(tx,"tx_hash")).orElseThrow();t.setTo(config.token);indexer.track(tx,12,10);
        assertEquals("TRANSACTION_MISMATCH",str(store.one("select * from blockchain_transactions where id=?",uuid(tx,"id")),"error_code"));
    }
    @Test void confirmedReceiptDisappearingQuarantinesEscrow() throws Exception {
        var tx=submitted();receipt(tx,true);indexer.track(tx,12,10);
        tx=store.one("select * from blockchain_transactions where id=?",uuid(tx,"id"));
        when(gateway.receipt(str(tx,"tx_hash"))).thenReturn(Optional.empty());indexer.track(tx,13,11);
        assertEquals("REORG_REVIEW",str(store.escrow(dealId),"reconciliation_status"));
    }
    @Test void eventUniquenessAndAccountingConstraintsAreEnforced() {
        create();UUID id=uuid(store.escrow(dealId),"id");
        String insert="insert into blockchain_events(escrow_id,chain_id,tx_hash,log_index,block_number,block_hash,event_topic,event_data) values(?,31337,'hash',0,1,'block','topic','data')";
        store.jdbc().update(insert,id);assertThrows(RuntimeException.class,() -> store.jdbc().update(insert,id));
        assertThrows(RuntimeException.class,() -> store.jdbc().update("update blockchain_escrows set released_atomic=1 where id=?",id));
    }
    @Test void offRampSimulationNeverReportsBankSettlement() {
        var result=new OffRampProvider.LocalSimulation().request(UUID.randomUUID(),vendorAddress,config.token,BigInteger.ONE);
        assertTrue(result.simulated());assertEquals("SIMULATED_PENDING",result.status());
        assertThrows(RuntimeException.class,() -> new OffRampProvider.Unconfigured().request(UUID.randomUUID(),vendorAddress,config.token,BigInteger.ONE));
    }
}
