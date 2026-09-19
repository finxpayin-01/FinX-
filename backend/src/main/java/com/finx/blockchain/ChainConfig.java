package com.finx.blockchain;

import com.finx.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ChainConfig {
    @Value("${BLOCKCHAIN_ENABLED:true}") public boolean enabled;
    @Value("${BLOCKCHAIN_RPC_URL:${FINX_RPC_URL:}}") String rpcUrl;
    @Value("${BLOCKCHAIN_CHAIN_ID:0}") public long chainId;
    @Value("${ESCROW_FACTORY_ADDRESS:}") public String factory;
    @Value("${USDC_CONTRACT_ADDRESS:}") public String token;
    @Value("${BLOCKCHAIN_TOKEN_SYMBOL:TEST-USDC}") public String symbol;
    @Value("${BLOCKCHAIN_TOKEN_DECIMALS:6}") public int decimals;
    @Value("${BLOCKCHAIN_CONFIRMATIONS:12}") public int confirmations;
    @Value("${BLOCKCHAIN_START_BLOCK:0}") public long startBlock;
    @Value("${BLOCKCHAIN_CREATOR_ADDRESS:}") public String creator;
    @Value("${BLOCKCHAIN_ARBITRATOR_ADDRESS:}") public String arbitrator;
    @Value("${BLOCKCHAIN_EXPLORER_URL:}") public String explorer;
    @Value("${BLOCKCHAIN_NETWORK_NAME:Polygon Amoy}") public String network;
    @Value("${BLOCKCHAIN_TESTNET:true}") public boolean testnet;
    @Value("${BLOCKCHAIN_WALLET_DOMAIN:localhost}") public String walletDomain;

    public boolean configured() {
        return enabled && chainId > 0 && !rpcUrl.isBlank() && address(factory) && address(token)
                && address(creator) && decimals >= 0 && decimals <= 36 && confirmations > 0 && startBlock >= 0;
    }
    public void requireConfigured() {
        if (!configured()) throw new BadRequestException("Blockchain configuration is incomplete; no transaction created.");
    }
    public static boolean address(String value) {
        return value != null && value.matches("0x[0-9a-fA-F]{40}") && !value.equalsIgnoreCase("0x" + "0".repeat(40));
    }
    public Map<String, Object> publicConfig() {
        String safeExplorer = explorer.matches("https://[a-zA-Z0-9.-]+/?") ? explorer.replaceAll("/$", "") : "";
        return Map.of("configured", configured(), "chainId", Long.toString(chainId), "network", network,
                "token", token, "symbol", symbol, "decimals", decimals, "confirmations", confirmations,
                "testnet", testnet, "explorer", safeExplorer, "signer", "EXTERNAL_WALLET");
    }
}
