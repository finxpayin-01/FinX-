package com.finx.payment.service.onramp;

import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Optional;
import java.math.BigInteger;

@Service
public class BlockchainSettlementVerifier {
    private static final Logger log = LoggerFactory.getLogger(BlockchainSettlementVerifier.class);
    
    private final BlockchainProperties properties;
    private Web3j web3j;
    
    public BlockchainSettlementVerifier(BlockchainProperties properties) {
        this.properties = properties;
        if (properties.getRpcUrl() != null && !properties.getRpcUrl().isBlank()) {
            try {
                this.web3j = Web3j.build(new HttpService(properties.getRpcUrl()));
            } catch (Exception e) {
                log.error("Failed to initialize Web3j", e);
            }
        }
    }

    public SettlementStatus verifySettlement(String txHash, String expectedAsset, String expectedAmount) {
        if (web3j == null || properties.getRpcUrl() == null || properties.getRpcUrl().isBlank()) {
            log.error("Blockchain RPC configuration missing or unreachable.");
            return SettlementStatus.RPC_UNAVAILABLE;
        }
        
        log.info("Verifying blockchain settlement for tx: {}", txHash);
        try {
            Optional<TransactionReceipt> receiptOpt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receiptOpt.isEmpty()) {
                log.warn("Transaction hash {} not found on chain yet.", txHash);
                return SettlementStatus.PENDING;
            }
            
            TransactionReceipt receipt = receiptOpt.get();
            if (!"0x1".equals(receipt.getStatus())) {
                log.error("Transaction {} reverted.", txHash);
                return SettlementStatus.REJECTED;
            }
            
            // Check confirm block bounds
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger txBlock = receipt.getBlockNumber();
            int reqConfirmations = properties.getRequiredConfirmations() != null ? properties.getRequiredConfirmations() : 2;
            
            if (latestBlock.subtract(txBlock).compareTo(BigInteger.valueOf(reqConfirmations)) < 0) {
                log.warn("Transaction {} lacks required {} confirmations.", txHash, reqConfirmations);
                return SettlementStatus.PENDING;
            }
            log.info("Transaction {} fully settled dynamically.", txHash);
            return SettlementStatus.VERIFIED;
        } catch (Exception e) {
            log.error("RPC Error verifying settlement for tx: " + txHash, e);
            return SettlementStatus.RPC_UNAVAILABLE;
        }
    }
}
