package com.finx.blockchain;

import com.finx.exception.BadRequestException;
import com.finx.payment.service.onramp.onramper.OnramperSignatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import java.util.*;

@Component
public class ChainConfig {
    @Value("${BLOCKCHAIN_ENABLED:true}") public boolean enabled;
    @Value("${BLOCKCHAIN_RPC_URL:${FINX_RPC_URL:https://polygon-amoy-bor-rpc.publicnode.com}}") public String rpcUrl;
    @Value("${BLOCKCHAIN_CHAIN_ID:${FINX_CHAIN_ID:80002}}") public long chainId;
    @Value("${ESCROW_FACTORY_ADDRESS:${FINX_ESCROW_CONTRACT_ADDRESS:}}") public String factory;
    @Value("${USDC_CONTRACT_ADDRESS:${FINX_USDC_CONTRACT_ADDRESS:0x41e94eb019c0762f9bfcf9fb1e58725bfb0e7582}}") public String token;
    @Value("${BLOCKCHAIN_TOKEN_SYMBOL:TEST-USDC}") public String symbol;
    @Value("${BLOCKCHAIN_TOKEN_DECIMALS:6}") public int decimals;
    @Value("${BLOCKCHAIN_CONFIRMATIONS:${FINX_REQUIRED_CONFIRMATIONS:12}}") public int confirmations;
    @Value("${BLOCKCHAIN_START_BLOCK:0}") public long startBlock;
    @Value("${BLOCKCHAIN_CREATOR_ADDRESS:${BLOCKCHAIN_SIGNER_ADDRESS:}}") public String creator;
    @Value("${BLOCKCHAIN_ARBITRATOR_ADDRESS:}") public String arbitrator;
    @Value("${BLOCKCHAIN_EXPLORER_URL:https://amoy.polygonscan.com}") public String explorer;
    @Value("${BLOCKCHAIN_NETWORK_NAME:Polygon Amoy}") public String network;
    @Value("${BLOCKCHAIN_TESTNET:true}") public boolean testnet;
    @Value("${BLOCKCHAIN_WALLET_DOMAIN:localhost}") public String walletDomain;

    @Autowired(required = false)
    private OnramperSignatureService onramper;

    volatile long lastRpcCheck = 0;
    volatile boolean lastRpcConnected = false;
    volatile Long lastRemoteChainId = null;

    public boolean configured() {
        return enabled && chainId > 0 && rpcUrl != null && !rpcUrl.isBlank() && address(factory) && address(token)
                && address(creator) && decimals >= 0 && decimals <= 36 && confirmations > 0 && startBlock >= 0;
    }

    public void requireConfigured() {
        if (!configured()) throw new BadRequestException("Blockchain configuration is incomplete; no transaction created.");
    }

    public void requireWalletVerificationAvailable() {
        if (!enabled || chainId <= 0) {
            throw new BadRequestException("Blockchain wallet verification is disabled or unconfigured.");
        }
    }

    public static boolean address(String value) {
        return value != null && value.matches("0x[0-9a-fA-F]{40}") && !value.equalsIgnoreCase("0x" + "0".repeat(40));
    }

    public boolean isRpcConfigured() {
        return rpcUrl != null && !rpcUrl.isBlank();
    }

    public synchronized boolean isRpcConnected() {
        long now = System.currentTimeMillis();
        if (now - lastRpcCheck < 15000) {
            return lastRpcConnected;
        }
        probeRpc();
        return lastRpcConnected;
    }

    public synchronized Long getRemoteChainId() {
        long now = System.currentTimeMillis();
        if (now - lastRpcCheck >= 15000) {
            probeRpc();
        }
        return lastRemoteChainId;
    }

    private void probeRpc() {
        lastRpcCheck = System.currentTimeMillis();
        if (!isRpcConfigured()) {
            lastRpcConnected = false;
            lastRemoteChainId = null;
            return;
        }
        try {
            var http = new okhttp3.OkHttpClient.Builder()
                    .callTimeout(java.time.Duration.ofSeconds(3))
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .readTimeout(java.time.Duration.ofSeconds(3))
                    .build();
            Web3j web3j = Web3j.build(new HttpService(rpcUrl, http, false));
            var resp = web3j.ethChainId().send();
            if (resp != null && resp.getChainId() != null) {
                lastRemoteChainId = resp.getChainId().longValueExact();
                lastRpcConnected = true;
            } else {
                lastRpcConnected = false;
                lastRemoteChainId = null;
            }
        } catch (Exception e) {
            lastRpcConnected = false;
            lastRemoteChainId = null;
        }
    }

    public boolean isOnrampConfigured() {
        return onramper != null && onramper.widgetConfigured();
    }

    public String computeStatus() {
        if (!enabled) return "BLOCKCHAIN_DISABLED";
        if (!isRpcConfigured()) return "RPC_UNAVAILABLE";
        if (!isRpcConnected()) return "RPC_UNAVAILABLE";
        Long remoteChain = getRemoteChainId();
        if (remoteChain != null && remoteChain != chainId) return "WRONG_CHAIN";
        if (!address(token)) return "TOKEN_CONTRACT_MISSING";
        if (!address(factory)) return "ESCROW_CONTRACT_MISSING";
        if (!address(creator)) return "SIGNER_NOT_CONFIGURED";
        if (!isOnrampConfigured()) return "ONRAMP_NOT_CONFIGURED";
        return "READY";
    }

    public boolean isReady() {
        return "READY".equals(computeStatus());
    }

    public Map<String, Object> publicConfig() {
        String safeExplorer = (explorer != null && explorer.matches("https://[a-zA-Z0-9.-]+/?")) ? explorer.replaceAll("/$", "") : "";
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("configured", configured());
        map.put("ready", isReady());
        map.put("status", computeStatus());
        map.put("enabled", enabled);
        map.put("network", network != null ? network : "Polygon Amoy");
        map.put("chainId", Long.toString(chainId));
        map.put("token", token != null ? token : "");
        map.put("symbol", symbol != null ? symbol : "TEST-USDC");
        map.put("decimals", decimals);
        map.put("confirmations", confirmations);
        map.put("testnet", testnet);
        map.put("explorer", safeExplorer);
        map.put("signer", "EXTERNAL_WALLET");
        map.put("rpcConfigured", isRpcConfigured());
        map.put("rpcConnected", isRpcConnected());
        map.put("factory", factory != null ? factory : "");
        map.put("creator", creator != null ? creator : "");
        map.put("escrowContractConfigured", address(factory));
        map.put("tokenConfigured", address(token));
        map.put("signerConfigured", address(creator));
        map.put("onrampConfigured", isOnrampConfigured());
        map.put("walletVerificationAvailable", enabled && chainId > 0);
        map.put("escrowCreationAvailable", enabled && chainId > 0 && address(factory));
        return Collections.unmodifiableMap(map);
    }
}
