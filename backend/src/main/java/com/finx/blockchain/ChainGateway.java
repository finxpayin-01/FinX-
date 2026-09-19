package com.finx.blockchain;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.*;
import java.math.BigInteger;
import java.util.*;

@Component
public class ChainGateway {
    private final ChainConfig config;
    private Web3j client;
    public ChainGateway(ChainConfig config) { this.config = config; }
    private synchronized Web3j rpc() {
        config.requireConfigured();
        if (client == null) {
            var http = new okhttp3.OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(15)).build();
            client = Web3j.build(new HttpService(config.rpcUrl, http, false));
        }
        return client;
    }
    public long head() throws Exception {
        if (rpc().ethChainId().send().getChainId().longValueExact() != config.chainId)
            throw new IllegalStateException("WRONG_CHAIN");
        return rpc().ethBlockNumber().send().getBlockNumber().longValueExact();
    }
    public String blockHash(long height) throws Exception {
        return rpc().ethGetBlockByNumber(DefaultBlockParameter.valueOf(BigInteger.valueOf(height)), false).send().getBlock().getHash();
    }
    public String call(String address, String data, long block) throws Exception {
        var result = rpc().ethCall(org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null,address,data),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(block))).send();
        if (result.hasError() || result.isReverted() || result.getValue() == null || result.getValue().equals("0x"))
            throw new IllegalStateException("CONTRACT_READ_FAILED");
        return result.getValue();
    }
    public BigInteger number(String address, String method, long block) throws Exception {
        return EscrowAbi.word(call(address, EscrowAbi.encode(method),block),0);
    }
    public String address(String address, String method, long block) throws Exception {
        return EscrowAbi.addressWord(call(address,EscrowAbi.encode(method),block),0);
    }
    public void validateNetwork() throws Exception {
        long block = head();
        if (number(config.token,"decimals",block).intValueExact() != config.decimals)
            throw new IllegalStateException("TOKEN_DECIMALS_MISMATCH");
        if (!number(config.factory,"VERSION",block).equals(BigInteger.ONE))
            throw new IllegalStateException("UNSUPPORTED_FACTORY");
    }
    public Optional<TransactionReceipt> receipt(String hash) throws Exception {
        var result = rpc().ethGetTransactionReceipt(hash).send();
        if (result.hasError()) throw new IllegalStateException("RPC_RECEIPT_FAILED");
        return result.getTransactionReceipt();
    }
    public Optional<Transaction> transaction(String hash) throws Exception {
        var result = rpc().ethGetTransactionByHash(hash).send();
        if (result.hasError()) throw new IllegalStateException("RPC_TRANSACTION_FAILED");
        return result.getTransaction();
    }
    public List<Log> logs(String address, long from, long to) throws Exception {
        var result = rpc().ethGetLogs(new EthFilter(DefaultBlockParameter.valueOf(BigInteger.valueOf(from)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(to)), address)).send();
        if (result.hasError()) throw new IllegalStateException("RPC_LOGS_FAILED");
        return result.getLogs().stream().map(l -> (Log) l.get()).toList();
    }
    @PreDestroy void close() { if (client != null) client.shutdown(); }
}
